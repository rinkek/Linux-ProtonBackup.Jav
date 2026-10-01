#!/usr/bin/env bash
# Runs INSIDE a container of one distribution; started by distros.sh, not by hand.
#
# Installs the real .deb (or, on distributions without dpkg, its files) and then checks, as an
# ordinary user with a clean home folder, everything that can differ between distributions: that it
# installs, that the daemon and the window start, that file names survive a hostile locale, that the
# Proton CLI is started with the user's own environment, that SIGTERM still ends a run cleanly, what
# the app leaves behind in the home folder and in /tmp, and how long one daemon start takes.
#
# Mounts: /pkg (the .deb, read-only), /out (screenshots). Environment: HOST_UID, HOST_GID, DEB.
set -u

. /etc/os-release
FAILED=0
pass() { printf 'PASS  %s%s\n' "$1" "${2:+  ($2)}"; }
fail() { printf 'FAIL  %s%s\n' "$1" "${2:+  ($2)}"; FAILED=1; }
note() { printf '      %s\n' "$*"; }

NAME=$(printf '%s-%s' "$ID" "${VERSION_ID:-x}" | tr '/:' '__')
DEB_FILE="/pkg/$DEB"
HOME_DIR=/home/tester

echo "distro: $PRETTY_NAME   glibc: $(ldd --version 2>&1 | head -n 1 | grep -o '[0-9][0-9.]*$')"

# ---------------------------------------------------------------- installing

if command -v apt-get >/dev/null 2>&1; then
    FAMILY=deb
    export DEBIAN_FRONTEND=noninteractive
    # Debian 11 is end-of-life. Its image names, as comments in sources.list, the snapshot of its last day
    # as the place for its packages; the live repositories no longer serve them.
    if [ "$ID" = debian ] && [ "${VERSION_ID:-}" = 11 ]; then
        sed -i -e '/^deb /d' -e 's/^# \(deb http:\/\/snapshot\)/\1/' /etc/apt/sources.list
        echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/99archive
    fi
    apt-get update -qq >/dev/null 2>&1
    note "dpkg $(dpkg --version | head -n 1 | grep -o '[0-9][0-9.]*' | head -n 1)"
    # The package's own Depends must be enough for the program: nothing but the .deb is installed
    # before the first start (the test tools come afterwards, in a separate step).
    if apt-get install -y -qq --no-install-recommends "$DEB_FILE" >/tmp/install.log 2>&1; then
        pass "install the .deb with apt" "$(dpkg -l | awk '/^ii  (libgtk-3-0t64|libgtk-3-0) /{print $2" "$3}')"
    else
        fail "install the .deb with apt"; tail -n 8 /tmp/install.log; exit 1
    fi
    apt-get install -y -qq --no-install-recommends xvfb xauth imagemagick procps util-linux >/tmp/tools.log 2>&1 \
        || { fail "install test tools"; tail -n 5 /tmp/tools.log; }
else
    FAMILY=rpm
    pm=dnf; command -v dnf >/dev/null 2>&1 || pm=yum
    # No dpkg here: the package's files are unpacked by hand and its Depends are installed by name.
    $pm install -y -q binutils tar xz util-linux procps-ng >/tmp/tools.log 2>&1 || { fail "install unpack tools"; tail -n 5 /tmp/tools.log; }
    $pm install -y -q gtk3 libX11 libXext libXi libXrender libXtst libXxf86vm mesa-libGL fontconfig freetype libsecret dejavu-sans-fonts >/tmp/install.log 2>&1 \
        || { fail "install the packages the .deb would depend on"; tail -n 8 /tmp/install.log; exit 1; }
    (cd /tmp && ar x "$DEB_FILE" && ls data.tar.* >/dev/null && tar -xf data.tar.* -C / && rm -f data.tar.* control.tar.* debian-binary) \
        && pass "unpack the .deb by hand (no dpkg on this distribution)" || { fail "unpack the .deb"; exit 1; }
    # netpbm turns the framebuffer dump into a PNG where ImageMagick is not in the distribution's own repositories.
    $pm install -y -q xorg-x11-server-Xvfb xorg-x11-xauth netpbm-progs >>/tmp/tools.log 2>&1 || { fail "install Xvfb and netpbm"; tail -n 5 /tmp/tools.log; }
fi

