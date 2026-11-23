package com.neonmaze.app;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.system.Os;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs optional, data-only NEON MAZE content packs into app-private storage.
 *
 * <p>The APK remains self-sufficient: core levels, code, UI and the required
 * Persian font are bundled in the APK. A remote pack can add versioned image
 * assets only; root {@code pack.properties} is the sole metadata file. It can
 * never replace executable code, audio behavior, fonts, or game rules.
 * If the endpoint is absent or the network is unavailable, the game remains
 * playable from its bundled resources.</p>
 *
 * <p>Manifest format (UTF-8 key=value, one key per line):</p>
 * <pre>
 * formatVersion=1
 * contentVersion=3
 * minAppVersionCode=1
 * maxAppVersionCode=100
 * archiveUrl=https://example.invalid/neon-maze-content-3.zip
 * archiveSizeBytes=123456
 * archiveSha256=64-lowercase-hex-characters
 * </pre>
 *
 * <p>Every archive must contain a root {@code pack.properties} with
 * {@code formatVersion=1} and the matching {@code contentVersion}. Permitted
 * payload extensions are intentionally data-only. DEX, JAR, SO, class, XML,
 * scripts, HTML, SVG and JSON are rejected.</p>
 */
public final class ContentPackInstaller {
    private static final String TAG = "NMContentPack";
    private static final int MANIFEST_MAX_BYTES = 16 * 1024;
    private static final long ARCHIVE_MAX_BYTES = 32L * 1024L * 1024L;
    private static final long EXPANDED_MAX_BYTES = 64L * 1024L * 1024L;
    private static final long ENTRY_MAX_BYTES = 16L * 1024L * 1024L;
    private static final int ENTRY_MAX_COUNT = 128;
    private static final int MAX_IMAGE_DIMENSION = 16_384;
    private static final long MAX_IMAGE_PIXELS = 64_000_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_REDIRECTS = 5;

    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "webp", "jpg", "jpeg", "properties"));

    public enum Phase {
        CHECKING_REMOTE,
        DOWNLOADING,
        VERIFYING,
        EXTRACTING,
        ACTIVATING
    }

    public interface ProgressListener {
        void onProgress(@NonNull Phase phase, int percent,
                        long completedBytes, long totalBytes);
    }

    public enum Status {
        NO_REMOTE_CONFIGURED,
        UP_TO_DATE,
        UPDATED,
        KEPT_NEWER_LOCAL_VERSION,
        READY_BUNDLED_ONLY,
        KEPT_PREVIOUS_AFTER_ERROR
    }

    public static final class Result {
        @NonNull public final Status status;
        public final int activeVersion;
        public final int remoteVersion;
        @Nullable public final String message;

        Result(@NonNull Status status, int activeVersion, int remoteVersion,
               @Nullable String message) {
            this.status = status;
            this.activeVersion = activeVersion;
            this.remoteVersion = remoteVersion;
            this.message = message;
        }
    }

    public static final class InstalledPack {
        public final int contentVersion;
        public final int minAppVersionCode;
        public final int maxAppVersionCode;
        @NonNull public final File directory;
        @NonNull public final String archiveSha256;

        InstalledPack(int contentVersion, int minAppVersionCode, int maxAppVersionCode,
                      @NonNull File directory, @NonNull String archiveSha256) {
            this.contentVersion = contentVersion;
            this.minAppVersionCode = minAppVersionCode;
            this.maxAppVersionCode = maxAppVersionCode;
            this.directory = directory;
            this.archiveSha256 = archiveSha256;
        }
    }

    private static final class Manifest {
        final int contentVersion;
        final int minAppVersionCode;
        final int maxAppVersionCode;
        final String archiveUrl;
        final long archiveSizeBytes;
        final String archiveSha256;

        Manifest(int contentVersion, int minAppVersionCode, int maxAppVersionCode,
                 String archiveUrl, long archiveSizeBytes, String archiveSha256) {
            this.contentVersion = contentVersion;
            this.minAppVersionCode = minAppVersionCode;
            this.maxAppVersionCode = maxAppVersionCode;
            this.archiveUrl = archiveUrl;
            this.archiveSizeBytes = archiveSizeBytes;
            this.archiveSha256 = archiveSha256;
        }
    }

    private final Context appContext;
    private final File contentRoot;
    private final AtomicReference<HttpURLConnection> activeConnection = new AtomicReference<>();

    public ContentPackInstaller(@NonNull Context context) {
        Context application = context.getApplicationContext();
        this.appContext = application != null ? application : context;
        this.contentRoot = new File(appContext.getFilesDir(), "content-packs");
    }

    /** Interrupts an in-flight HTTP operation. Safe to call from the UI thread. */
    public void cancelCurrentRequest() {
        HttpURLConnection connection = activeConnection.getAndSet(null);
        if (connection != null) connection.disconnect();
    }

    @Nullable
    public InstalledPack getActivePack() {
        try {
            return readActivePack();
        } catch (IOException invalid) {
            Log.w(TAG, "Active content pack is unavailable; bundled resources remain active", invalid);
            return null;
        }
    }

    @NonNull
    public Result checkAndInstall(@Nullable String manifestUrl, int appVersionCode,
                                  @NonNull ProgressListener listener) throws IOException {
        cleanupInterruptedArtifacts();
        if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
            InstalledPack active = readActivePackSafely();
            return new Result(Status.NO_REMOTE_CONFIGURED,
                    active == null ? 0 : active.contentVersion, 0, null);
        }
        if (appVersionCode < 1) throw new IllegalArgumentException("appVersionCode must be positive");

        InstalledPack active = readActivePackSafely();
        Manifest manifest;
        try {
            validateHttpsUrl(manifestUrl);
            notify(listener, Phase.CHECKING_REMOTE, 0, 0, MANIFEST_MAX_BYTES);
            byte[] manifestBytes = readUrlBounded(manifestUrl, MANIFEST_MAX_BYTES);
            manifest = parseManifest(manifestBytes);
            if (appVersionCode < manifest.minAppVersionCode
                    || appVersionCode > manifest.maxAppVersionCode) {
                throw new IOException("Content pack is not compatible with this app version");
            }
        } catch (IOException networkOrManifestError) {
            if (active != null) {
                return new Result(Status.KEPT_PREVIOUS_AFTER_ERROR,
                        active.contentVersion, 0, safeMessage(networkOrManifestError));
            }
            // The APK contains all assets needed for the complete core game.
            return new Result(Status.READY_BUNDLED_ONLY, 0, 0,
                    safeMessage(networkOrManifestError));
        }

        if (active != null && active.contentVersion == manifest.contentVersion) {
            if (active.minAppVersionCode != manifest.minAppVersionCode
                    || active.maxAppVersionCode != manifest.maxAppVersionCode
                    || !constantTimeHexEquals(active.archiveSha256, manifest.archiveSha256)) {
                return new Result(Status.KEPT_PREVIOUS_AFTER_ERROR, active.contentVersion,
                        manifest.contentVersion, "An immutable content version was republished with different metadata or bytes");
            }
            return new Result(Status.UP_TO_DATE, active.contentVersion,
                    manifest.contentVersion, null);
        }
        if (active != null && active.contentVersion > manifest.contentVersion) {
            // Never roll a working installation back because an endpoint was stale.
            return new Result(Status.KEPT_NEWER_LOCAL_VERSION, active.contentVersion,
                    manifest.contentVersion, null);
        }

        File download = null;
        File staging = null;
        try {
            File downloadDir = new File(contentRoot, "downloads");
            if (!downloadDir.isDirectory() && !downloadDir.mkdirs()) {
                throw new IOException("Unable to create private download directory");
            }
            download = new File(downloadDir, "content-" + UUID.randomUUID() + ".zip");
            downloadArchive(manifest, download, listener);

            notify(listener, Phase.VERIFYING, 100, manifest.archiveSizeBytes,
                    manifest.archiveSizeBytes);
            String actualHash = sha256(download);
            if (!constantTimeHexEquals(manifest.archiveSha256, actualHash)) {
                throw new IOException("Content archive SHA-256 does not match its manifest");
            }

            File versionsDir = new File(contentRoot, "versions");
            if (!versionsDir.isDirectory() && !versionsDir.mkdirs()) {
                throw new IOException("Unable to create private content directory");
            }
            staging = new File(versionsDir, ".staging-" + UUID.randomUUID());
            if (!staging.mkdir()) throw new IOException("Unable to create content staging directory");

            notify(listener, Phase.EXTRACTING, 0, 0, manifest.archiveSizeBytes);
            extractAndValidate(download, staging, manifest);

            File target = versionDirectory(manifest.contentVersion);
            if (target.exists()) {
                deleteRecursively(target);
                if (target.exists()) throw new IOException("Unable to replace an incomplete previous content version");
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Content installation cancelled");
            notify(listener, Phase.ACTIVATING, 0, 0, 1);
            try {
                Os.rename(staging.getAbsolutePath(), target.getAbsolutePath());
            } catch (android.system.ErrnoException error) {
                throw new IOException("Unable to atomically activate the downloaded content pack", error);
            }
            staging = null;
            writeActivePointer(manifest.contentVersion, manifest.minAppVersionCode,
                    manifest.maxAppVersionCode, actualHash);
            cleanupOldVersions(manifest.contentVersion,
                    active == null ? -1 : active.contentVersion);
            notify(listener, Phase.ACTIVATING, 100, 1, 1);
            return new Result(Status.UPDATED, manifest.contentVersion,
                    manifest.contentVersion, null);
        } catch (IOException error) {
            InstalledPack stillActive = readActivePackSafely();
            if (stillActive != null) {
                return new Result(Status.KEPT_PREVIOUS_AFTER_ERROR,
                        stillActive.contentVersion, manifest.contentVersion, safeMessage(error));
            }
            return new Result(Status.READY_BUNDLED_ONLY, 0,
                    manifest.contentVersion, safeMessage(error));
        } finally {
            cancelCurrentRequest();
            if (download != null && download.exists() && !download.delete()) {
                Log.w(TAG, "Unable to remove temporary content archive");
            }
            if (staging != null && staging.exists()) deleteRecursively(staging);
        }
    }

    private void downloadArchive(Manifest manifest, File output,
                                 ProgressListener listener) throws IOException {
        HttpURLConnection connection = openHttpsConnection(manifest.archiveUrl);
        try {
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Content download returned HTTP " + response);
            }
            long declared = connection.getContentLengthLong();
            if (declared > ARCHIVE_MAX_BYTES || declared > 0L && declared != manifest.archiveSizeBytes) {
                throw new IOException("Content server returned an unexpected archive size");
            }
            long total = manifest.archiveSizeBytes;
            long copied = 0L;
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(output)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Content download cancelled");
                    }
                    copied += read;
                    if (copied > ARCHIVE_MAX_BYTES || copied > total) {
                        throw new IOException("Content archive exceeds its declared size limit");
                    }
                    fileOutput.write(buffer, 0, read);
                    int percent = total == 0L ? 0 : (int) Math.min(100L, copied * 100L / total);
                    notify(listener, Phase.DOWNLOADING, percent, copied, total);
                }
                fileOutput.getFD().sync();
            }
            if (copied != total) throw new IOException("Content archive size differs from its manifest");
        } finally {
            activeConnection.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    private byte[] readUrlBounded(String url, int maxBytes) throws IOException {
        HttpURLConnection connection = openHttpsConnection(url);
        try {
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Content manifest returned HTTP " + response);
            }
            long declared = connection.getContentLengthLong();
            if (declared > maxBytes) throw new IOException("Content manifest is too large");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Content manifest request cancelled");
                    }
                    if (output.size() + read > maxBytes) {
                        throw new IOException("Content manifest exceeded the size limit");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } finally {
            activeConnection.compareAndSet(connection, null);
            connection.disconnect();
        }
    }

    private HttpURLConnection openHttpsConnection(String initialUrl) throws IOException {
        URL current = new URL(initialUrl);
        validateHttpsUrl(current.toString());
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Content request cancelled");
            }
            if (!"https".equalsIgnoreCase(current.getProtocol())) {
                throw new IOException("HTTPS is required for all content requests");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/octet-stream, text/plain;q=0.9");
            activeConnection.set(connection);
            int response;
            try {
                response = connection.getResponseCode();
            } catch (IOException error) {
                activeConnection.compareAndSet(connection, null);
                connection.disconnect();
                throw error;
            }
            if (response == HttpURLConnection.HTTP_MOVED_PERM
                    || response == HttpURLConnection.HTTP_MOVED_TEMP
                    || response == HttpURLConnection.HTTP_SEE_OTHER
                    || response == 307 || response == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                activeConnection.compareAndSet(connection, null);
                if (location == null || redirects == MAX_REDIRECTS) {
                    throw new IOException("Invalid or excessive content redirect");
                }
                current = new URL(current, location);
                validateHttpsUrl(current.toString());
                continue;
            }
            return connection;
        }
        throw new IOException("Too many content redirects");
    }

    private static Manifest parseManifest(byte[] bytes) throws IOException {
        Map<String, String> values = parseStrictProperties(bytes, MANIFEST_MAX_BYTES);
        requireExactKeys(values, "formatVersion", "contentVersion", "minAppVersionCode",
                "maxAppVersionCode", "archiveUrl", "archiveSizeBytes", "archiveSha256");
        requireExactFormatVersion(values, "formatVersion", 1);
        int contentVersion = parseInt(values, "contentVersion", 1, Integer.MAX_VALUE);
        int minApp = parseInt(values, "minAppVersionCode", 1, Integer.MAX_VALUE);
        int maxApp = parseInt(values, "maxAppVersionCode", minApp, Integer.MAX_VALUE);
        long size = parseLong(values, "archiveSizeBytes", 1L, ARCHIVE_MAX_BYTES);
        String archiveUrl = required(values, "archiveUrl");
        validateHttpsUrl(archiveUrl);
        String hash = required(values, "archiveSha256").toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid archive SHA-256 value");
        return new Manifest(contentVersion, minApp, maxApp, archiveUrl, size, hash);
    }

    private static Map<String, String> parseStrictProperties(byte[] bytes, int maxBytes)
            throws IOException {
        if (bytes.length > maxBytes) throw new IOException("Properties document is too large");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Properties document is not valid UTF-8", malformed);
        }
        Map<String, String> values = new HashMap<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator <= 0) throw new IOException("Malformed properties line");
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!key.matches("[A-Za-z][A-Za-z0-9]*")) {
                throw new IOException("Invalid properties key");
            }
            if (value.isEmpty() || values.put(key, value) != null) {
                throw new IOException("Empty or duplicate properties value: " + key);
            }
        }
        return values;
    }

    private static void extractAndValidate(File archive, File staging,
                                           Manifest manifest) throws IOException {
        Set<String> names = new HashSet<>();
        long totalExpanded = 0L;
        int fileCount = 0;
        int entryCount = 0;
        boolean packMetadataFound = false;
        String root = staging.getCanonicalPath() + File.separator;
        byte[] buffer = new byte[8192];

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Content installation cancelled");
                }
                if (++entryCount > ENTRY_MAX_COUNT * 2) {
                    throw new IOException("Too many content archive entries");
                }
                String name = entry.getName();
                validateZipEntryName(name);
                if (entry.isDirectory()) {
                    File directory = new File(staging, name);
                    ensureInside(root, directory);
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Unable to create content directory");
                    }
                    if (!names.add(name)) throw new IOException("Duplicate content archive entry");
                    zip.closeEntry();
                    continue;
                }
                if (!names.add(name)) throw new IOException("Duplicate content archive entry");
                if (++fileCount > ENTRY_MAX_COUNT) throw new IOException("Too many content files");
                String extension = extensionOf(name);
                if (!ALLOWED_EXTENSIONS.contains(extension)) {
                    throw new IOException("Content archive contains a forbidden file type: " + extension);
                }
                if ("properties".equals(extension) && !"pack.properties".equals(name)) {
                    throw new IOException("Only root pack.properties metadata is permitted");
                }
                if ("pack.properties".equals(name)) {
                    if (packMetadataFound) throw new IOException("Duplicate pack metadata");
                    packMetadataFound = true;
                }
                File output = new File(staging, name);
                ensureInside(root, output);
                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Unable to create content parent directory");
                }
                long entryBytes = 0L;
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    if (!"properties".equals(extension)) {
                        byte[] header = new byte[12];
                        int headerBytes = 0;
                        while (headerBytes < header.length) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new IOException("Content installation cancelled");
                            }
                            int read = zip.read(header, headerBytes, header.length - headerBytes);
                            if (read < 0) break;
                            if (read == 0) continue;
                            headerBytes += read;
                            entryBytes += read;
                            totalExpanded += read;
                        }
                        validateImageSignature(extension, header, headerBytes, name);
                        stream.write(header, 0, headerBytes);
                    }
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Content installation cancelled");
                        }
                        entryBytes += read;
                        totalExpanded += read;
                        if (entryBytes > ENTRY_MAX_BYTES || totalExpanded > EXPANDED_MAX_BYTES) {
                            throw new IOException("Expanded content exceeds its safety limit");
                        }
                        stream.write(buffer, 0, read);
                    }
                    if (entryBytes > ENTRY_MAX_BYTES || totalExpanded > EXPANDED_MAX_BYTES) {
                        throw new IOException("Expanded content exceeds its safety limit");
                    }
                    stream.getFD().sync();
                }
                if (!"properties".equals(extension)) {
                    validateImageDimensions(output, name);
                }
                if (entry.getSize() >= 0L && entry.getSize() != entryBytes) {
                    throw new IOException("Content entry size does not match ZIP metadata");
                }
                long compressed = entry.getCompressedSize();
                if (compressed > 0L && entryBytes / Math.max(1L, compressed) > 250L) {
                    throw new IOException("Suspicious content compression ratio");
                }
                zip.closeEntry();
            }
        } catch (SecurityException security) {
            throw new IOException("Unsafe content archive path", security);
        }

        if (!packMetadataFound) throw new IOException("Content pack metadata is missing");
        File metadata = new File(staging, "pack.properties");
        Map<String, String> pack = parseStrictProperties(readFileBounded(metadata, 4096), 4096);
        requireExactKeys(pack, "formatVersion", "contentVersion", "minAppVersionCode", "maxAppVersionCode");
        requireExactFormatVersion(pack, "formatVersion", 1);
        int packVersion = parseInt(pack, "contentVersion", 1, Integer.MAX_VALUE);
        int packMinApp = parseInt(pack, "minAppVersionCode", 1, Integer.MAX_VALUE);
        int packMaxApp = parseInt(pack, "maxAppVersionCode", packMinApp, Integer.MAX_VALUE);
        if (packVersion != manifest.contentVersion
                || packMinApp != manifest.minAppVersionCode
                || packMaxApp != manifest.maxAppVersionCode) {
            throw new IOException("Content pack metadata does not match its manifest");
        }
        if (fileCount < 2) throw new IOException("Content pack has no data assets");
    }

    private static void validateImageSignature(String extension, byte[] header,
                                               int length, String entryName) throws IOException {
        boolean valid;
        switch (extension) {
            case "png":
                valid = length >= 8 && (header[0] & 0xff) == 0x89
                        && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                        && header[4] == 0x0d && header[5] == 0x0a
                        && header[6] == 0x1a && header[7] == 0x0a;
                break;
            case "webp":
                valid = length >= 12 && header[0] == 'R' && header[1] == 'I'
                        && header[2] == 'F' && header[3] == 'F'
                        && header[8] == 'W' && header[9] == 'E'
                        && header[10] == 'B' && header[11] == 'P';
                break;
            case "jpg":
            case "jpeg":
                valid = length >= 3 && (header[0] & 0xff) == 0xff
                        && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
                break;
            default:
                throw new IOException("Unsupported image type: " + extension);
        }
        if (!valid) throw new IOException("Image extension does not match file signature: " + entryName);
    }

    private static void validateImageDimensions(File imageFile, String entryName) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        bounds.inScaled = false;
        try {
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
        } catch (RuntimeException invalidImage) {
            throw new IOException("Content image header cannot be decoded: " + entryName, invalidImage);
        }
        long pixels = (long) bounds.outWidth * (long) bounds.outHeight;
        if (bounds.outWidth < 1 || bounds.outHeight < 1
                || bounds.outWidth > MAX_IMAGE_DIMENSION
                || bounds.outHeight > MAX_IMAGE_DIMENSION
                || pixels > MAX_IMAGE_PIXELS) {
            throw new IOException("Content image has invalid or excessive dimensions: " + entryName);
        }

        // Bounds-only decoding does not prove that the compressed pixel stream
        // is intact. Decode a bounded thumbnail on the installer worker so a
        // truncated/corrupt image cannot become the active version. Keep the
        // returned Bitmap below ~4 MP (RGB_565) and release it immediately.
        int sampleSize = 1;
        while (true) {
            int decodedWidth = Math.max(1, bounds.outWidth / sampleSize);
            int decodedHeight = Math.max(1, bounds.outHeight / sampleSize);
            if (decodedWidth <= 4096 && decodedHeight <= 4096
                    && (long) decodedWidth * decodedHeight <= 4_000_000L) break;
            sampleSize *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inJustDecodeBounds = false;
        decode.inScaled = false;
        decode.inSampleSize = sampleSize;
        decode.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
        final android.graphics.Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), decode);
        } catch (RuntimeException invalidImage) {
            throw new IOException("Content image cannot be fully decoded: " + entryName, invalidImage);
        }
        if (bitmap == null) {
            throw new IOException("Content image cannot be fully decoded: " + entryName);
        }
        bitmap.recycle();
    }

    private static byte[] readFileBounded(File file, int maxBytes) throws IOException {
        if (!file.isFile() || file.length() > maxBytes) throw new IOException("Invalid pack metadata size");
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maxBytes) throw new IOException("Pack metadata is too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }


    @Nullable
    private InstalledPack readActivePackSafely() {
        try {
            return readActivePack();
        } catch (IOException invalid) {
            Log.w(TAG, "Ignoring an invalid active content pointer; bundled resources remain available", invalid);
            return null;
        }
    }

    private InstalledPack readActivePack() throws IOException {
        File pointer = new File(contentRoot, "active.properties");
        if (!pointer.isFile()) return null;
        Map<String, String> values = parseStrictProperties(readFileBounded(pointer, 4096), 4096);
        requireExactKeys(values, "formatVersion", "contentVersion", "minAppVersionCode",
                "maxAppVersionCode", "archiveSha256");
        requireExactFormatVersion(values, "formatVersion", 1);
        int version = parseInt(values, "contentVersion", 1, Integer.MAX_VALUE);
        String hash = required(values, "archiveSha256").toLowerCase(Locale.ROOT);
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Invalid active content hash");
        File directory = versionDirectory(version);
        File metadata = new File(directory, "pack.properties");
        Map<String, String> pack = parseStrictProperties(readFileBounded(metadata, 4096), 4096);
        requireExactKeys(pack, "formatVersion", "contentVersion", "minAppVersionCode", "maxAppVersionCode");
        requireExactFormatVersion(pack, "formatVersion", 1);
        if (parseInt(pack, "contentVersion", 1, Integer.MAX_VALUE) != version) {
            throw new IOException("Active content directory does not match its pointer");
        }
        int minApp = parseInt(values, "minAppVersionCode", 1, Integer.MAX_VALUE);
        int maxApp = parseInt(values, "maxAppVersionCode", minApp, Integer.MAX_VALUE);
        int currentApp = BuildConfig.VERSION_CODE;
        if (currentApp < minApp || currentApp > maxApp) {
            throw new IOException("Installed content pack is incompatible with this app version");
        }
        if (parseInt(pack, "minAppVersionCode", 1, Integer.MAX_VALUE) != minApp
                || parseInt(pack, "maxAppVersionCode", minApp, Integer.MAX_VALUE) != maxApp) {
            throw new IOException("Active content compatibility metadata is inconsistent");
        }
        return new InstalledPack(version, minApp, maxApp, directory, hash);
    }

    private void writeActivePointer(int contentVersion, int minAppVersionCode,
                                    int maxAppVersionCode, String archiveSha256) throws IOException {
        if (!contentRoot.isDirectory() && !contentRoot.mkdirs()) {
            throw new IOException("Unable to create content root");
        }
        File temporary = new File(contentRoot, "active-" + UUID.randomUUID() + ".tmp");
        File active = new File(contentRoot, "active.properties");
        byte[] bytes = ("formatVersion=1\ncontentVersion=" + contentVersion
                + "\nminAppVersionCode=" + minAppVersionCode
                + "\nmaxAppVersionCode=" + maxAppVersionCode
                + "\narchiveSha256=" + archiveSha256 + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(bytes);
            output.getFD().sync();
        }
        try {
            // POSIX rename within the same private filesystem is atomic. The old
            // active pointer remains valid if the replacement cannot complete.
            Os.rename(temporary.getAbsolutePath(), active.getAbsolutePath());
        } catch (Exception error) {
            if (temporary.exists()) temporary.delete();
            throw new IOException("Unable to atomically activate content pack", error);
        }
    }

    /** Removes download/staging leftovers from a process death before a new update attempt. */
    private void cleanupInterruptedArtifacts() {
        File downloads = new File(contentRoot, "downloads");
        File[] downloadFiles = downloads.listFiles();
        if (downloadFiles != null) {
            for (File file : downloadFiles) deleteRecursively(file);
        }

        File versions = new File(contentRoot, "versions");
        File[] versionEntries = versions.listFiles();
        if (versionEntries != null) {
            for (File entry : versionEntries) {
                if (entry.getName().startsWith(".staging-")) deleteRecursively(entry);
            }
        }

        File[] rootEntries = contentRoot.listFiles();
        if (rootEntries != null) {
            for (File entry : rootEntries) {
                String name = entry.getName();
                if (entry.isFile() && name.startsWith("active-") && name.endsWith(".tmp")) {
                    deleteRecursively(entry);
                }
            }
        }
    }

    private void cleanupOldVersions(int activeVersion, int previousActiveVersion) {
        File versions = new File(contentRoot, "versions");
        File[] directories = versions.listFiles(File::isDirectory);
        if (directories == null) return;
        for (File directory : directories) {
            int version = versionFromDirectory(directory);
            // Preserve the version that was actually active before the atomic
            // pointer swap. Do not keep an unreferenced higher-numbered orphan:
            // it may be the remnant of an activation interrupted before commit.
            if (version == activeVersion || version == previousActiveVersion) continue;
            deleteRecursively(directory);
        }
    }

    private File versionDirectory(int version) {
        return new File(new File(contentRoot, "versions"), "v" + version);
    }

    private static int versionFromDirectory(File directory) {
        String name = directory.getName();
        if (!name.matches("v[1-9][0-9]*")) return -1;
        try { return Integer.parseInt(name.substring(1)); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static void validateZipEntryName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.length() > 240
                || name.startsWith("/") || name.startsWith("\\")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0
                || name.matches("^[A-Za-z]:.*")) {
            throw new IOException("Unsafe content archive entry name");
        }
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean finalDirectorySeparator = i == parts.length - 1
                    && part.isEmpty() && name.endsWith("/");
            if (part.equals("..") || part.equals(".") || part.isEmpty() && !finalDirectorySeparator) {
                throw new IOException("Unsafe content archive path segment");
            }
        }
    }

    private static void ensureInside(String rootWithSeparator, File candidate) throws IOException {
        String canonical = candidate.getCanonicalPath();
        if (!canonical.startsWith(rootWithSeparator)) {
            throw new IOException("Content archive path escapes its staging directory");
        }
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void validateHttpsUrl(String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 2048
                || value.chars().anyMatch(ch -> Character.isISOControl(ch) || Character.isWhitespace(ch))) {
            throw new IOException("Content URL is empty or contains invalid characters");
        }
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost() == null
                || url.getHost().isEmpty() || url.getUserInfo() != null
                || url.getRef() != null) {
            throw new IOException("Content URLs must use HTTPS without user information or fragments");
        }
    }

    private static void requireExactKeys(Map<String, String> values, String... expected)
            throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList(expected));
        if (values.size() != allowed.size() || !values.keySet().equals(allowed)) {
            throw new IOException("Properties document contains missing or unknown keys");
        }
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing manifest key: " + key);
        return value.trim();
    }

    private static int parseInt(Map<String, String> values, String key, int min, int max)
            throws IOException {
        try {
            int value = Integer.parseInt(required(values, key));
            if (value < min || value > max) throw new IOException("Manifest integer is out of range: " + key);
            return value;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid manifest integer: " + key, invalid);
        }
    }

    private static long parseLong(Map<String, String> values, String key, long min, long max)
            throws IOException {
        try {
            long value = Long.parseLong(required(values, key));
            if (value < min || value > max) throw new IOException("Manifest number is out of range: " + key);
            return value;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid manifest number: " + key, invalid);
        }
    }

    private static void requireExactFormatVersion(Map<String, String> values,
                                                  String key, int expected) throws IOException {
        if (parseInt(values, key, 1, 100) != expected) {
            throw new IOException("Unsupported content format version");
        }
    }

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }

    private static boolean constantTimeHexEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static void notify(ProgressListener listener, Phase phase, int percent,
                               long completed, long total) {
        listener.onProgress(phase, Math.max(0, Math.min(100, percent)), completed, total);
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return "Content update unavailable";
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to remove old content path: " + file.getName());
        }
    }
}
