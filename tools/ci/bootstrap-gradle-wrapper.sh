#!/bin/sh
# NEON MAZE — POSIX bootstrap for the official Gradle 9.6.0 Wrapper JAR.
#
# This script is intentionally POSIX sh: gradlew is a POSIX launcher and calls
# this file through `sh`. Keep the implementation within the POSIX shell
# language so the same launcher works on dash, ash, and other POSIX shells.
#
# The downloaded JAR is installed only after its SHA-256 matches the value
# published by Gradle. A failed download or checksum leaves the destination
# untouched and removes the temporary file.

set -eu

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd -P) || {
    printf '%s\n' 'ERROR: Unable to resolve the bootstrap script directory.' >&2
    exit 2
}
ROOT_DIR=$(CDPATH= cd -P "${SCRIPT_DIR}/../.." && pwd -P) || {
    printf '%s\n' 'ERROR: Unable to resolve the NEON MAZE project root.' >&2
    exit 2
}

WRAPPER_DIR="${ROOT_DIR}/gradle/wrapper"
PROPERTIES_FILE="${WRAPPER_DIR}/gradle-wrapper.properties"
DESTINATION="${WRAPPER_DIR}/gradle-wrapper.jar"
DISTRIBUTION_URL='https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar'
EXPECTED_SHA256='497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7'

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit "${2:-2}"
}

sha256_file() {
    checksum_output=''

    if command -v sha256sum >/dev/null 2>&1; then
        checksum_output=$(sha256sum "$1") || return 1
    elif command -v shasum >/dev/null 2>&1; then
        checksum_output=$(shasum -a 256 "$1") || return 1
    else
        return 127
    fi

    # Both sha256sum and shasum print the digest as the first field.
    printf '%s\n' "${checksum_output%% *}"
}

if [ ! -f "$PROPERTIES_FILE" ]; then
    fail 'gradle/wrapper/gradle-wrapper.properties is missing.' 2
fi

if ! grep -F -x 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip' \
    "$PROPERTIES_FILE" >/dev/null 2>&1; then
    fail 'Wrapper distribution URL does not match the pinned Gradle 9.6.0 release.' 2
fi

if ! grep -F -x 'distributionSha256Sum=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01' \
    "$PROPERTIES_FILE" >/dev/null 2>&1; then
    fail 'Wrapper distribution checksum is missing or unexpected.' 2
fi

if ! command -v curl >/dev/null 2>&1; then
    fail 'curl is required to retrieve the official Gradle Wrapper JAR.' 3
fi

if ! command -v sha256sum >/dev/null 2>&1 && ! command -v shasum >/dev/null 2>&1; then
    fail 'sha256sum or shasum is required to verify the official Gradle Wrapper JAR.' 3
fi

if ! command -v mktemp >/dev/null 2>&1; then
    fail 'mktemp is required to create a safe temporary Wrapper JAR.' 3
fi

if ! command -v chmod >/dev/null 2>&1 || ! command -v mv >/dev/null 2>&1; then
    fail 'chmod and mv are required to install the verified Wrapper JAR.' 3
fi

if [ ! -d "$WRAPPER_DIR" ]; then
    mkdir -p "$WRAPPER_DIR" || fail 'Unable to create gradle/wrapper.' 3
fi

if [ -f "$DESTINATION" ]; then
    actual_sha256=$(sha256_file "$DESTINATION") || \
        fail 'Unable to calculate the existing Wrapper JAR SHA-256.' 3

    if [ "$actual_sha256" = "$EXPECTED_SHA256" ]; then
        printf 'Gradle Wrapper JAR is already present and verified (%s).\n' "$actual_sha256"
        exit 0
    fi

    printf '%s\n' \
        'WARNING: Existing Wrapper JAR checksum is invalid; it will be replaced only after a verified download.' >&2
fi

temporary=$(mktemp "${WRAPPER_DIR}/.gradle-wrapper.jar.XXXXXX") || \
    fail 'Unable to create a temporary Wrapper JAR file.' 3

cleanup() {
    if [ -n "${temporary:-}" ] && [ -f "$temporary" ]; then
        rm -f "$temporary"
    fi
}

trap cleanup 0
trap 'exit 1' HUP INT TERM

if ! curl \
    --fail \
    --location \
    --silent \
    --show-error \
    --proto '=https' \
    --proto-redir '=https' \
    --tlsv1.2 \
    --retry 3 \
    --retry-delay 2 \
    --connect-timeout 20 \
    --max-time 120 \
    --max-filesize 10485760 \
    "$DISTRIBUTION_URL" \
    --output "$temporary"; then
    fail 'Official Gradle Wrapper JAR download failed.' 3
fi

downloaded_sha256=$(sha256_file "$temporary") || \
    fail 'Unable to calculate the downloaded Wrapper JAR SHA-256.' 3

if [ "$downloaded_sha256" != "$EXPECTED_SHA256" ]; then
    fail "Gradle Wrapper JAR SHA-256 mismatch. Expected ${EXPECTED_SHA256}; received ${downloaded_sha256}." 3
fi

chmod 0644 "$temporary" || fail 'Unable to set permissions on the verified Wrapper JAR.' 3
mv -f "$temporary" "$DESTINATION" || fail 'Unable to install the verified Wrapper JAR.' 3
temporary=''

printf 'Installed the official Gradle 9.6.0 Wrapper JAR after SHA-256 verification (%s).\n' \
    "$downloaded_sha256"
