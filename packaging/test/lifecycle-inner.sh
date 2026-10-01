#!/usr/bin/env bash
# Runs INSIDE a container with a real systemd and a real systemd user manager (lifecycle.sh starts
# it): the removal and upgrade matrix of MIGRATION_PLAN.md, section 6.8. After every case the
# system and the home folder are searched for what is left.
#
# Mounts: /pkg (the new .deb), /old (an older build of the same package). Environment: DEB.
set -u

NEW="/pkg/$DEB"
OLD=/old/protonbackup_0.4.4_amd64.deb
USER_ID=1001
H=/home/tester
FAILED=0
LOG=/tmp/lifecycle-apt.log
export DEBIAN_FRONTEND=noninteractive

pass() { printf 'PASS  %s%s\n' "$1" "${2:+  ($2)}"; }
fail() { printf 'FAIL  %s%s\n' "$1" "${2:+  ($2)}"; FAILED=1; }
note() { printf '      %s\n' "$*"; }
case_header() { printf '\n--- %s\n' "$*"; }

as_t() {
    runuser -u tester -- env HOME="$H" PATH=/usr/local/bin:/usr/bin:/bin LANG=C.UTF-8 \
        XDG_RUNTIME_DIR="/run/user/$USER_ID" DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$USER_ID/bus" "$@"
}
sc() { as_t systemctl --user "$@"; }
pb() { as_t protonbackup "$@"; }

wait_until() { # wait_until <seconds> <command...>
    local limit=$1 n=0; shift
    while ! "$@" >/dev/null 2>&1; do
        n=$((n + 1)); [ "$n" -ge "$limit" ] && return 1; sleep 1
    done
}

app_processes() { pgrep -f '^/usr/lib/protonbackup/bin/' 2>/dev/null; }
timer_active() { [ "$(sc is-active protonbackup-sync.timer 2>/dev/null)" = active ]; }
timer_enabled() { [ "$(sc is-enabled protonbackup-sync.timer 2>/dev/null)" = enabled ]; }

install_new() { apt-get install -y -qq --no-install-recommends "$NEW" >"$LOG" 2>&1; }
install_old() { apt-get install -y -qq --no-install-recommends "$OLD" >"$LOG" 2>&1; }

# Everything of the package that may be on the system outside the home folder.
system_residue() {
    local found="" path
    for path in /usr/bin/protonbackup /usr/bin/protonbackup-ui /usr/lib/protonbackup /usr/share/doc/protonbackup \
            /usr/lib/systemd/user/protonbackup-sync.service /usr/lib/systemd/user/protonbackup-sync@.service \
            /usr/lib/systemd/user/protonbackup-sync.timer /usr/share/applications/protonbackup.desktop \
            /usr/share/icons/hicolor/256x256/apps/protonbackup.png; do
        [ -e "$path" ] && found="$found $path"
    done
    echo "$found"
}
# Everything of the app that may be in the home folder.
home_residue() {
    find "$H" \( -iname '*protonbackup*' -o -name '.openjfx' -o -name 'hs_err_pid*' \) 2>/dev/null | sed "s#^$H/#~/#" | tr '\n' ' '
}

fresh() {
    sc stop protonbackup-sync.timer protonbackup-sync.service >/dev/null 2>&1
    sc disable protonbackup-sync.timer >/dev/null 2>&1
    apt-get purge -y -qq protonbackup >/dev/null 2>&1
    pkill -u tester -f '^/usr/lib/protonbackup/bin/' 2>/dev/null
    pkill -u tester Xvfb 2>/dev/null
    rm -rf "$H/.local/share/ProtonBackup" "$H/.config/ProtonBackup" "$H/.cache/ProtonBackup" "$H/.openjfx" \
        "$H"/.config/systemd/user/protonbackup* "$H"/.config/systemd/user/timers.target.wants/protonbackup* \
        "$H"/.local/share/systemd/timers/stamp-protonbackup* "$H"/hs_err_pid*.log "$H/src" "$H/fake-upload-seconds"
    sc daemon-reload >/dev/null 2>&1
    sc reset-failed >/dev/null 2>&1
    true
}