useradd -m -s /bin/bash -u 1000 tester 2>/dev/null || useradd -m -s /bin/bash tester
HOME_DIR=$(getent passwd tester | cut -d: -f6)
as_tester() { runuser -u tester -- env -i HOME="$HOME_DIR" PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 "$@"; }

# ---------------------------------------------------------------- the wrappers and the daemon

[ -x /usr/bin/protonbackup ] && [ -x /usr/bin/protonbackup-ui ] && pass "wrappers in /usr/bin" || fail "wrappers in /usr/bin"
[ "$(sha256sum < /usr/bin/protonbackup)" = "$(sha256sum < /usr/bin/protonbackup-ui)" ] && pass "the two wrappers are copies of one script" || fail "the two wrappers differ"

help_out=$(as_tester protonbackup --help 2>&1); help_status=$?
if [ "$help_status" = 0 ] && echo "$help_out" | grep -q -- '--run-once'; then pass "daemon: --help"; else fail "daemon: --help" "exit $help_status: $(echo "$help_out" | head -n 2)"; fi

# Java reads file names with the charset of the locale. A timer starts the daemon with no LANG at
# all, and LC_ALL=C is common: the wrapper must still deliver the name intact.
as_tester mkdir -p "$HOME_DIR/src/café-日本語"
for hostile in "LC_ALL=C" "LANG=C" "LC_ALL=POSIX"; do
    rm -rf "$HOME_DIR/.local/share/ProtonBackup"
    as_tester env "$hostile" protonbackup add-source "$HOME_DIR/src/café-日本語" "/Backup/café" >/dev/null 2>&1
    listed=$(as_tester env "$hostile" protonbackup list-sources 2>&1)
    if echo "$listed" | grep -q 'café-日本語'; then pass "non-ASCII folder name survives $hostile"; else fail "non-ASCII folder name under $hostile" "$listed"; fi
done
as_tester env -i HOME="$HOME_DIR" PATH=/usr/bin:/bin protonbackup list-sources 2>&1 | grep -q 'café-日本語' \
    && pass "non-ASCII folder name survives an empty environment (what a timer has)" || fail "non-ASCII name with an empty environment"

# ---------------------------------------------------------------- the CLI as a child process

CLI_DIR="$HOME_DIR/.local/share/ProtonBackup/bin"
as_tester mkdir -p "$CLI_DIR"
as_tester sh -c "cat > '$CLI_DIR/proton-drive'" <<'FAKE'
#!/bin/sh
# Fake Proton CLI: records the environment it was started with, and what it was asked to do.
env > "$HOME/cli-env.txt"
echo "$@" >> "$HOME/cli-calls.txt"
case "$1 $2" in
    "filesystem list")   echo '[]' ;;
    "filesystem info")   exit 0 ;;
    "filesystem upload") sleep "${FAKE_UPLOAD_SECONDS:-0}" ;;
    "version "*)         echo "proton-drive 0.0.0-fake" ;;
esac
exit 0
FAKE
as_tester chmod +x "$CLI_DIR/proton-drive"

as_tester env LD_LIBRARY_PATH=/opt/mine protonbackup --run-once >/tmp/run1.txt 2>&1; run_status=$?
if [ "$run_status" = 0 ]; then pass "daemon: --run-once against a fake CLI" "exit 0"; else fail "daemon: --run-once against a fake CLI" "exit $run_status: $(tail -n 3 /tmp/run1.txt)"; fi
if [ -f "$HOME_DIR/cli-env.txt" ]; then
    grep -q '^_JPACKAGE_LAUNCHER=' "$HOME_DIR/cli-env.txt" && fail "the CLI sees _JPACKAGE_LAUNCHER" || pass "the CLI does not see _JPACKAGE_LAUNCHER"
    ld=$(grep '^LD_LIBRARY_PATH=' "$HOME_DIR/cli-env.txt" || true)
    if [ "$ld" = "LD_LIBRARY_PATH=/opt/mine" ]; then pass "the CLI sees the user's own LD_LIBRARY_PATH, unchanged"; else fail "the CLI's LD_LIBRARY_PATH" "$ld"; fi
    as_tester env protonbackup --run-once >/dev/null 2>&1
    ld=$(grep '^LD_LIBRARY_PATH=' "$HOME_DIR/cli-env.txt" || true)
    [ -z "$ld" ] && pass "without one of the user's own, the CLI sees no LD_LIBRARY_PATH at all" || fail "the CLI got an LD_LIBRARY_PATH the user never set" "$ld"
