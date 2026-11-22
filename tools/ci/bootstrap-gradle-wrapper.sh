#!/usr/bin/env bash
# Fetch the official Gradle 9.6.0 Wrapper JAR only when absent or invalid.
# The downloaded bytes are installed only after matching Gradle's published SHA-256.
set -euo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly WRAPPER_DIR="${ROOT_DIR}/gradle/wrapper"
readonly DESTINATION="${WRAPPER_DIR}/gradle-wrapper.jar"
readonly DISTRIBUTION_URL="https://services.gradle.org/distributions/gradle-9.6.0-wrapper.jar"
readonly EXPECTED_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

if [[ ! -f "${ROOT_DIR}/gradle/wrapper/gradle-wrapper.properties" ]]; then
  echo "ERROR: gradle-wrapper.properties is missing." >&2
  exit 2
fi

grep -Fqx 'distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip' \
  "${ROOT_DIR}/gradle/wrapper/gradle-wrapper.properties" || {
    echo "ERROR: Wrapper distribution URL does not match the pinned Gradle 9.6.0 release." >&2
    exit 2
  }
grep -Fqx 'distributionSha256Sum=bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01' \
  "${ROOT_DIR}/gradle/wrapper/gradle-wrapper.properties" || {
    echo "ERROR: Wrapper distribution checksum is missing or unexpected." >&2
    exit 2
  }

mkdir -p "${WRAPPER_DIR}"
if [[ -f "${DESTINATION}" ]]; then
  actual="$(sha256sum "${DESTINATION}" | awk '{print $1}')"
  if [[ "${actual}" == "${EXPECTED_SHA256}" ]]; then
    echo "Gradle Wrapper JAR is already present and verified (${actual})."
    exit 0
  fi
  echo "WARNING: Existing Wrapper JAR checksum is invalid; it will be replaced only after a verified download." >&2
fi

command -v curl >/dev/null 2>&1 || {
  echo "ERROR: curl is required to retrieve the official Gradle Wrapper JAR." >&2
  exit 3
}
command -v sha256sum >/dev/null 2>&1 || {
  echo "ERROR: sha256sum is required to verify the official Gradle Wrapper JAR." >&2
  exit 3
}

temporary="$(mktemp "${WRAPPER_DIR}/.gradle-wrapper.jar.XXXXXX")"
cleanup() { rm -f "${temporary}"; }
trap cleanup EXIT

curl --fail --location --silent --show-error \
  --proto '=https' --proto-redir '=https' --tlsv1.2 \
  --retry 3 --retry-delay 2 --connect-timeout 20 --max-time 120 \
  "${DISTRIBUTION_URL}" --output "${temporary}"

printf '%s  %s\n' "${EXPECTED_SHA256}" "${temporary}" | sha256sum --check --strict
chmod 0644 "${temporary}"
mv -f "${temporary}" "${DESTINATION}"
trap - EXIT

echo "Installed the official Gradle 9.6.0 Wrapper JAR after SHA-256 verification."