# A fake Proton CLI: every folder "contains" the two files of the test source, so an upload is confirmed;
# an upload takes as long as fake-upload-seconds says.
fake_cli() {
    as_t mkdir -p "$H/.local/share/ProtonBackup/bin"
    echo "$1" | as_t tee "$H/fake-upload-seconds" >/dev/null
    as_t sh -c "cat > $H/.local/share/ProtonBackup/bin/proton-drive" <<'FAKE'
#!/bin/sh
case "$1 $2" in
    "filesystem list")
        if [ "$3" = /my-files ]; then echo '[]'; else
            echo '[{"uid":"1","type":"file","name":{"ok":true,"value":"one.txt"},"activeRevision":{"claimedSize":4}},{"uid":"2","type":"file","name":{"ok":true,"value":"two.txt"},"activeRevision":{"claimedSize":4}}]'
        fi ;;
    "filesystem upload") sleep "$(cat "$HOME/fake-upload-seconds")" ;;
esac
exit 0
FAKE
    as_t chmod +x "$H/.local/share/ProtonBackup/bin/proton-drive"
}
set_upload_seconds() { echo "$1" | as_t tee "$H/fake-upload-seconds" >/dev/null; }

# A source with two folders, so that a run has a checkpoint after the first batch.
make_source() {
    as_t mkdir -p "$H/src/a" "$H/src/b"
    as_t sh -c "echo one > $H/src/a/one.txt; echo two > $H/src/b/two.txt; touch -d '1 hour ago' $H/src/a/one.txt $H/src/b/two.txt"
    pb add-source "$H/src" /Backup >/dev/null 2>&1
}

# What a user who has been using the app has: units, the timer on, a source, a database, a finished run.
used_app() {
    fake_cli 1
    make_source
    pb install-units >/dev/null 2>&1
    pb set-interval 5 >/dev/null 2>&1
    pb enable-timer >/dev/null 2>&1
    pb --run-once >/dev/null 2>&1
}

echo "distro: $(. /etc/os-release; echo "$PRETTY_NAME")   systemd: $(systemctl --version | head -n 1)   dpkg $(dpkg --version | head -n 1 | grep -o '[0-9][0-9.]*' | head -n 1)"
apt-get update -qq >/dev/null 2>&1   # the image has no package lists; the package's Depends must be resolvable
if ! wait_until 30 test -S "/run/user/$USER_ID/systemd/private"; then fail "no user manager for the test user"; exit 1; fi
[ "$(sc is-system-running 2>/dev/null)" = running ] && pass "a real systemd user manager is running" || note "user manager state: $(sc is-system-running 2>&1)"
fresh

# ======================================================================================= 1
case_header "1. fresh install, remove, purge"
install_new && pass "install" "$(dpkg -s protonbackup | grep '^Version')" || { fail "install"; tail -n 5 "$LOG"; }
[ -z "$(app_processes)" ] && pass "nothing runs after the installation"
sc daemon-reload; sc cat protonbackup-sync.timer >/dev/null 2>&1 && pass "the user manager sees the packaged timer unit" || fail "the packaged timer unit is not known to the user manager"
apt-get remove -y -qq protonbackup >"$LOG" 2>&1 && pass "remove" || { fail "remove"; tail -n 5 "$LOG"; }
[ -z "$(system_residue)" ] && pass "remove: no file of the package is left" || fail "remove: left behind" "$(system_residue)"
grep -q 'protonbackup --cleanup' "$LOG" && pass "remove: the note about the data in the home folder is shown" || fail "remove: no note about the home folder"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1 && pass "purge" || { fail "purge"; tail -n 5 "$LOG"; }
[ -z "$(dpkg -l protonbackup 2>/dev/null | grep '^ii')" ] && [ -z "$(dpkg -l protonbackup 2>/dev/null | grep '^rc')" ] && pass "purge: dpkg no longer knows the package" || fail "purge: dpkg status" "$(dpkg -l protonbackup 2>&1 | tail -n 1)"
[ -z "$(find /usr/lib/protonbackup /usr/share/doc/protonbackup 2>/dev/null)" ] && pass "purge: find /usr/lib/protonbackup /usr/share/doc/protonbackup is empty" || fail "purge: files left" "$(find /usr/lib/protonbackup /usr/share/doc/protonbackup 2>/dev/null | head -n 3 | tr '\n' ' ')"
[ -z "$(home_residue)" ] && pass "nothing in the home folder (the app never ran)" || fail "home folder" "$(home_residue)"