else
    fail "the fake CLI was never started" "$(tail -n 3 /tmp/run1.txt)"
fi

# SIGTERM in the middle of an upload must end the run cleanly (exit 0). Two folders, so that the run
# has a checkpoint after the first batch, where the cancellation can land.
as_tester sh -c "cd '$HOME_DIR/src/café-日本語' && mkdir -p a b && echo one > a/one.txt && echo two > b/two.txt && touch -d '1 hour ago' a/one.txt b/two.txt"
as_tester env FAKE_UPLOAD_SECONDS=8 protonbackup --run-once >/tmp/term.txt 2>&1 &
runner_pid=$!
sleep 4
daemon_pid=$(pgrep -u tester -f '/usr/lib/protonbackup/bin/protonbackup --run-once' | head -n 1)
if [ -z "$daemon_pid" ]; then fail "SIGTERM during an upload: no daemon process found"; else kill -TERM "$daemon_pid"; fi
wait "$runner_pid"; term_status=$?
if [ "$term_status" = 0 ]; then pass "SIGTERM during an upload: the daemon ends cleanly" "exit 0"; else fail "SIGTERM during an upload" "exit $term_status: $(tail -n 3 /tmp/term.txt)"; fi
if grep -q -i 'cancel' /tmp/term.txt; then pass "the cancelled run is reported" "$(grep -i cancel /tmp/term.txt | head -n 1)"; else fail "no 'cancelled' in the daemon's output" "$(tail -n 4 /tmp/term.txt)"; fi

# No CLI at all: exit code 2 (the contract the timer's failure state relies on).
mv "$CLI_DIR/proton-drive" "$CLI_DIR/proton-drive.off"
as_tester env protonbackup --run-once >/dev/null 2>&1; missing_status=$?
[ "$missing_status" = 2 ] && pass "no CLI installed: --run-once exits 2" || fail "no CLI installed: exit code" "got $missing_status"
mv "$CLI_DIR/proton-drive.off" "$CLI_DIR/proton-drive"

# ---------------------------------------------------------------- libraries

# ldd of every library in the package, as the JVM would see it (libjvm.so is found through the launcher).
# libasound.so.2 belongs to javax.sound, which neither JavaFX nor this app ever loads: not a dependency.
missing=$(find /usr/lib/protonbackup -name '*.so*' -type f -exec env LD_LIBRARY_PATH=/usr/lib/protonbackup/lib/runtime/lib/server:/usr/lib/protonbackup/lib/runtime/lib ldd {} \; 2>/dev/null \
    | grep 'not found' | grep -v 'libasound.so.2' | sort | uniq -c | sort -rn)
if [ -z "$missing" ]; then pass "every shared library of the package resolves" "except libasound.so.2 of javax.sound, never loaded"; else
    fail "shared libraries that do not resolve" "see below"; echo "$missing" | sed 's/^/        /'
fi

# ---------------------------------------------------------------- the window

