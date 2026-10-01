#!/usr/bin/env bash
# The release gate of phase 12 in one command, about half an hour:
#
#   1. mvn verify on this machine   all tests, including the ones that need a display
#   2. build-deb.sh                 the .deb, built in the Temurin container, with the tests that run there
#   3. test/distros.sh              the real .deb on 11 distributions
#   4. test/lifecycle.sh            install, upgrade, remove and purge with a real systemd, on 8 of them
#
# Needs a JDK 21 with Maven and Docker. A failing step is reported at the end; when the .deb cannot be
# built, the steps that test it are skipped.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
results=()
failed=0

run() {
    local name="$1"; shift
    printf '\n################ %s\n' "$name"
    if "$@"; then results+=("ok       $name"); else results+=("FAILED   $name"); failed=1; return 1; fi
}

run "mvn verify (this machine, with a display)" mvn -B -q -f "$ROOT/pom.xml" verify
if run "build the .deb (Temurin container)" "$HERE/build-deb.sh"; then
    run "the .deb on 11 distributions" "$HERE/test/distros.sh"
    run "install, upgrade, remove, purge with a real systemd" \
        "$HERE/test/lifecycle.sh" ubuntu:24.04 ubuntu:22.04 ubuntu:20.04 ubuntu:18.04 ubuntu:26.04 debian:11-slim debian:12-slim debian:13-slim
else
    results+=("skipped  the tests of the .deb: it was not built")
fi

printf '\n################ release gate\n'
printf '%s\n' "${results[@]}"
exit "$failed"
