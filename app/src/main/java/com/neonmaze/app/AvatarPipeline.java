package com.neonmaze.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, local-only photo-avatar ingestion and storage.
 *
 * <p>The caller obtains a {@link Uri} through Android Photo Picker / Activity
 * Result APIs and passes it here. This class never requests broad media or
 * storage permissions and never persists the source URI. The source is first
 * copied to a size-limited private staging file, then decoded from that exact
 * byte sequence, normalized for all eight EXIF orientations, centre-cropped,
 * and written as a 512x512 PNG to a unique app-generated internal filename.</p>
 *
 * <p>A new file is fully committed before its path is returned. Existing
 * avatar files are never overwritten by this operation; the owner of the
 * profile transaction decides when an older file may be deleted.</p>
 */
public final class AvatarPipeline {

    public static final int AVATAR_DIMENSION = 512;
    public static final long MAX_SOURCE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_DECODED_EDGE = 1024;
    private static final int MAX_SOURCE_EDGE = 100_000;
    private static final long MAX_SOURCE_PIXELS = 500_000_000L;
    private static final String DIRECTORY_NAME = "avatars";
    private static final String AVATAR_PREFIX = "avatar_";
    private static final String AVATAR_SUFFIX = ".png";
    private static final String LEGACY_AVATAR_NAME = "avatar.png";
    private static final String INPUT_PREFIX = ".input_";
    private static final String INPUT_SUFFIX = ".tmp";
    private static final long ORPHAN_AVATAR_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long STALE_INPUT_TTL_MS = 24L * 60L * 60L * 1000L;

    private final Context appContext;
    private final File avatarDirectory;

    public AvatarPipeline(@NonNull Context context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        Context application = context.getApplicationContext();
        this.appContext = application != null ? application : context;
        this.avatarDirectory = new File(appContext.getFilesDir(), DIRECTORY_NAME);
    }

