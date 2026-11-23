#!/usr/bin/env bash
# Prepare the two Vazirmatn TTF weights the app bundles under
# app/src/main/assets/fonts/. Run BEFORE Gradle (CI does this automatically).
#
# Source : official release archive rastikerdar/vazirmatn v33.003 (HTTPS)
# Checks : archive SHA-256 (see FONT_SHA_ENFORCE), exactly one matching entry per
#          font, size limits, TrueType/OpenType magic bytes.
#
# Environment:
#   FONT_SHA_ENFORCE=true|false  (default true) false = a SHA mismatch becomes a
#                                warning that prints the real hash; the font
#                                structure checks still apply.
#   NEON_MAZE_VAZIRMATN_ARCHIVE  path to an already-downloaded archive (offline use)
set -euo pipefail

readonly ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly OUT_DIR="${ROOT_DIR}/app/src/main/assets/fonts"
readonly URL="https://github.com/rastikerdar/vazirmatn/releases/download/v33.003/vazirmatn-v33.003.zip"
readonly EXPECTED_SHA256="0a9afd41967e6f57096a56a181a23f81a2b999b62f1f2a4e4b26736580854fdb"
readonly FONTS=("Vazirmatn-Regular.ttf" "Vazirmatn-Bold.ttf")
readonly MAX_ARCHIVE=$((16 * 1024 * 1024))
readonly MAX_FONT=$((8 * 1024 * 1024))
readonly MIN_FONT=40000
ENFORCE="${FONT_SHA_ENFORCE:-true}"

mkdir -p "${OUT_DIR}"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

all_present=true
for f in "${FONTS[@]}"; do [[ -s "${OUT_DIR}/${f}" ]] || all_present=false; done

archive="${tmp}/vazirmatn.zip"
if [[ -n "${NEON_MAZE_VAZIRMATN_ARCHIVE:-}" ]]; then
  cp -- "${NEON_MAZE_VAZIRMATN_ARCHIVE}" "${archive}"
else
  command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 3; }
  curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' \
       --tlsv1.2 --retry 3 --retry-delay 3 --connect-timeout 20 --max-time 180 \
       --max-filesize "${MAX_ARCHIVE}" "${URL}" --output "${archive}"
fi

size="$(stat -c %s "${archive}")"
(( size > 0 && size <= MAX_ARCHIVE )) || { echo "ERROR: archive size ${size} out of range" >&2; exit 4; }
actual="$(sha256sum "${archive}" | awk '{print $1}')"
if [[ "${actual}" == "${EXPECTED_SHA256}" ]]; then
  echo "Vazirmatn archive SHA-256 verified: ${actual}"
elif [[ "${ENFORCE}" == "true" ]]; then
  echo "ERROR: Vazirmatn archive SHA-256 mismatch. expected=${EXPECTED_SHA256} actual=${actual}" >&2
  exit 5
else
  echo "::warning::Vazirmatn archive SHA-256 differs from the pinned value. expected=${EXPECTED_SHA256} actual=${actual} (FONT_SHA_ENFORCE=false, continuing with structure checks)"
fi

command -v unzip >/dev/null || { echo "ERROR: unzip is required" >&2; exit 3; }
for f in "${FONTS[@]}"; do
  mapfile -t matches < <(unzip -Z1 "${archive}" | grep -E "(^|/)fonts/ttf/${f}\$" || true)
  if (( ${#matches[@]} != 1 )); then
    echo "ERROR: expected exactly one fonts/ttf/${f} in archive, found ${#matches[@]}" >&2
    exit 6
  fi
  unzip -p "${archive}" "${matches[0]}" > "${tmp}/${f}"
  fsize="$(stat -c %s "${tmp}/${f}")"
  (( fsize >= MIN_FONT && fsize <= MAX_FONT )) || { echo "ERROR: ${f} size ${fsize} out of range" >&2; exit 7; }
  magic="$(head -c 4 "${tmp}/${f}" | od -An -tx1 | tr -d ' \n')"
  [[ "${magic}" == "00010000" || "${magic}" == "4f54544f" ]] || { echo "ERROR: ${f} is not a TrueType/OpenType font (magic ${magic})" >&2; exit 8; }
  install -m 0644 "${tmp}/${f}" "${OUT_DIR}/${f}"
  echo "Prepared ${f} (${fsize} bytes)"
done
echo "Vazirmatn fonts are ready in ${OUT_DIR}"