as_tester rm -rf "$HOME_DIR/.local" "$HOME_DIR/.config" "$HOME_DIR/.cache" "$HOME_DIR/cli-env.txt" "$HOME_DIR/cli-calls.txt"
touch /tmp/before-ui
cat > /tmp/ui-run.sh <<'RUN'
#!/bin/sh
protonbackup-ui >/tmp/ui.out 2>&1 &
pid=$!
sleep 15
if kill -0 "$pid" 2>/dev/null; then echo alive > /tmp/ui.state; else echo died > /tmp/ui.state; fi
grep -o '/[^ ]*\.so[^ ]*' "/proc/$pid/maps" 2>/dev/null | sort -u > /tmp/ui.maps
# Xvfb keeps the screen as an XWD file in the directory given with -fbdir: no screenshot client is needed.
fb=/tmp/fb/Xvfb_screen0
if command -v magick >/dev/null 2>&1; then magick xwd:$fb png:/tmp/ui.png 2>/tmp/shot.err
elif command -v convert >/dev/null 2>&1; then convert xwd:$fb png:/tmp/ui.png 2>/tmp/shot.err
else xwdtopnm $fb 2>/tmp/shot.err | pnmtopng > /tmp/ui.png 2>>/tmp/shot.err; fi
if command -v xwdtopnm >/dev/null 2>&1; then xwdtopnm $fb 2>/dev/null | ppmhist 2>/dev/null | wc -l > /tmp/ui.colors; fi
kill -TERM "$pid" 2>/dev/null
n=0; while kill -0 "$pid" 2>/dev/null && [ "$n" -lt 20 ]; do sleep 1; n=$((n + 1)); done
if kill -0 "$pid" 2>/dev/null; then echo "hung after SIGTERM" > /tmp/ui.exit; kill -KILL "$pid"; else echo "closed after SIGTERM" > /tmp/ui.exit; fi
RUN
chmod 755 /tmp/ui-run.sh
rm -rf /tmp/ui.state /tmp/ui.maps /tmp/ui.png /tmp/ui.out /tmp/ui.exit /tmp/ui.colors /tmp/fb
mkdir /tmp/fb && chown tester /tmp/fb /tmp/ui-run.sh
as_tester xvfb-run -a -s "-screen 0 1280x800x24 -fbdir /tmp/fb" sh /tmp/ui-run.sh >/tmp/xvfb.out 2>&1
# files written by the tester are not readable by root-owned /tmp scripts only when modes differ; read as root
state=$(cat /tmp/ui.state 2>/dev/null || echo "no state")
if [ "$state" = alive ]; then pass "UI: the window stays up for 15 s"; else fail "UI: the window" "$state"; fi
if [ -s /tmp/ui.png ]; then
    colors=$(identify -format '%k' /tmp/ui.png 2>/dev/null || magick identify -format '%k' /tmp/ui.png 2>/dev/null || cat /tmp/ui.colors 2>/dev/null || echo 0)
    if [ "${colors:-0}" -gt 50 ]; then pass "UI: the screen shows a rendered window" "$colors colours"; else fail "UI: the screenshot is nearly blank" "$colors colours"; fi
    [ -d /out ] && cp /tmp/ui.png "/out/$NAME.png"
else
    fail "UI: no screenshot" "$(cat /tmp/shot.err 2>/dev/null | head -n 2)"
fi
natives_loaded=$(grep -c '/usr/lib/protonbackup/lib/app/natives/' /tmp/ui.maps 2>/dev/null || true)
[ "${natives_loaded:-0}" -ge 5 ] && pass "UI: JavaFX and SQLite natives are loaded from lib/app/natives" "$natives_loaded libraries" || fail "UI: natives not loaded from lib/app/natives" "$natives_loaded"
[ "$(cat /tmp/ui.exit 2>/dev/null)" = "closed after SIGTERM" ] && pass "UI: closes on SIGTERM" || fail "UI: SIGTERM" "$(cat /tmp/ui.exit 2>/dev/null)"
if grep -q -E 'Exception|Error:|UnsatisfiedLink|NoClassDef' /tmp/ui.out; then fail "UI: errors on stderr" "$(grep -E 'Exception|Error' /tmp/ui.out | head -n 3)"; else pass "UI: no exception on stderr"; fi
note "UI output: $(grep -v -E '^$' /tmp/ui.out | sort | uniq -c | sort -rn | head -n 6 | tr '\n' '|')"
note "UI tray: $(grep -i -E 'tray' /tmp/ui.out | head -n 1)"

# ---------------------------------------------------------------- what the app leaves behind

echo "--- files created by the UI run (everything below the home folder, and /tmp except test files)"
find "$HOME_DIR" -mindepth 1 -newer /tmp/before-ui \( -type f -o -type l \) 2>/dev/null | sed "s#^$HOME_DIR/#  ~/#" | sort | head -n 40
echo "--- /tmp"
find /tmp -mindepth 1 -maxdepth 1 -newer /tmp/before-ui ! -name 'ui.*' ! -name 'fb' ! -name 'ui-run.sh' ! -name 'shot.err' ! -name 'xvfb.out' ! -name 'before-ui' ! -name '.X*' ! -name 'xvfb-run.*' ! -name '.ICE-unix' 2>/dev/null | sed 's#^#  #' | sort | head -n 20
[ -d "$HOME_DIR/.openjfx" ] && fail "~/.openjfx was created" || pass "no ~/.openjfx"
ls /tmp/sqlite-* >/dev/null 2>&1 && fail "SQLite library extracted into /tmp" || pass "no SQLite library extracted into /tmp"
[ -z "$(find /usr/lib/protonbackup -newer /tmp/before-ui 2>/dev/null)" ] && pass "nothing was written below /usr/lib/protonbackup" || fail "something was written below /usr/lib/protonbackup" "$(find /usr/lib/protonbackup -newer /tmp/before-ui | head -n 3 | tr '\n' ' ')"

