#!/usr/bin/env bash
# Phase 13: the C# daemon and the Java daemon (from the built .deb) run the same scenarios against one fake
# Proton CLI; exit codes, output, CLI calls, systemctl calls, database rows and the remote tree are compared.
# Needs: the .NET SDK (the reference is copied and built in a scratch folder; ../../ProtonDrive.Net stays untouched),
# packaging/protonbackup_*_amd64.deb, python3. Everything happens under $COMPARE_WORK (default /tmp/protonbackup-compare).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
export COMPARE_WORK="${COMPARE_WORK:-/tmp/protonbackup-compare}"
export DOTNET_ROOT="${DOTNET_ROOT:-$HOME/.dotnet}"
DOTNET="${DOTNET:-$DOTNET_ROOT/dotnet}"
mkdir -p "$COMPARE_WORK"
rm -rf "$COMPARE_WORK/cs" "$COMPARE_WORK/csbin" "$COMPARE_WORK/java"
mkdir -p "$COMPARE_WORK/cs" "$COMPARE_WORK/java"
(cd "$ROOT/../ProtonDrive.Net" && tar --exclude=bin --exclude=obj -cf - ProtonBackup.Core ProtonBackup.Daemon Directory.Build.props global.json) | tar -C "$COMPARE_WORK/cs" -xf -
(cd "$COMPARE_WORK/cs" && "$DOTNET" build ProtonBackup.Daemon -c Release -o "$COMPARE_WORK/csbin" 2>&1 | tail -n 3)
dpkg-deb -x "$(ls -1 "$ROOT"/packaging/protonbackup_*_amd64.deb | sort -V | tail -n 1)" "$COMPARE_WORK/java"
for scenario in sc1 sc2 sc3; do
    echo "######## $scenario"
    python3 "$HERE/compare.py" "$HERE/$scenario.py" | cut -c1-220
done