# ======================================================================================= 2
case_header "2. remove while the timer is enabled"
fresh; install_new
used_app
timer_active && timer_enabled && pass "before: the timer is enabled and active" || fail "before: the timer is not active (setup failed)" "$(sc is-active protonbackup-sync.timer) / $(sc is-enabled protonbackup-sync.timer)"
apt-get remove -y -qq protonbackup >"$LOG" 2>&1 && pass "remove" || { fail "remove"; tail -n 5 "$LOG"; }
timer_active && fail "after: the timer is still active" || pass "after: the timer is stopped"
timer_enabled && fail "after: the timer is still enabled" || pass "after: the timer is disabled" "$(sc is-enabled protonbackup-sync.timer 2>&1)"
[ -e "$H/.config/systemd/user/timers.target.wants/protonbackup-sync.timer" ] && fail "after: the enable link is still in the home folder" || pass "after: the enable link is gone from timers.target.wants"
sc list-timers --all --no-legend 2>/dev/null | grep -q protonbackup && fail "after: the timer is still listed by systemctl list-timers" || pass "after: systemctl --user list-timers no longer lists it"
[ -z "$(system_residue)" ] && pass "after: no file of the package is left" || fail "after: left behind" "$(system_residue)"
[ -z "$(app_processes)" ] && pass "after: no process left" || fail "after: processes left" "$(app_processes | tr '\n' ' ')"
grep -q 'rm -rf ~/.local/share/ProtonBackup' "$LOG" && pass "the manual fallback is printed" || fail "the manual fallback is not printed"
note "left in the home folder (user data, as documented): $(home_residue)"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1

# ======================================================================================= 3
case_header "3. remove while a sync is running"
fresh; install_new
fake_cli 40
make_source
pb install-units >/dev/null 2>&1
sc start --no-block protonbackup-sync.service
if wait_until 30 pgrep -u tester -f 'ProtonBackup/bin/proton-drive filesystem upload'; then pass "before: a run is in the middle of an upload"; else fail "before: the run never started its upload" "$(sc status protonbackup-sync.service 2>&1 | head -n 5)"; fi
started=$(date +%s)
apt-get remove -y -qq protonbackup >"$LOG" 2>&1 && pass "remove" "took $(( $(date +%s) - started )) s" || { fail "remove"; tail -n 5 "$LOG"; }
[ "$(sc is-active protonbackup-sync.service 2>/dev/null)" = active ] && fail "after: the service is still active" || pass "after: the service is not running" "$(sc is-active protonbackup-sync.service 2>&1) / result $(sc show protonbackup-sync.service -p Result --value 2>&1)"
[ -z "$(app_processes)" ] && [ -z "$(pgrep -u tester -f 'ProtonBackup/bin/proton-drive')" ] && pass "after: neither the daemon nor its CLI child is running" || fail "after: processes left" "$(pgrep -af 'protonbackup|proton-drive' | head -n 3 | tr '\n' ' ')"
[ -z "$(system_residue)" ] && pass "after: no file of the package is left" || fail "after: left behind" "$(system_residue)"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1