# --cleanup must leave nothing of ours in the home folder. (These containers have no systemd, so its
# systemctl steps are expected to be reported as failed and the exit code is not 0; the lifecycle test
# with a real user manager checks the exit code.)
as_tester protonbackup add-source "$HOME_DIR/src/café-日本語" "/Backup" >/dev/null 2>&1
as_tester protonbackup install-units >/dev/null 2>&1
as_tester protonbackup --cleanup --yes >/tmp/cleanup.txt 2>&1; cleanup_status=$?
left=$(find "$HOME_DIR" \( -iname '*protonbackup*' -o -name '.openjfx' \) 2>/dev/null | head -n 10)
if [ -z "$left" ]; then pass "--cleanup --yes: nothing named protonbackup is left in the home folder" "exit $cleanup_status, no systemd here"; else
    fail "--cleanup --yes left files behind" "$left"; tail -n 12 /tmp/cleanup.txt | sed 's/^/        /'
fi
grep -q 'Interface cache removed' /tmp/cleanup.txt && pass "--cleanup reports all its steps" || fail "--cleanup output is incomplete" "$(head -n 3 /tmp/cleanup.txt)"

# ---------------------------------------------------------------- start-up time

as_tester protonbackup list-sources >/dev/null 2>&1
start=$(date +%s%N)
for i in 1 2 3 4 5 6 7 8 9 10; do as_tester protonbackup list-sources >/dev/null 2>&1; done
end=$(date +%s%N)
note "daemon start (list-sources, incl. runuser and env): $(( (end - start) / 10000000 )) ms on average over 10 runs"
as_tester protonbackup --cleanup --yes >/dev/null 2>&1

# ---------------------------------------------------------------- removing the package without systemd

# No user manager here, so the maintainer scripts have nothing to stop: they must still succeed, quickly
# and quietly, and remove everything. (The removals with a real systemd are in lifecycle.sh.)
if [ "$FAMILY" = deb ]; then
    as_tester protonbackup list-sources >/dev/null 2>&1   # the app has been used: there is data in the home folder
    started=$(date +%s)
    apt-get remove -y -qq protonbackup >/tmp/remove.log 2>&1; remove_status=$?
    if [ "$remove_status" = 0 ]; then pass "apt remove, without systemd" "$(( $(date +%s) - started )) s"; else fail "apt remove" "exit $remove_status: $(tail -n 3 /tmp/remove.log)"; fi
    grep -q 'protonbackup --cleanup' /tmp/remove.log && pass "remove: the note about the data in the home folder is printed" || fail "remove: no note about the home folder"
    [ -z "$(ls -d /usr/lib/protonbackup /usr/bin/protonbackup /usr/bin/protonbackup-ui /usr/lib/systemd/user/protonbackup* /usr/share/applications/protonbackup.desktop 2>/dev/null)" ] \
        && pass "remove: no file of the package is left" || fail "remove: files left" "$(ls -d /usr/lib/protonbackup /usr/bin/protonbackup* /usr/lib/systemd/user/protonbackup* 2>/dev/null | tr '\n' ' ')"
    apt-get purge -y -qq protonbackup >/tmp/purge.log 2>&1; purge_status=$?
    if [ "$purge_status" = 0 ]; then pass "apt purge, without systemd"; else fail "apt purge" "exit $purge_status: $(tail -n 3 /tmp/purge.log)"; fi
    [ -z "$(dpkg -l protonbackup 2>/dev/null | grep -E '^(ii|rc)')" ] && pass "purge: dpkg no longer knows the package" || fail "purge: dpkg still lists the package"
    [ -z "$(find /usr/lib/protonbackup /usr/share/doc/protonbackup 2>/dev/null)" ] && pass "purge: nothing left below /usr/lib/protonbackup or /usr/share/doc/protonbackup" || fail "purge: files left"
    apt-get install -y -qq --no-install-recommends "$DEB_FILE" >/tmp/reinstall.log 2>&1 && pass "install again after the purge" || fail "install again after the purge"
fi

[ -d /out ] && chown -R "${HOST_UID:-0}:${HOST_GID:-0}" /out 2>/dev/null
echo
[ "$FAILED" = 0 ] && echo "RESULT $NAME: all checks passed" || echo "RESULT $NAME: SOME CHECKS FAILED"
exit "$FAILED"