    /**
     * Imports and normalizes an image URI into a new internal avatar file.
     * The caller owns the returned file reference and must persist its path
     * before deleting the previously active avatar.
     */
    @NonNull
    public AvatarResult save(@NonNull Uri sourceUri) throws IOException {
        if (sourceUri == null) throw new IllegalArgumentException("sourceUri must not be null");
        ContentResolver resolver = appContext.getContentResolver();
        String mime = resolver.getType(sourceUri);
        if (mime == null || !mime.regionMatches(true, 0, "image/", 0, 6)) {
            throw new IOException("selected content is not an image");
        }
        ensureDirectory();

        File stagedSource = new File(avatarDirectory,
                INPUT_PREFIX + UUID.randomUUID().toString() + INPUT_SUFFIX);
        File target = new File(avatarDirectory,
                AVATAR_PREFIX + UUID.randomUUID().toString() + AVATAR_SUFFIX);
        Bitmap decoded = null;
        Bitmap oriented = null;
        Bitmap cropped = null;
        Bitmap output = null;
        boolean committed = false;
        try {
            copyBoundedSource(resolver, sourceUri, stagedSource);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(stagedSource.getAbsolutePath(), bounds);
            validateBounds(bounds.outWidth, bounds.outHeight);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inScaled = false;
            decoded = BitmapFactory.decodeFile(stagedSource.getAbsolutePath(), options);
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                throw new IOException("image decoder could not decode the selected content");
            }

            int orientation = readOrientation(stagedSource);
            oriented = applyExifOrientation(decoded, orientation);
            int side = Math.min(oriented.getWidth(), oriented.getHeight());
            int left = (oriented.getWidth() - side) / 2;
            int top = (oriented.getHeight() - side) / 2;
            cropped = Bitmap.createBitmap(oriented, left, top, side, side);
            output = (cropped.getWidth() == AVATAR_DIMENSION
                    && cropped.getHeight() == AVATAR_DIMENSION)
                    ? cropped
                    : Bitmap.createScaledBitmap(cropped, AVATAR_DIMENSION,
                    AVATAR_DIMENSION, true);

            long outputBytes = writePngAtomically(target, output);
            committed = true;
            return new AvatarResult(target.getAbsolutePath(), target.getName(),
                    outputBytes, AVATAR_DIMENSION, AVATAR_DIMENSION);
        } catch (OutOfMemoryError memoryFailure) {
            throw new IOException("selected image exceeds available decoding memory", memoryFailure);
        } finally {
            recycleDistinct(output, cropped, oriented, decoded);
            if (stagedSource.exists() && !stagedSource.delete()) {
                // A stale private staging file is harmless and is never exposed as an avatar.
            }
            if (!committed) {
                // Remove the base and any AtomicFile sidecars after a failed new-file write.
                new AtomicFile(target).delete();
                deleteSidecars(target);
            }
        }
    }

    /** Deletes one avatar only when its canonical path belongs to this app's avatar directory. */
    public boolean delete(@Nullable String absolutePath) throws IOException {
        if (absolutePath == null || absolutePath.trim().isEmpty()) return false;
        File file = new File(absolutePath);
        if (!isManagedAvatarFile(file)) {
            throw new IOException("refusing to delete a file outside the managed avatar directory");
        }
        boolean existed = file.exists() || new File(file.getPath() + ".bak").exists()
                || new File(file.getPath() + ".new").exists();
        new AtomicFile(file).delete();
        boolean sidecarsRemoved = deleteSidecars(file);
        if (existed && (!sidecarsRemoved || file.exists()
                || new File(file.getPath() + ".bak").exists()
                || new File(file.getPath() + ".new").exists())) {
            throw new IOException("unable to delete managed avatar artifacts");
        }
        return existed;
    }

    /**
     * Deletes every committed avatar and staging artifact owned by this pipeline.
     * Unrelated files and paths outside the private avatar directory are never touched.
     */
    public void deleteAll() throws IOException {
        if (!avatarDirectory.exists()) return;
        File[] files = avatarDirectory.listFiles();
        if (files == null) throw new IOException("unable to enumerate avatar directory");
        Set<String> deletedBases = new HashSet<String>();
        IOException failure = null;
        for (File file : files) {
            File base = managedAvatarBaseForArtifact(file);
            if (base != null && deletedBases.add(base.getAbsolutePath())) {
                try {
                    new AtomicFile(base).delete();
                    if (!deleteSidecars(base) || base.exists()) {
                        throw new IOException("unable to delete avatar artifacts: " + base.getName());
                    }
                } catch (IOException | RuntimeException exception) {
                    if (failure == null) failure = new IOException(
                            "unable to delete managed avatar: " + base.getName(), exception);
                }
            } else if (isManagedInputFile(file)) {
                if (file.exists() && !file.delete() && failure == null) {
                    failure = new IOException("unable to delete managed staging file: "
                            + file.getName());
                }
            }
        }
        if (failure != null) throw failure;
    }

    /**
     * Removes abandoned private staging files and old unreferenced avatar files.
     * The active profile avatar is never deleted. Unreferenced committed images
     * receive a seven-day grace period so a Photo Editor can retry after a DB
     * failure or process interruption.
     */
    public void cleanupOrphans(@Nullable String activeAvatarPath) throws IOException {
        if (!avatarDirectory.exists()) return;
        File[] files = avatarDirectory.listFiles();
        if (files == null) throw new IOException("unable to enumerate avatar directory");
        long now = System.currentTimeMillis();
        long orphanCutoff = now - ORPHAN_AVATAR_TTL_MS;
        long stagingCutoff = now - STALE_INPUT_TTL_MS;
        String activeCanonical = null;
        if (isManagedAvatarPath(activeAvatarPath)) {
            activeCanonical = new File(activeAvatarPath).getCanonicalPath();
        }

        Set<String> processedBases = new HashSet<String>();
        IOException failure = null;
        for (File file : files) {
            if (isManagedInputFile(file)) {
                if (file.lastModified() > 0L && file.lastModified() < stagingCutoff
                        && file.exists() && !file.delete() && failure == null) {
                    failure = new IOException("unable to delete stale avatar staging file: "
                            + file.getName());
                }
                continue;
            }
            File base = managedAvatarBaseForArtifact(file);
            if (base == null || !processedBases.add(base.getAbsolutePath())) continue;
            try {
                String canonical = base.getCanonicalPath();
                if (canonical.equals(activeCanonical)) continue;
                long modified = Math.max(base.lastModified(), sidecarLastModified(base));
                if (modified > 0L && modified < orphanCutoff) {
                    new AtomicFile(base).delete();
                    if (!deleteSidecars(base) || base.exists()) {
                        throw new IOException("unable to delete orphan avatar artifacts: " + base.getName());
                    }
                }
            } catch (IOException | RuntimeException exception) {
                if (failure == null) failure = new IOException(
                        "unable to clean orphan avatar: " + base.getName(), exception);
            }
        }
        if (failure != null) throw failure;
    }

    private File managedAvatarBaseForArtifact(File candidate) {
        try {
            File canonicalDirectory = avatarDirectory.getCanonicalFile();
            File canonicalCandidate = candidate.getCanonicalFile();
            if (!canonicalDirectory.equals(canonicalCandidate.getParentFile())) return null;
            String name = canonicalCandidate.getName();
            if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
            else if (name.endsWith(".new")) name = name.substring(0, name.length() - 4);
            if (!isManagedAvatarName(name)) return null;
            return new File(canonicalDirectory, name);
        } catch (IOException exception) {
            return null;
        }
    }

    private static boolean isManagedAvatarName(String name) {
        return LEGACY_AVATAR_NAME.equals(name)
                || (name.startsWith(AVATAR_PREFIX) && name.endsWith(AVATAR_SUFFIX));
    }

    private static long sidecarLastModified(File base) {
        long modified = 0L;
        File backup = new File(base.getPath() + ".bak");
        File fresh = new File(base.getPath() + ".new");
        if (backup.exists()) modified = Math.max(modified, backup.lastModified());
        if (fresh.exists()) modified = Math.max(modified, fresh.lastModified());
        return modified;
    }

    private static boolean deleteSidecars(File base) {
        File backup = new File(base.getPath() + ".bak");
        File fresh = new File(base.getPath() + ".new");
        boolean backupRemoved = !backup.exists() || backup.delete();
        boolean freshRemoved = !fresh.exists() || fresh.delete();
        return backupRemoved && freshRemoved && !backup.exists() && !fresh.exists();
    }

    public boolean isManagedAvatarPath(@Nullable String absolutePath) {
        if (absolutePath == null || absolutePath.trim().isEmpty()) return false;
        return isManagedAvatarFile(new File(absolutePath));
    }

    private void ensureDirectory() throws IOException {
        if (!avatarDirectory.exists() && !avatarDirectory.mkdirs()) {
            throw new IOException("unable to create private avatar directory");
        }
        if (!avatarDirectory.isDirectory()) {
            throw new IOException("private avatar path is not a directory");
        }
    }

    private static void copyBoundedSource(ContentResolver resolver, Uri uri, File target)
            throws IOException {
        long total = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target, false)) {
            if (input == null) throw new IOException("unable to open selected image");
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                total += count;
                if (total > MAX_SOURCE_BYTES) {
                    throw new IOException("selected image exceeds 2 MiB source limit");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            output.getFD().sync();
        } catch (IOException exception) {
            if (target.exists() && !target.delete()) exception.addSuppressed(
                    new IOException("unable to remove failed source staging file"));
            throw exception;
        }
        if (total <= 0L) {
            if (target.exists() && !target.delete()) {
                throw new IOException("empty source image and failed staging cleanup");
            }
            throw new IOException("selected image is empty");
        }
    }

    private static void validateBounds(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE) {
            throw new IOException("selected image dimensions are invalid or exceed the limit");
        }
        long pixels = (long) width * (long) height;
        if (pixels > MAX_SOURCE_PIXELS) {
            throw new IOException("selected image pixel count exceeds the safety limit");
        }
    }

    private static int sampleSizeFor(int width, int height) {
        int sample = 1;
        while (Math.max(ceilDivide(width, sample), ceilDivide(height, sample))
                > MAX_DECODED_EDGE && sample <= (1 << 28)) {
            sample <<= 1;
        }
        return sample;
    }

    private static int ceilDivide(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static int readOrientation(File source) throws IOException {
        ExifInterface exif = new ExifInterface(source.getAbsolutePath());
        return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL);
    }

    private static Bitmap applyExifOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return source;
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(),
                matrix, true);
    }

    private static long writePngAtomically(File target, Bitmap bitmap) throws IOException {
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        boolean completed = false;
        try {
            output = atomic.startWrite();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IOException("PNG encoder failed to write avatar");
            }
            output.flush();
            output.getFD().sync();
            atomic.finishWrite(output);
            completed = true;
            return target.length();
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("unable to encode avatar", exception);
        } finally {
            if (!completed && output != null) atomic.failWrite(output);
        }
    }

    private boolean isManagedAvatarFile(File file) {
        return managedAvatarBaseForArtifact(file) != null
                && !file.getName().endsWith(".bak")
                && !file.getName().endsWith(".new");
    }

    private boolean isManagedInputFile(File file) {
        try {
            File canonicalDirectory = avatarDirectory.getCanonicalFile();
            File canonicalFile = file.getCanonicalFile();
            String name = canonicalFile.getName();
            return canonicalDirectory.equals(canonicalFile.getParentFile())
                    && name.startsWith(INPUT_PREFIX) && name.endsWith(INPUT_SUFFIX);
        } catch (IOException exception) {
            return false;
        }
    }

    private static void recycleDistinct(Bitmap first, Bitmap second,
                                        Bitmap third, Bitmap fourth) {
        recycleIfDistinct(first, null, null, null);
        recycleIfDistinct(second, first, null, null);
        recycleIfDistinct(third, first, second, null);
        recycleIfDistinct(fourth, first, second, third);
    }

    private static void recycleIfDistinct(Bitmap value, Bitmap a, Bitmap b, Bitmap c) {
        if (value == null || value == a || value == b || value == c || value.isRecycled()) return;
        value.recycle();
    }

    /** Immutable receipt for one successfully written avatar file. */
    public static final class AvatarResult {
        private final String absolutePath;
        private final String fileName;
        private final long byteCount;
        private final int width;
        private final int height;

        private AvatarResult(String absolutePath, String fileName, long byteCount,
                             int width, int height) {
            this.absolutePath = absolutePath;
            this.fileName = fileName;
            this.byteCount = byteCount;
            this.width = width;
            this.height = height;
        }

        public String absolutePath() { return absolutePath; }
        public String fileName() { return fileName; }
        public long byteCount() { return byteCount; }
        public int width() { return width; }
        public int height() { return height; }
    }
}