# ======================================================================================= 4
case_header "4. upgrade and remove while the window is open"
fresh; install_old
as_t sh -c 'Xvfb :99 -screen 0 1280x800x24 -nolisten tcp >/tmp/xvfb-lifecycle.log 2>&1 &'
sleep 2
open_window() {
    as_t sh -c "cd $H && DISPLAY=:99 nohup protonbackup-ui >$H/ui.out 2>&1 &"
    wait_until 40 sh -c "pgrep -f '^/usr/lib/protonbackup/bin/protonbackup-ui' >/dev/null && grep -q 'tray' $H/ui.out"
}
if open_window; then pass "before: the window of the old build is open"; else fail "before: the window did not open" "$(tail -n 3 "$H/ui.out" 2>/dev/null)"; fi
before_version=$(dpkg-query -W -f '${Version}' protonbackup)
apt-get install -y -qq --no-install-recommends "$NEW" >"$LOG" 2>&1 && pass "upgrade $before_version -> $(dpkg-query -W -f '${Version}' protonbackup)" || { fail "upgrade"; tail -n 5 "$LOG"; }
[ -z "$(app_processes)" ] && pass "after the upgrade: the old window was closed" || fail "after the upgrade: the old window is still running" "$(app_processes | tr '\n' ' ')"
ls "$H"/hs_err_pid*.log /tmp/hs_err_pid*.log >/dev/null 2>&1 && fail "a JVM crashed (hs_err file found)" || pass "no JVM crash during the upgrade"
grep -q -E 'NoClassDefFoundError|SIGBUS|Exception in thread' "$H/ui.out" && fail "errors in the old window's output" "$(grep -E 'NoClassDefFoundError|SIGBUS|Exception' "$H/ui.out" | head -n 2)" || pass "no errors in the old window's output"
[ ! -e /usr/lib/protonbackup/lib/app/OBSOLETE-FILE-FROM-0.4.4 ] && pass "a file of the old version that the new one no longer has was removed" || fail "obsolete file left behind"
if open_window; then pass "the new build's window opens"; else fail "the new window did not open" "$(tail -n 3 "$H/ui.out" 2>/dev/null)"; fi
apt-get remove -y -qq protonbackup >"$LOG" 2>&1 && pass "remove while the window is open" || { fail "remove"; tail -n 5 "$LOG"; }
[ -z "$(app_processes)" ] && pass "after the removal: the window was closed" || fail "after the removal: still running" "$(app_processes | tr '\n' ' ')"
ls "$H"/hs_err_pid*.log /tmp/hs_err_pid*.log >/dev/null 2>&1 && fail "a JVM crashed (hs_err file found)" || pass "no JVM crash during the removal"
[ -z "$(system_residue)" ] && pass "after the removal: no file of the package is left" || fail "left behind" "$(system_residue)"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1

# ======================================================================================= 5
case_header "5. upgrade over an older build, timer on, run in progress"
fresh; install_old
used_app
set_upload_seconds 40
pb force-all >/dev/null 2>&1
sc start --no-block protonbackup-sync.service
wait_until 30 pgrep -u tester -f 'ProtonBackup/bin/proton-drive filesystem upload' && pass "before: an upload is in progress under the old build" || fail "before: no upload in progress"
apt-get install -y -qq --no-install-recommends "$NEW" >"$LOG" 2>&1 && pass "upgrade" "$(dpkg-query -W -f '${Version}' protonbackup)" || { fail "upgrade"; tail -n 5 "$LOG"; }
[ "$(sc is-active protonbackup-sync.service 2>/dev/null)" = active ] && fail "the run of the old build is still going" || pass "the run of the old build was stopped"
timer_active && timer_enabled && pass "after: the timer is enabled and running again (postinst started it)" || fail "after: the timer is not running" "$(sc is-active protonbackup-sync.timer) / $(sc is-enabled protonbackup-sync.timer)"
pb list-sources 2>&1 | grep -q "$H/src" && pass "after: the new build reads the database of the old one" || fail "after: the database was not readable"
[ ! -e /usr/lib/protonbackup/lib/app/OBSOLETE-FILE-FROM-0.4.4 ] && pass "the obsolete file of the old build is gone" || fail "obsolete file left behind"
set_upload_seconds 1
sc start protonbackup-sync.service >/dev/null 2>&1
[ "$(sc show protonbackup-sync.service -p Result --value 2>/dev/null)" = success ] && pass "after: the timer's service runs the new build successfully" || fail "after: the service does not run" "$(sc status protonbackup-sync.service 2>&1 | tail -n 4 | tr '\n' ' ')"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1

# ======================================================================================= 6
case_header "6. --cleanup first, then remove"
fresh; install_new
used_app
note "the app has been used: $(home_residue)"
pb --cleanup --yes >/tmp/cleanup-lifecycle.txt 2>&1; cleanup_status=$?
[ "$cleanup_status" = 0 ] && pass "protonbackup --cleanup --yes" "exit 0 with a real systemd" || { fail "protonbackup --cleanup --yes" "exit $cleanup_status"; sed 's/^/        /' /tmp/cleanup-lifecycle.txt | tail -n 20; }
[ -z "$(home_residue)" ] && pass "after --cleanup: nothing of the app is left in the home folder" || fail "after --cleanup: left in the home folder" "$(home_residue)"
[ -e "$H/.local/share/systemd/timers/stamp-protonbackup-sync.timer" ] && fail "the timer stamp file is still there" || pass "no timer stamp file in ~/.local/share/systemd/timers"
timer_active && fail "after --cleanup: the timer is still active" || pass "after --cleanup: the timer is not active"
apt-get remove -y -qq protonbackup >"$LOG" 2>&1 && pass "remove" || { fail "remove"; tail -n 5 "$LOG"; }
[ -z "$(system_residue)" ] && pass "after remove: no file of the package is left" || fail "left behind" "$(system_residue)"
[ -z "$(home_residue)" ] && pass "after remove: still nothing in the home folder" || fail "home folder" "$(home_residue)"
[ -z "$(sc list-unit-files 'protonbackup*' --no-legend 2>/dev/null)" ] && pass "systemctl --user list-unit-files 'protonbackup*' is empty" || fail "unit files still known" "$(sc list-unit-files 'protonbackup*' --no-legend 2>&1 | tr '\n' ' ')"
[ -z "$(pgrep -af protonbackup)" ] && pass "pgrep -af protonbackup is empty" || fail "processes" "$(pgrep -af protonbackup | head -n 3)"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1

