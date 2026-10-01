#!/usr/bin/env bash
# Runs INSIDE the build container (packaging/Dockerfile.build); started by build-deb.sh, not by hand.
#
#   1. Maven build of the daemon and the UI (with their tests, unless SKIP_TESTS=1)
#   2. one input folder with every runtime jar; the JavaFX and SQLite native libraries are taken out
#      of their jars and put in input/natives
#   3. jlink runtime, jpackage app-image with two launchers sharing it
#   4. the daemon's launcher is made unable to see JavaFX or the UI
#   5. the staging tree of the package, glibc and layout checks, dpkg-deb
#
# Mounts: /src (the project, read-only), /out (packaging/, where the .deb goes), /m2 (Maven cache).
# Environment: VERSION, SKIP_TESTS.
set -euo pipefail

PACKAGE=protonbackup
ARCH=amd64
SRC=/src
OUT=/out
M2=/m2
WORK=/tmp/work

# The package promises to start on glibc 2.17 and newer (RHEL/CentOS 7, Debian 8, Ubuntu 14.04 and up).
# The build fails if anything in it needs more. Measured today: Temurin 2.15, JavaFX 2.17, SQLite 2.3.
GLIBC_LIMIT=2.17

# jlink. jdeps (below) proves that nothing the code references statically is missing from this list.
# Not found by any analysis, but needed: java.logging (JavaFX and the JDK log through it), java.xml
# (JavaFX's CSS and FXML code), java.naming (the HTTPS host-name check of the CLI download) and
# jdk.crypto.ec (the elliptic curves of TLS). jdk.jfr is only named by JavaFX's optional pulse
# logging, which this app never switches on; it is in the list so the check can stay strict.
MODULES=java.base,java.logging,java.sql,java.net.http,java.desktop,java.xml,java.naming,jdk.unsupported,jdk.crypto.ec,jdk.jfr

VERSION="${VERSION:?VERSION is not set}"
SKIP_TESTS="${SKIP_TESTS:-0}"

INPUT="$WORK/input"
RUNTIME="$WORK/runtime"
DIST="$WORK/dist"
STAGE="$WORK/stage/${PACKAGE}-${VERSION}"
DEB="$OUT/${PACKAGE}_${VERSION}_${ARCH}.deb"

export HOME="${HOME:-/tmp/home}"
mkdir -p "$HOME"
umask 022

