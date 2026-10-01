#!/usr/bin/env bash
# The distribution matrix of the release gate: installs the real .deb in a container of each
# distribution (or, where there is no dpkg, unpacks it) and runs distro-inner.sh there.
#
#   packaging/test/distros.sh                       the default list
#   packaging/test/distros.sh ubuntu:24.04 ...      only these images
#
# Full output per distribution: /tmp/protonbackup-distros/<image>.log. Screenshots of the window,
# one per distribution, end up in docs/package-shots/.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG_DIR="$(dirname "$HERE")"
ROOT="$(dirname "$PKG_DIR")"
SHOTS="$ROOT/docs/package-shots"
LOGS=/tmp/protonbackup-distros
mkdir -p "$SHOTS" "$LOGS"

DEB="$(basename "$(ls -1 "$PKG_DIR"/protonbackup_*_amd64.deb | sort -V | tail -n 1)")"
[ -n "$DEB" ] || { echo "No .deb in $PKG_DIR: run packaging/build-deb.sh first." >&2; exit 1; }

IMAGES=("$@")
if [ ${#IMAGES[@]} -eq 0 ]; then
    IMAGES=(ubuntu:18.04 ubuntu:20.04 ubuntu:22.04 ubuntu:24.04 ubuntu:26.04 debian:11-slim debian:12-slim debian:13-slim rockylinux:8 rockylinux:9 fedora:latest)
fi

echo "== $DEB on ${#IMAGES[@]} distributions"
failed=0
for image in "${IMAGES[@]}"; do
    log="$LOGS/$(echo "$image" | tr '/:' '__').log"
    if docker run --rm --platform linux/amd64 -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" -e DEB="$DEB" \
            -v "$PKG_DIR":/pkg:ro -v "$SHOTS":/out -v "$HERE/distro-inner.sh":/inner.sh:ro \
            "$image" bash /inner.sh >"$log" 2>&1; then
        printf '%-18s  ok     %s\n' "$image" "$(grep -c '^PASS' "$log") checks passed"
    else
        failed=1
        printf '%-18s  FAILED %s  (%s)\n' "$image" "$(grep -c '^FAIL' "$log") failed" "$log"
        grep '^FAIL' "$log" | sed 's/^/    /'
    fi
done
exit "$failed"