# ======================================================================================= 7
case_header "7. remove without --cleanup: the note, the fallback, and does the fallback work?"
fresh; install_new
used_app
apt-get remove -y -qq protonbackup >"$LOG" 2>&1; remove_status=$?
[ "$remove_status" = 0 ] && pass "remove succeeds although the home folder holds data" || fail "remove" "exit $remove_status"
grep -q 'protonbackup --cleanup' "$LOG" && grep -q 'rm -rf ~/.local/share/ProtonBackup' "$LOG" && pass "the note and the manual fallback are shown" || fail "note or fallback missing from the output"
note "left in the home folder: $(home_residue)"
# Run the commands from the printed fallback, exactly as printed, as the user.
# (apt runs dpkg on a pseudo-terminal, which turns every newline into CR LF: strip the CR.)
sed -n '/by hand, as yourself:/,/Nothing on Proton Drive/p' "$LOG" | tr -d '\r' | grep '^    ' | sed 's/^    //' >/tmp/fallback.txt
note "fallback commands printed: $(wc -l < /tmp/fallback.txt)"
while IFS= read -r command_line; do as_t bash -c "$command_line" >/dev/null 2>&1 </dev/null; done </tmp/fallback.txt
[ -z "$(home_residue)" ] && pass "the printed fallback commands remove everything from the home folder" || fail "the fallback leaves files behind" "$(home_residue)"
[ -z "$(sc list-unit-files 'protonbackup*' --no-legend 2>/dev/null)" ] && pass "and systemd no longer knows any unit" || fail "units remain after the fallback"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1 && pass "purge afterwards" || fail "purge"
grep -q 'rm -rf ~/.local/share/ProtonBackup' "$LOG" && pass "purge prints the fallback as well" || fail "purge: no fallback printed"

# ======================================================================================= 8
case_header "8. install again: after a purge, and after a removal with the user's data left"
fresh; install_new
used_app
apt-get remove -y -qq protonbackup >"$LOG" 2>&1
apt-get purge -y -qq protonbackup >"$LOG" 2>&1
install_new && pass "install after remove and purge, with the user's data still in the home folder" || { fail "install"; tail -n 5 "$LOG"; }
pb list-sources 2>&1 | grep -q "$H/src" && pass "the app finds its earlier settings and database" || fail "the earlier data was not found"
sc daemon-reload; sc cat protonbackup-sync.timer >/dev/null 2>&1 && pass "the units work again" || fail "units unknown"
pb enable-timer >/dev/null 2>&1; timer_active && pass "the timer can be enabled again" || fail "the timer cannot be enabled again" "$(sc is-active protonbackup-sync.timer 2>&1)"
pb --cleanup --yes >/dev/null 2>&1
apt-get purge -y -qq protonbackup >"$LOG" 2>&1
fresh; install_new && pass "install on a clean home folder" || fail "install"
pb list-sources 2>&1 | grep -q "$H/src" && fail "an old source appeared on a clean install" || pass "a clean install starts empty"
apt-get purge -y -qq protonbackup >"$LOG" 2>&1
[ -z "$(system_residue)" ] && pass "at the very end: nothing of the package on the system" || fail "left behind" "$(system_residue)"

fresh
echo
[ "$FAILED" = 0 ] && echo "RESULT lifecycle: all checks passed" || echo "RESULT lifecycle: SOME CHECKS FAILED"
exit "$FAILED"