step() { printf '\n== %s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

step "toolchain"
java -version 2>&1 | sed -n '1,2p'
ldd --version 2>&1 | sed -n 1p
dpkg-deb --version | sed -n 1p

# ---------------------------------------------------------------- 1. Maven

step "copying the project (without build output)"
rm -rf "$WORK"
mkdir -p "$WORK/project" "$INPUT/natives" "$STAGE"
tar -C "$SRC" --exclude=target --exclude=./probe --exclude=./docs --exclude='*.deb' -cf - . | tar -C "$WORK/project" -xf -

step "Maven build (tests: $([ "$SKIP_TESTS" = 1 ] && echo skipped || echo run))"
cd "$WORK/project"
MVN=(mvn -B -ntp -Dmaven.repo.local="$M2")
test_flags=()
[ "$SKIP_TESTS" = 1 ] && test_flags+=(-DskipTests)
log="$WORK/maven.log"
# copy-dependencies in the same invocation: the module jars of the reactor are only packaged by then.
if ! "${MVN[@]}" -pl protonbackup-daemon,protonbackup-ui -am "${test_flags[@]}" \
        verify org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies \
        -DincludeScope=runtime -DoutputDirectory="$WORK/libs" >"$log" 2>&1; then
    tail -n 80 "$log" >&2
    die "the Maven build failed (full log in the container: $log)"
fi
grep -E 'Tests run:.*Fail.*Skipped: [0-9]+$|BUILD|Reactor Summary' "$log" | sed 's/^\[INFO\] //' | tail -n 6

# ---------------------------------------------------------------- 2. the input folder

step "assembling the input folder"
cp "$WORK"/libs/*.jar "$INPUT/"
for module in daemon ui; do
    cp "$WORK/project/protonbackup-$module/target/protonbackup-$module-$VERSION.jar" "$INPUT/"
done
core_jars=$(ls "$INPUT"/protonbackup-core-*.jar | wc -l)
[ "$core_jars" = 1 ] || die "expected one protonbackup-core jar in the input folder, found $core_jars"

# Maven also delivers a stub (about 300 bytes) next to every JavaFX platform jar; only the -linux jars count.
for stub in "$INPUT"/javafx-*.jar; do
    case "$stub" in *-linux.jar) ;; *) rm -f "$stub" ;; esac
done

# JavaFX keeps its native libraries inside the platform jars and unpacks them into ~/.openjfx at run
# time, a folder in the user's home that removal would leave behind. Taking them out of the jars and
# loading them through java.library.path means nothing is ever unpacked.
# Not `unzip -l | grep -q`: under pipefail grep's early exit makes unzip die of SIGPIPE and the test fails.
has_natives() { [ "$(unzip -l "$1" | grep -c '\.so$' || true)" != 0 ]; }
shopt -s nullglob
fx_jars=("$INPUT"/javafx-*-linux.jar)
[ "${#fx_jars[@]}" -ge 3 ] || die "expected at least 3 javafx-*-linux.jar files (base, graphics, controls), found ${#fx_jars[@]}"
for jar in "${fx_jars[@]}"; do
    # Only javafx-graphics has natives; unzip and zip both fail on a jar without any.
    if has_natives "$jar"; then
        unzip -q -o -j "$jar" '*.so' -d "$INPUT/natives"
        zip -q -d "$jar" '*.so'
    fi
    ! has_natives "$jar" || die "native libraries are still inside $(basename "$jar")"
done
# SQLite's driver extracts its library into /tmp at run time, and a daemon that ends with halt()
# (which it does, to carry its exit code through SIGTERM) never deletes it again: one file per run.
# Handing it the library through org.sqlite.lib.path means nothing is extracted either.
sqlite_jar=$(ls "$INPUT"/sqlite-jdbc-*.jar)
unzip -q -o -j "$sqlite_jar" 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so' -d "$INPUT/natives"
# The jar carries the library for every platform (10 of its 12 MB). This package is for Linux on
# x86-64 only; that one stays inside as the fallback should java.library.path ever be wrong.
unzip -Z1 "$sqlite_jar" | grep -E '^org/sqlite/native/.+[^/]$' | grep -v '^org/sqlite/native/Linux/x86_64/' | zip -q -d "$sqlite_jar" -@ >/dev/null
sqlite_entries=$(unzip -Z1 "$sqlite_jar")   # (not piped into grep -q: SIGPIPE under pipefail, see has_natives)
grep -q '^org/sqlite/native/Linux/x86_64/libsqlitejdbc.so$' <<<"$sqlite_entries" || die "the SQLite jar lost its Linux x86-64 library"
shopt -u nullglob
echo "natives: $(ls "$INPUT/natives" | tr '\n' ' ')"
echo "jars:    $(ls "$INPUT" | grep -c '\.jar$')"

# ---------------------------------------------------------------- 3. jlink and jpackage

step "jdeps: which JDK modules does the code need?"
# All jars are inputs, so none is given again as class path (that makes jdeps warn about split packages).
# The answer is the last line of the output; jdeps puts its warnings in front of it.
jdeps --print-module-deps --ignore-missing-deps --multi-release 21 "$INPUT"/*.jar >"$WORK/jdeps.txt" 2>"$WORK/jdeps.err" \
    || { cat "$WORK/jdeps.err" >&2; die "jdeps failed"; }
needed=$(tail -n 1 "$WORK/jdeps.txt" | tr ',' '\n' | sort -u)
for module in $needed; do
    echo "$module" | grep -q -E '^(java|jdk)\.[a-z.]+$' || die "unexpected jdeps output: $(cat "$WORK/jdeps.txt" | head -n 5)"
done
echo "needed:   $(echo "$needed" | tr '\n' ' ')"
echo "in jlink: $(echo "$MODULES" | tr ',' ' ')"
missing=0
for module in $needed; do
    case ",$MODULES," in
        *",$module,"*) ;;
        *) echo "ERROR: jdeps says the code needs the module $module, which is not in the jlink list" >&2; missing=1 ;;
    esac
done
[ "$missing" = 0 ] || die "add the module(s) above to MODULES in packaging/build-in-container.sh"

step "jlink runtime"
jlink --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
    --compress zip-6 --output "$RUNTIME"
du -sh "$RUNTIME" | cut -f1 | sed 's/^/runtime: /'

step "jpackage app-image (two launchers, one runtime)"
cat > "$WORK/ui-launcher.properties" <<EOF
main-class=protonbackup.ui.Main
main-jar=protonbackup-ui-$VERSION.jar
description=Proton Drive backup, window
EOF
# $APPDIR is jpackage's own placeholder, resolved by the launcher at start-up: it must stay literal.
# The -XX/-Xss options keep a short-lived daemon small (measured in docs/SPIKES.md); UsePerfData off
# stops the JVM from writing a monitoring file into /tmp/hsperfdata_<user> on every start.
jpackage --type app-image --name "$PACKAGE" --app-version "$VERSION" \
    --vendor "Rinke Kleijer" --description "One-way backup to Proton Drive" \
    --input "$INPUT" --main-jar "protonbackup-daemon-$VERSION.jar" --main-class protonbackup.daemon.Main \
    --runtime-image "$RUNTIME" --dest "$DIST" \
    --add-launcher "protonbackup-ui=$WORK/ui-launcher.properties" \
    --java-options '-Djava.library.path=$APPDIR/natives' \
    --java-options '-Dorg.sqlite.lib.path=$APPDIR/natives' \
    --java-options '-XX:+UseSerialGC' \
    --java-options '-XX:-UsePerfData' \
    --java-options '-Xss1m'
APP="$DIST/$PACKAGE"
[ -x "$APP/bin/protonbackup" ] && [ -x "$APP/bin/protonbackup-ui" ] || die "jpackage did not create both launchers"

# jpackage's default icons: not ours, and nothing uses them (the desktop entry has its own icon).
rm -f "$APP/lib/protonbackup.png" "$APP/lib/protonbackup-ui.png"

# jpackage puts every jar of the input folder on the class path of every launcher. The daemon is
# started by a timer every few minutes and must never load JavaFX or the window code, so its class
# path is cut down. (The UI keeps the full list.)
daemon_cfg="$APP/lib/app/protonbackup.cfg"
ui_cfg="$APP/lib/app/protonbackup-ui.cfg"
sed -i -e '/^app\.classpath=.*javafx-/d' -e '/^app\.classpath=.*protonbackup-ui-/d' "$daemon_cfg"
grep -q 'javafx' "$daemon_cfg" && die "the daemon's launcher configuration still mentions JavaFX"
grep -q 'protonbackup-ui-' "$daemon_cfg" && die "the daemon's launcher configuration still mentions the UI"
grep -q 'javafx-controls' "$ui_cfg" || die "the UI's launcher configuration lost JavaFX"
grep -q '^app.mainclass=protonbackup.daemon.Main$' "$daemon_cfg" || die "wrong main class for the daemon"
grep -q '^app.mainclass=protonbackup.ui.Main$' "$ui_cfg" || die "wrong main class for the UI"
echo "--- $(basename "$daemon_cfg")"; cat "$daemon_cfg"

# ---------------------------------------------------------------- 4. staging tree

step "staging tree"
install -d -m 755 "$STAGE/DEBIAN" "$STAGE/usr/bin" "$STAGE/usr/lib/systemd/user" \
    "$STAGE/usr/share/applications" "$STAGE/usr/share/doc/$PACKAGE" \
    "$STAGE/usr/share/icons/hicolor/256x256/apps"
cp -a "$APP" "$STAGE/usr/lib/protonbackup"

# /usr/bin/protonbackup{,-ui} are two copies of the wrapper, not symlinks: the name it is called by
# selects the launcher, and it guarantees a UTF-8 locale before the JVM starts (MIGRATION_PLAN.md 6.3).
install -m 755 "$SRC/packaging/launcher.sh" "$STAGE/usr/bin/protonbackup"
install -m 755 "$SRC/packaging/launcher.sh" "$STAGE/usr/bin/protonbackup-ui"

for unit in protonbackup-sync.service 'protonbackup-sync@.service' protonbackup-sync.timer; do
    install -m 644 "$SRC/packaging/templates/$unit" "$STAGE/usr/lib/systemd/user/$unit"
done
install -m 644 "$SRC/packaging/templates/protonbackup.desktop" "$STAGE/usr/share/applications/protonbackup.desktop"
install -m 644 "$SRC/packaging/protonbackup.png" "$STAGE/usr/share/icons/hicolor/256x256/apps/protonbackup.png"
install -m 644 "$SRC/packaging/templates/copyright" "$STAGE/usr/share/doc/$PACKAGE/copyright"

# Directories 755; files keep an executable bit only if they had one (owner and group are root in the .deb).
find "$STAGE/usr/lib/protonbackup" -type d -exec chmod 755 {} +
find "$STAGE/usr/lib/protonbackup" -type f -exec sh -c 'for f; do if [ -x "$f" ]; then chmod 755 "$f"; else chmod 644 "$f"; fi; done' _ {} +

# Maintainer scripts: each script plus the shared helpers pasted in at its "@COMMON@" line.
for script in postinst prerm postrm; do
    sed -e '/^# @COMMON@$/{' -e "r $SRC/packaging/debian/common.sh" -e 'd' -e '}' \
        "$SRC/packaging/debian/$script" > "$STAGE/DEBIAN/$script"
    chmod 755 "$STAGE/DEBIAN/$script"
    grep -q '@COMMON@' "$STAGE/DEBIAN/$script" && die "$script: the shared helpers were not pasted in"
    sh -n "$STAGE/DEBIAN/$script" || die "$script has a syntax error"
done

# ---------------------------------------------------------------- 5. checks on what will be shipped

step "glibc floor (limit $GLIBC_LIMIT) of every binary in the package"
newer() { [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" = "$1" ] && [ "$1" != "$2" ]; }
report="$WORK/glibc.txt"
: > "$report"
while IFS= read -r -d '' file; do
    glibc=$(objdump -T "$file" 2>/dev/null | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -V | tail -n 1 || true)
    cxx=$(objdump -T "$file" 2>/dev/null | grep -o 'GLIBCXX_[0-9.]*' | sed 's/GLIBCXX_//' | sort -V | tail -n 1 || true)
    [ -n "$glibc$cxx" ] && printf '%s\t%s\t%s\n' "${glibc:--}" "${cxx:--}" "${file#"$STAGE"/}" >> "$report"
done < <(find "$STAGE/usr/lib/protonbackup" -type f -print0)
echo "binaries checked: $(wc -l < "$report")"
echo "highest glibc symbol versions:"
awk -F'\t' '$1 != "-"' "$report" | sort -t$'\t' -k1,1V | tail -n 4 | awk -F'\t' '{printf "  glibc %-6s glibcxx %-8s %s\n", $1, $2, $3}'
if awk -F'\t' '$2 != "-" {found = 1} END {exit !found}' "$report"; then
    echo "highest libstdc++ symbol versions:"
    awk -F'\t' '$2 != "-"' "$report" | sort -t$'\t' -k2,2V | tail -n 3 | awk -F'\t' '{printf "  glibc %-6s glibcxx %-8s %s\n", $1, $2, $3}'
else
    echo "no binary needs libstdc++"
fi
offenders=0
while IFS=$'\t' read -r glibc cxx file; do
    if [ "$glibc" != "-" ] && newer "$glibc" "$GLIBC_LIMIT"; then
        echo "ERROR: $file needs glibc $glibc (limit $GLIBC_LIMIT)" >&2
        offenders=1
    fi
done < "$report"
[ "$offenders" = 0 ] || die "the package would not start on glibc $GLIBC_LIMIT"

step "system libraries the package needs, against its Depends line"
# Every library that some binary of the package asks the system for, apart from the ones the package
# ships itself. Each must be covered by the Depends line; a library that is not in this table is a
# new requirement (a JavaFX or JDK update brought it) and stops the build until it is looked at.
declare -A OWNER=(
    [libc.so.6]=libc6 [libm.so.6]=libc6 [libdl.so.2]=libc6 [libpthread.so.0]=libc6 [librt.so.1]=libc6 [ld-linux-x86-64.so.2]=libc6
    [libX11.so.6]=libx11-6 [libXext.so.6]=libxext6 [libXi.so.6]=libxi6 [libXrender.so.1]=libxrender1
    [libXtst.so.6]=libxtst6 [libXxf86vm.so.1]=libxxf86vm1 [libGL.so.1]=libgl1
    [libfontconfig.so.1]=libfontconfig1 [libfreetype.so.6]=libfreetype6
    # Everything GTK itself needs comes with it.
    [libgtk-3.so.0]=libgtk-3-0 [libgdk-3.so.0]=libgtk-3-0 [libgdk_pixbuf-2.0.so.0]=libgtk-3-0
    [libgio-2.0.so.0]=libgtk-3-0 [libgobject-2.0.so.0]=libgtk-3-0 [libglib-2.0.so.0]=libgtk-3-0
    [libgthread-2.0.so.0]=libgtk-3-0 [libpango-1.0.so.0]=libgtk-3-0 [libpangoft2-1.0.so.0]=libgtk-3-0
    [libpangocairo-1.0.so.0]=libgtk-3-0 [libcairo.so.2]=libgtk-3-0 [libcairo-gobject.so.2]=libgtk-3-0
    [libatk-1.0.so.0]=libgtk-3-0
    # javax.sound, which neither JavaFX nor this app ever loads: not a dependency.
    [libasound.so.2]=OPTIONAL
)
# libsecret-1-0 is not needed by any binary here: it is the Proton CLI that keeps the session in the keyring.
DEPENDS="libc6 (>= $GLIBC_LIMIT), libgtk-3-0t64 | libgtk-3-0, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libxxf86vm1, libgl1, libfontconfig1, libfreetype6, libsecret-1-0"
shipped=$(find "$STAGE/usr/lib/protonbackup" -type f -name '*.so*' -printf '%f\n' | sort -u)
# (readelf fails on a file that is not an ELF binary; that must not stop the pipeline.)
wanted=$(find "$STAGE/usr/lib/protonbackup" -type f \( -name '*.so' -o -name '*.so.*' -o -perm -u+x \) -print0 \
    | xargs -0 -n 20 sh -c 'readelf -d "$@" 2>/dev/null || true' _ | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/' | sort -u)
unknown=0
for lib in $wanted; do
    grep -qx "$lib" <<<"$shipped" && continue
    owner="${OWNER[$lib]:-}"
    if [ -z "$owner" ]; then echo "ERROR: the package needs $lib from the system, and nothing in the build knows which package provides it" >&2; unknown=1; continue; fi
    [ "$owner" = OPTIONAL ] && { echo "optional: $lib"; continue; }
    grep -q -F "$owner" <<<"$DEPENDS" || { echo "ERROR: $lib comes from $owner, which the Depends line does not name" >&2; unknown=1; }
done
[ "$unknown" = 0 ] || die "the Depends line and the libraries the binaries need do not agree"
echo "needed from the system: $(grep -v -x -F -f <(echo "$shipped") <<<"$wanted" | tr '\n' ' ')"

step "no JavaFX library left inside a jar, natives where java.library.path looks"
for jar in "$STAGE"/usr/lib/protonbackup/lib/app/javafx-*.jar; do
    ! has_natives "$jar" || die "$(basename "$jar") still contains native libraries"
done
for lib in libglass.so libglassgtk3.so libprism_es2.so libprism_sw.so libjavafx_font.so libsqlitejdbc.so; do
    [ -f "$STAGE/usr/lib/protonbackup/lib/app/natives/$lib" ] || die "natives/$lib is missing"
done
echo ok

# ---------------------------------------------------------------- 6. control file and the .deb

step "control file"
installed_kb=$(du -sk "$STAGE" | cut -f1)
cat > "$STAGE/DEBIAN/control" <<EOF
Package: $PACKAGE
Version: $VERSION
Section: utils
Priority: optional
Architecture: $ARCH
Depends: $DEPENDS
Installed-Size: $installed_kb
Maintainer: Rinke Kleijer <rkl_shop@hotmail.com>
Description: One-way backup of local folders to Proton Drive
 Copies local folders to Proton Drive and uploads only new and changed files. Never deletes
 or replaces anything on Proton. Runs as a systemd user timer, with a desktop app for the
 settings, the status and signing in. Contains its own Java runtime: no Java needs to be
 installed.
 .
 The official proton-drive CLI is not part of this package; the app finds it and offers to
 download it, checking its SHA-512.
EOF
cat "$STAGE/DEBIAN/control"

step "building the .deb (xz: Debian 11's dpkg cannot read the zstd that a current dpkg uses by default)"
rm -f "$DEB"
dpkg-deb -Zxz --root-owner-group --build "$STAGE" "$DEB" >/dev/null

step "checks on the .deb"
dpkg-deb --info "$DEB" | sed -n '1,3p'
# Nothing may be installed outside these places (MIGRATION_PLAN.md, section 2a, rule 6).
stray=$(dpkg-deb --contents "$DEB" | awk '{print $6}' | sed -e 's#^\./##' -e 's#/$##' | grep -v '^$' \
    | grep -v -E '^(usr|usr/bin(/.*)?|usr/lib|usr/lib/protonbackup(/.*)?|usr/lib/systemd|usr/lib/systemd/user(/.*)?|usr/share|usr/share/applications(/.*)?|usr/share/icons(/.*)?|usr/share/doc|usr/share/doc/protonbackup(/.*)?)$' || true)
[ -z "$stray" ] || die "the package would install outside its own places: $(echo "$stray" | head -n 5 | tr '\n' ' ')"
echo "layout ok: $(dpkg-deb --contents "$DEB" | wc -l) entries, all in the allowed places"
ls -l "$DEB" | awk '{printf "size: %.1f MB\n", $5/1048576}'

# A first start-up test of the staged files, on the build machine's own glibc (the other
# distributions are tested with the real .deb by packaging/test/distros.sh).
step "smoke test of the staged daemon"
# The Maven tests above leave SQLite libraries in /tmp, so count before and after instead of looking for none.
extracted() { find /tmp -maxdepth 1 -name 'sqlite-*' | wc -l; }
smoke_home=$(mktemp -d)
before=$(extracted)
HOME="$smoke_home" LC_ALL=C.UTF-8 "$STAGE/usr/lib/protonbackup/bin/protonbackup" list-sources >"$WORK/smoke.txt" 2>&1 \
    || { cat "$WORK/smoke.txt" >&2; die "the staged daemon does not start"; }
cat "$WORK/smoke.txt"
[ "$(extracted)" = "$before" ] || die "SQLite extracted its library into /tmp: org.sqlite.lib.path is not working"
[ -f "$smoke_home/.local/share/ProtonBackup/protonbackup.db" ] || die "the daemon did not create its database"
rm -rf "$smoke_home"
echo "daemon starts, creates its database, SQLite loaded from natives (nothing extracted to /tmp)"

printf '\nDone: %s\n' "${DEB}"
