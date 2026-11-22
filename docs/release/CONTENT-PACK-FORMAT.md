# NEON MAZE — Content Pack Format v1

## Purpose and boundary

The APK is the complete playable product. Campaign logic, Java code, screens,
required fonts, and core resources are packaged in the APK. A content pack is an
optional, versioned data update. It is not a plugin system and must never carry
executable code.

The client downloads only from HTTPS. It verifies the declared archive length
and SHA-256, extracts into an app-private staging directory, validates every
path/type/size, and atomically switches the active version pointer. A failed
update leaves the previous active version intact. On version cleanup, the
installer preserves the version that was actually active before pointer swap,
not an unreferenced higher-numbered directory left by an interrupted activation.
If the process is killed mid-download or extraction, the next startup removes orphaned download/staging
temporaries before trying again. If there is no valid remote pack, the game
continues with the complete bundled content.

## Manifest: UTF-8 key=value

The manifest is limited to 16 KiB and contains one key per line:

```properties
formatVersion=1
contentVersion=3
minAppVersionCode=1
maxAppVersionCode=100
archiveUrl=https://host.example/path/neon-maze-content-3.zip
archiveSizeBytes=123456
archiveSha256=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
```

`formatVersion` must be `1`. `contentVersion` is a positive, monotonically
increasing integer. App version bounds are inclusive. Archive size must be
positive and no greater than 32 MiB. SHA-256 is 64 hexadecimal characters.
All URLs, including redirects, must remain HTTPS. User credentials or access
tokens must never be embedded in the APK or manifest.

## Archive layout

`pack.properties` must be present at the archive root:

```properties
formatVersion=1
contentVersion=3
minAppVersionCode=1
maxAppVersionCode=100
```

Permitted payload extensions: `png`, `webp`, `jpg`, and `jpeg`. The archive's
only text metadata is the generated root `pack.properties`. The current UI
consumer resolves an optional `ui/home-hero.webp` image; other permitted images
may be added when a screen explicitly consumes them. Both the pack builder and
Android installer validate that each image's binary signature matches its
extension. Audio and fonts are bundled/managed by the APK, not remotely
overridden. No properties payload files other than root `pack.properties`,
`.dex`, `.jar`, `.class`, `.so`, `.ttf`, `.ogg`, `.wav`, `.mp3`, `.xml`, `.js`,
`.html`, `.svg`, `.json`, scripts, native libraries, or application code may be
included in a content pack.

Limits: 32 MiB compressed archive, 64 MiB total expanded data, 16 MiB per
file, 128 regular files including `pack.properties`, and at most 256 ZIP entries
(including directory entries). Image dimensions are no greater than
16,384 pixels per side, and no more than 64 megapixels per image. The installer
also performs a bounded sampled decode (at most approximately 4 megapixels,
RGB_565) before activation to reject truncated/corrupt compressed image data.
Absolute paths, drive paths,
backslashes, dot segments, duplicate file entries, and path traversal are
rejected.

## Hosting and build configuration

Host both the manifest and archive at HTTPS URLs. The archive URL may redirect,
but every redirect must stay HTTPS. Configure the public manifest URL at build
time, not as an access token:

- Gradle property: `-PneonMazeContentManifestUrl=https://host/path/manifest.properties`
- Environment variable: `NEON_MAZE_CONTENT_MANIFEST_URL`

When unset, the application performs no remote request and starts from the APK.
A public static endpoint or a public GitHub Release asset can be used. A private
GitHub asset cannot be fetched anonymously; do not solve that by embedding a
Personal Access Token in the app. For a private contest APK, keep the pack
bundled or use an appropriately scoped HTTPS host that does not expose secrets
inside the client.

## Creating a pack

Use the repository tool (Python 3 standard library only):

```bash
python tools/content-pack/create_content_pack.py \
  --source content-pack-assets \
  --version 3 \
  --archive-url https://host.example/path/neon-maze-content-3.zip \
  --archive-output build-content/neon-maze-content-3.zip \
  --manifest-output build-content/content-manifest.properties \
  --min-app-version 1 \
  --max-app-version 100
```

The tool rejects symlinks, unsupported file types, wrong image signatures,
unsafe paths, output paths inside the source directory, invalid URLs, and
oversized input. It creates deterministic ZIP metadata and calculates the
archive hash. The Android installer applies the same image extension and
signature rules and accepts only the generated root metadata file.

## Guarantees and non-guarantees

- HTTPS protects transport; SHA-256 binds the downloaded archive to the manifest.
- This v1 format does not implement a detached public-key signature. It is
  intended for the private competition build and non-executable content only.
  A future public/untrusted distribution must add a pinned signature-verification
  key before relying on third-party content hosting.
- A valid pack does not change level rules, physics, Java code, or game logic.
- No live content endpoint is configured in the current Phase02 source tree.
