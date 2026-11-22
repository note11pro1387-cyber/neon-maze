#!/usr/bin/env python3
"""Create a deterministic, data-only NEON MAZE content pack and properties manifest."""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import stat
import sys
import zipfile
from urllib.parse import urlsplit
from pathlib import Path, PurePosixPath

ALLOWED_EXTENSIONS = {"png", "webp", "jpg", "jpeg"}
MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_EXPANDED_BYTES = 64 * 1024 * 1024
MAX_ENTRY_BYTES = 16 * 1024 * 1024
MAX_FILES = 128
FIXED_ZIP_TIME = (1980, 1, 1, 0, 0, 0)


def fail(message: str) -> "NoReturn":
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)


def validate_relative_path(path: Path) -> str:
    relative = path.as_posix()
    pure = PurePosixPath(relative)
    if pure.is_absolute() or not relative or any(part in ("", ".", "..") for part in pure.parts):
        fail(f"Unsafe content path: {relative}")
    if "\\" in relative or re.match(r"^[A-Za-z]:", relative):
        fail(f"Unsafe content path: {relative}")
    if any(ord(char) < 32 or ord(char) == 127 for char in relative):
        fail(f"Control character in content path: {relative!r}")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,239}", relative):
        fail(f"Content path contains unsupported characters: {relative}")
    return relative


def validate_image_signature(path: Path, extension: str, relative_path: str) -> None:
    with path.open("rb") as stream:
        header = stream.read(12)
    valid = False
    if extension == "png":
        valid = header.startswith(b"\x89PNG\r\n\x1a\n")
    elif extension == "webp":
        valid = len(header) >= 12 and header[:4] == b"RIFF" and header[8:12] == b"WEBP"
    elif extension in {"jpg", "jpeg"}:
        valid = header.startswith(b"\xff\xd8\xff")
    if not valid:
        fail(f"Image extension does not match file signature: {relative_path}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path, help="Folder containing approved data assets")
    parser.add_argument("--version", required=True, type=int, help="Positive integer content version")
    parser.add_argument("--archive-url", required=True, help="HTTPS URL where the archive will be hosted")
    parser.add_argument("--archive-output", required=True, type=Path)
    parser.add_argument("--manifest-output", required=True, type=Path)
    parser.add_argument("--min-app-version", type=int, default=1)
    parser.add_argument("--max-app-version", type=int, default=999999)
    args = parser.parse_args()

    if args.version < 1 or args.min_app_version < 1 or args.max_app_version < args.min_app_version:
        fail("Version bounds are invalid")
    if len(args.archive_url) > 2048 or any(ord(char) < 32 or ord(char) == 127 for char in args.archive_url):
        fail("archive-url is too long or contains control characters")
    try:
        parsed_url = urlsplit(args.archive_url)
        _ = parsed_url.port  # Force validation of malformed port values.
    except ValueError:
        fail("archive-url is malformed")
    if (parsed_url.scheme.lower() != "https" or not parsed_url.hostname
            or parsed_url.username is not None or parsed_url.password is not None
            or parsed_url.fragment or any(char.isspace() for char in args.archive_url)):
        fail("archive-url must be a valid HTTPS URL without credentials, whitespace or fragments")
    if not args.source.is_dir():
        fail(f"Source folder does not exist: {args.source}")
    if args.archive_output.resolve() == args.manifest_output.resolve():
        fail("Archive and manifest outputs must be different files")

    source = args.source.resolve()
    for output_path in (args.archive_output.resolve(), args.manifest_output.resolve()):
        if output_path == source or source in output_path.parents:
            fail("Output files must be outside the source asset folder")
    files: list[tuple[Path, str]] = []
    expanded_size = 0
    for path in sorted(source.rglob("*")):
        if path.is_symlink():
            fail(f"Symbolic links are not permitted: {path}")
        if not path.is_file():
            continue
        relative_path = validate_relative_path(path.relative_to(source))
        extension = path.suffix.lower().lstrip(".")
        if extension not in ALLOWED_EXTENSIONS:
            fail(f"Disallowed file extension .{extension}: {relative_path}")
        validate_image_signature(path, extension, relative_path)
        size = path.stat().st_size
        if size > MAX_ENTRY_BYTES:
            fail(f"File exceeds per-entry size limit: {relative_path}")
        expanded_size += size
        files.append((path, relative_path))
    if not files:
        fail("The content source folder contains no assets")
    if len(files) + 1 > MAX_FILES:
        fail("Too many content files")
    if expanded_size > MAX_EXPANDED_BYTES:
        fail("Expanded content exceeds the total size limit")
    if any(name == "pack.properties" for _, name in files):
        fail("pack.properties is generated by this tool; remove the source copy")

    args.archive_output.parent.mkdir(parents=True, exist_ok=True)
    args.manifest_output.parent.mkdir(parents=True, exist_ok=True)
    temporary_archive = args.archive_output.with_suffix(args.archive_output.suffix + ".tmp")
    if temporary_archive.exists():
        temporary_archive.unlink()

    try:
        with zipfile.ZipFile(temporary_archive, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=9, allowZip64=False) as archive:
            metadata = (
                "formatVersion=1\n"
                f"contentVersion={args.version}\n"
                f"minAppVersionCode={args.min_app_version}\n"
                f"maxAppVersionCode={args.max_app_version}\n"
            ).encode("utf-8")
            metadata_info = zipfile.ZipInfo("pack.properties", FIXED_ZIP_TIME)
            metadata_info.compress_type = zipfile.ZIP_DEFLATED
            metadata_info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(metadata_info, metadata)
            for source_path, relative_path in files:
                info = zipfile.ZipInfo(relative_path, FIXED_ZIP_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                with source_path.open("rb") as source_stream, archive.open(info, "w") as target_stream:
                    while chunk := source_stream.read(1024 * 1024):
                        target_stream.write(chunk)
        size = temporary_archive.stat().st_size
        if size < 1 or size > MAX_ARCHIVE_BYTES:
            fail(f"Archive size is outside the allowed range: {size} bytes")
        digest = sha256_file(temporary_archive)
        os.replace(temporary_archive, args.archive_output)
    finally:
        if temporary_archive.exists():
            temporary_archive.unlink()

    manifest = (
        "formatVersion=1\n"
        f"contentVersion={args.version}\n"
        f"minAppVersionCode={args.min_app_version}\n"
        f"maxAppVersionCode={args.max_app_version}\n"
        f"archiveUrl={args.archive_url}\n"
        f"archiveSizeBytes={size}\n"
        f"archiveSha256={digest}\n"
    )
    manifest_tmp = args.manifest_output.with_suffix(args.manifest_output.suffix + ".tmp")
    manifest_tmp.write_text(manifest, encoding="utf-8", newline="\n")
    os.replace(manifest_tmp, args.manifest_output)

    print(f"Created: {args.archive_output}")
    print(f"Manifest: {args.manifest_output}")
    print(f"Files: {len(files)} (plus pack.properties)")
    print(f"Expanded bytes: {expanded_size}")
    print(f"Archive bytes: {size}")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    main()
