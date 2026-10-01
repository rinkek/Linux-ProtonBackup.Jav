#!/usr/bin/env bash
# Builds packaging/protonbackup_<version>_amd64.deb: the daemon, the UI, their own Java runtime and
# the systemd units, in one package that needs no Java on the target system.
#
# The real work happens in a container with the Temurin JDK (packaging/build-in-container.sh): the
# JDK's native binaries set the glibc floor of the whole package, so the host's JDK is never used.
# Only Docker is needed on the host.
#
#   packaging/build-deb.sh               build, including all tests that run without a display
#   packaging/build-deb.sh --skip-tests  build only
#
# The version comes from the parent pom.xml. Maven's download cache lives in the Docker volume
# "protonbackup-m2" (remove it with: docker volume rm protonbackup-m2), nothing is written to ~/.m2.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
IMAGE=protonbackup-build:temurin21
M2_VOLUME=protonbackup-m2
SKIP_TESTS=0

for argument in "$@"; do
    case "$argument" in
        --skip-tests) SKIP_TESTS=1 ;;
        -h | --help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown option: $argument (try --help)" >&2; exit 2 ;;
    esac
done

command -v docker >/dev/null 2>&1 || { echo "Docker is required: the release is built in the Temurin container." >&2; exit 1; }

# The first <version> at the top level of the parent pom is the project's own.
VERSION="$(sed -n 's/^  <version>\(.*\)<\/version>$/\1/p' "$ROOT/pom.xml" | head -n 1)"
[ -n "$VERSION" ] || { echo "No version found in $ROOT/pom.xml" >&2; exit 1; }
case "$VERSION" in
    *[!0-9.]*) echo "jpackage needs a plain numeric version; '$VERSION' is not." >&2; exit 1 ;;
esac

echo "== protonbackup $VERSION (amd64)"

echo "-- build image $IMAGE (cached after the first time)"
docker build --quiet --platform linux/amd64 -t "$IMAGE" - < "$HERE/Dockerfile.build" >/dev/null

# A new Docker volume belongs to root; the build runs as the calling user, so the files in packaging/
# are the user's, and so must the cache be.
docker volume create "$M2_VOLUME" >/dev/null
docker run --rm --platform linux/amd64 --entrypoint chown -v "$M2_VOLUME":/m2 "$IMAGE" "$(id -u):$(id -g)" /m2

docker run --rm --platform linux/amd64 --entrypoint bash \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp/home -e VERSION="$VERSION" -e SKIP_TESTS="$SKIP_TESTS" \
    -v "$ROOT":/src:ro \
    -v "$HERE":/out \
    -v "$M2_VOLUME":/m2 \
    "$IMAGE" /src/packaging/build-in-container.sh

echo
echo "Ready: $HERE/protonbackup_${VERSION}_amd64.deb"
