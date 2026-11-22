package com.neonmaze.app;

import android.content.res.AssetManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolves optional data assets from the active, verified content pack first,
 * then from the APK's {@code assets/content/} directory.
 *
 * <p>Only paths and extensions accepted by ContentPackInstaller are resolved.
 * Callers own and must close every returned stream. This class never performs
 * network access and never loads executable code.</p>
 */
public final class ContentAssetResolver {
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "png", "webp", "jpg", "jpeg"));

    private final AssetManager packagedAssets;
    @Nullable private final File installedPackDirectory;

    public ContentAssetResolver(@NonNull AssetManager packagedAssets,
                                @Nullable ContentPackInstaller.InstalledPack activePack) {
        this.packagedAssets = packagedAssets;
        this.installedPackDirectory = activePack == null ? null : activePack.directory;
    }

    /** Opens a required logical path; installed content takes precedence over APK fallback. */
    @NonNull
    public InputStream open(@NonNull String logicalPath) throws IOException {
        String safePath = validateLogicalPath(logicalPath);
        File installed = findInstalledFile(safePath);
        if (installed != null) return new FileInputStream(installed);
        return packagedAssets.open("content/" + safePath, AssetManager.ACCESS_STREAMING);
    }

    /** Returns null rather than throwing when an optional asset is not supplied. */
    @Nullable
    public InputStream openOptional(@NonNull String logicalPath) {
        try {
            return open(logicalPath);
        } catch (IOException | IllegalArgumentException unavailable) {
            return null;
        }
    }

    public boolean exists(@NonNull String logicalPath) {
        String safePath;
        try {
            safePath = validateLogicalPath(logicalPath);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (findInstalledFile(safePath) != null) return true;
        try (InputStream ignored = packagedAssets.open(
                "content/" + safePath, AssetManager.ACCESS_STREAMING)) {
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    @Nullable
    public File findInstalledFile(@NonNull String logicalPath) {
        if (installedPackDirectory == null) return null;
        final String safePath;
        try {
            safePath = validateLogicalPath(logicalPath);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        try {
            String root = installedPackDirectory.getCanonicalPath() + File.separator;
            File candidate = new File(installedPackDirectory, safePath);
            String canonical = candidate.getCanonicalPath();
            if (!canonical.startsWith(root) || !candidate.isFile()) return null;
            return candidate;
        } catch (IOException invalidPath) {
            return null;
        }
    }

    @NonNull
    private static String validateLogicalPath(@NonNull String path) {
        if (path.isEmpty() || path.length() > 240 || path.startsWith("/")
                || path.startsWith("\\") || path.contains("\\") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid content asset path");
        }
        String[] parts = path.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Invalid content asset path segment");
            }
        }
        int dot = path.lastIndexOf('.');
        String extension = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported content asset type");
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw new IllegalArgumentException("Invalid content asset path character");
            }
        }
        return path;
    }
}
