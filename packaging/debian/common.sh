# Helpers shared by postinst, prerm and postrm. The build script pastes this file into each of them
# at their marker line, because a maintainer script must stand on its own: by the time postrm runs,
# the package's files are already gone.
#
# Rules for everything in here:
#  - plain POSIX sh (dash, busybox ash), no bashisms;
#  - best effort. A package that cannot be removed because a helper failed is far worse than a
#    timer that was left running, so no function may make the calling script fail;
#  - never touch anything below /home. Per-user state is the app's business (`protonbackup --cleanup`).

APP_DIR=/usr/lib/protonbackup
TIMER=protonbackup-sync.timer

say() {
    printf '%s\n' "protonbackup: $*" >&2
}

# Prints "<uid> <user name>" for every user that has a running systemd user manager. Looked up
# through the manager's own socket, so it needs neither loginctl nor a running system manager.
user_managers() {
    for _dir in /run/user/*; do
        [ -S "$_dir/systemd/private" ] || continue
        _uid=${_dir#/run/user/}
        case "$_uid" in '' | *[!0-9]*) continue ;; esac
        _name=$(getent passwd "$_uid" 2>/dev/null | cut -d: -f1)
        [ -n "$_name" ] || continue
        printf '%s %s\n' "$_uid" "$_name"
    done
}

# user_systemctl <uid> <user name> <time limit in seconds> <systemctl arguments...>
# Runs `systemctl --user` as that user, talking to that user's own manager.
user_systemctl() {
    _uid=$1
    _name=$2
    _limit=$3
    shift 3
    _limiter=""
    if command -v timeout >/dev/null 2>&1; then _limiter="timeout $_limit"; fi
    if command -v runuser >/dev/null 2>&1; then
        $_limiter runuser -u "$_name" -- env "XDG_RUNTIME_DIR=/run/user/$_uid" systemctl --user "$@" \
            </dev/null >/dev/null 2>&1
    elif command -v su >/dev/null 2>&1; then
        $_limiter su -s /bin/sh -c "XDG_RUNTIME_DIR=/run/user/$_uid systemctl --user $*" "$_name" \
            </dev/null >/dev/null 2>&1
    else
        return 1
    fi
}

# Stops the timer (so nothing new starts) and then any run in progress, for every logged-in user.
# A running sync is asked to stop with SIGTERM and finishes its current batch; the daemon gives up
# after 60 seconds, so 80 is enough.
# With "disable" the timer is also turned off, which removes the enable link in the user's home.
stop_user_units() {
    user_managers | while read -r _u _n; do
        if user_systemctl "$_u" "$_n" 30 is-active --quiet "$TIMER"; then
            say "stopping the backup timer of $_n"
        fi
        user_systemctl "$_u" "$_n" 30 stop "$TIMER"
        user_systemctl "$_u" "$_n" 80 stop protonbackup-sync.service 'protonbackup-sync@*.service'
        if [ "${1:-}" = disable ]; then
            user_systemctl "$_u" "$_n" 30 disable "$TIMER"
        fi
    done
    return 0
}

# Starts the timer again for users who have it enabled (after an upgrade stopped it) and makes the
# running user managers read the unit files that came with the package.
start_enabled_timers() {
    user_managers | while read -r _u _n; do
        user_systemctl "$_u" "$_n" 30 daemon-reload
        if user_systemctl "$_u" "$_n" 30 is-enabled --quiet "$TIMER"; then
            user_systemctl "$_u" "$_n" 30 start "$TIMER"
        fi
    done
    return 0
}

# After the unit files are gone: let the running user managers forget them.
reload_user_managers() {
    user_managers | while read -r _u _n; do
        user_systemctl "$_u" "$_n" 30 daemon-reload
    done
    return 0
}

# The process ids of everything that runs from the installed files, whoever started it. The launcher
# is started by its full path, so that is the first word of its command line. (Not /proc/<pid>/exe:
# root may not read that for another user's process without CAP_SYS_PTRACE, which containers lack.)
app_pids() {
    grep -l -a -F "$APP_DIR/" /proc/[0-9]*/cmdline 2>/dev/null | while read -r _file; do
        _pid=${_file#/proc/}
        _pid=${_pid%/cmdline}
        _first=$(tr '\0' '\n' <"$_file" 2>/dev/null | head -n 1)
        case "$_first" in
            "$APP_DIR"/*) printf '%s\n' "$_pid" ;;
        esac
    done
}

# A JVM whose files are replaced under it crashes (NoClassDefFoundError, SIGBUS), so the window
# and any daemon still running are closed before the files change: SIGTERM, 15 seconds, then SIGKILL.
stop_app_processes() {
    _pids=$(app_pids)
    [ -n "$_pids" ] || return 0
    say "closing the running program(s): $(echo $_pids)"
    kill -TERM $_pids 2>/dev/null || true
    _waited=0
    while [ "$_waited" -lt 15 ]; do
        [ -z "$(app_pids)" ] && return 0
        sleep 1
        _waited=$((_waited + 1))
    done
    _pids=$(app_pids)
    [ -z "$_pids" ] || kill -KILL $_pids 2>/dev/null || true
    sleep 1
    return 0
}

refresh_desktop_caches() {
    if command -v update-desktop-database >/dev/null 2>&1; then
        update-desktop-database -q /usr/share/applications || true
    fi
    if command -v gtk-update-icon-cache >/dev/null 2>&1; then
        gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
    fi
    return 0
}

# A package may not delete files in anybody's home folder, so what the app keeps there stays.
# This is what to do about it. Keep it in step with Cleanup.java (a test checks that).
print_manual_cleanup() {
    cat >&2 <<'EOF'
protonbackup: the settings, the database and the downloaded Proton CLI in your home folder were
not removed; a package must not delete files there. To remove them, run this as yourself BEFORE
removing the package:

    protonbackup --cleanup

If the package is already gone, remove them by hand, as yourself:

    systemctl --user disable --now protonbackup-sync.timer
    rm -rf ~/.local/share/ProtonBackup ~/.config/ProtonBackup ~/.cache/ProtonBackup
    rm -f ~/.config/systemd/user/protonbackup-sync.service
    rm -f ~/.config/systemd/user/protonbackup-sync@.service
    rm -f ~/.config/systemd/user/protonbackup-sync.timer
    rm -rf ~/.config/systemd/user/protonbackup-sync.timer.d
    rm -f ~/.config/systemd/user/timers.target.wants/protonbackup-sync.timer
    rm -f ~/.local/share/systemd/timers/stamp-protonbackup-sync.timer
    rm -rf ~/.openjfx
    systemctl --user daemon-reload

Nothing on Proton Drive is touched by any of this. If you are signed in, sign out first
(Settings, Account) so the Proton session leaves your keyring too.
EOF
}
