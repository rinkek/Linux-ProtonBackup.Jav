#!/usr/bin/env bash
# The removal and upgrade matrix of the release gate (MIGRATION_PLAN.md, section 6.8), run in a
# container that has a real systemd as PID 1 and a real systemd user manager (lingering user), so the
# timer, the service and "a sync is running" are the real thing.
#
#   packaging/test/lifecycle.sh                        Ubuntu 24.04
#   packaging/test/lifecycle.sh debian:11-slim ...     other distributions
#
# The container gets CAP_SYS_ADMIN and no AppArmor profile (systemd needs both inside a container to
# manage its own cgroup); it is not --privileged, /proc/sys stays read-only, and it is removed at the
# end. Full output per distribution: /tmp/protonbackup-lifecycle/<image>.log
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG_DIR="$(dirname "$HERE")"
LOGS=/tmp/protonbackup-lifecycle
mkdir -p "$LOGS"

# LIFECYCLE_DEB=/some/other.deb tests that file instead of the newest build (used to check that the
# tests fail when the maintainer scripts are broken on purpose).
if [ -n "${LIFECYCLE_DEB:-}" ]; then
    PKG_DIR="$(dirname "$LIFECYCLE_DEB")"
    DEB="$(basename "$LIFECYCLE_DEB")"
else
    DEB="$(basename "$(ls -1 "$PKG_DIR"/protonbackup_*_amd64.deb | sort -V | tail -n 1)")"
fi
[ -n "$DEB" ] || { echo "No .deb in $PKG_DIR: run packaging/build-deb.sh first." >&2; exit 1; }

BASES=("$@")
[ ${#BASES[@]} -gt 0 ] || BASES=(ubuntu:24.04)

# An "older build of the same package": the new one with another version number and one extra file,
# which the new version does not have. It stands in for a release that is already installed.
WORK="$(mktemp -d)"
CONTAINER=""
cleanup() { [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" >/dev/null 2>&1; rm -rf "$WORK"; }
trap cleanup EXIT
dpkg-deb -R "$PKG_DIR/$DEB" "$WORK/older"
sed -i 's/^Version: .*/Version: 0.4.4/' "$WORK/older/DEBIAN/control"
echo "only in 0.4.4" > "$WORK/older/usr/lib/protonbackup/lib/app/OBSOLETE-FILE-FROM-0.4.4"
mkdir "$WORK/old"
dpkg-deb -Zxz --root-owner-group -b "$WORK/older" "$WORK/old/protonbackup_0.4.4_amd64.deb" >/dev/null

echo "== $DEB (upgrading from 0.4.4) on ${#BASES[@]} distribution(s) with a real systemd"
failed=0
for base in "${BASES[@]}"; do
    name="$(echo "$base" | tr '/:' '__')"
    log="$LOGS/$name.log"
    image="protonbackup-lifecycle:$name"
    if ! docker build -q --platform linux/amd64 --build-arg BASE="$base" -t "$image" - < "$HERE/Dockerfile.systemd" >"$log.build" 2>&1; then
        failed=1; printf '%-18s  FAILED (the test image could not be built, see %s)\n' "$base" "$log.build"; continue
    fi
    CONTAINER="pb-lifecycle-$$"
    docker rm -f "$CONTAINER" >/dev/null 2>&1
    docker run -d -t --name "$CONTAINER" --platform linux/amd64 --cgroupns=private \
        --cap-add SYS_ADMIN --security-opt apparmor=unconfined \
        --tmpfs /run --tmpfs /run/lock --tmpfs /tmp \
        -v "$PKG_DIR":/pkg:ro -v "$WORK/old":/old:ro -v "$HERE/lifecycle-inner.sh":/lifecycle-inner.sh:ro \
        --entrypoint /bin/bash "$image" -c 'mount -o remount,rw /sys/fs/cgroup && exec /sbin/init' >/dev/null

    # Wait for the system to be up (degraded is fine: some units are masked on purpose).
    up=0
    for _ in $(seq 1 60); do
        state="$(docker exec "$CONTAINER" systemctl is-system-running 2>/dev/null || true)"
        case "$state" in running | degraded) up=1; break ;; esac
        sleep 1
    done
    if [ "$up" = 0 ]; then
        failed=1; printf '%-18s  FAILED (systemd did not start in the container)\n' "$base"
        docker logs "$CONTAINER" 2>&1 | tail -n 15 >"$log"; docker rm -f "$CONTAINER" >/dev/null 2>&1; CONTAINER=""; continue
    fi
    docker exec "$CONTAINER" loginctl enable-linger tester
    docker exec -e DEB="$DEB" "$CONTAINER" bash /lifecycle-inner.sh >"$log" 2>&1
    status=$?
    docker rm -f "$CONTAINER" >/dev/null 2>&1; CONTAINER=""
    if [ "$status" = 0 ]; then
        printf '%-18s  ok     %s checks passed\n' "$base" "$(grep -c '^PASS' "$log")"
    else
        failed=1
        printf '%-18s  FAILED %s failed  (%s)\n' "$base" "$(grep -c '^FAIL' "$log")" "$log"
        grep '^FAIL' "$log" | sed 's/^/    /'
    fi
done
exit "$failed"
