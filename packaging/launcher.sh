#!/bin/sh
# Installed twice, as /usr/bin/protonbackup and /usr/bin/protonbackup-ui (two copies, not
# symlinks: the name this script is called by selects the real launcher).
#
# Why a wrapper at all: Java decodes file names and encodes process arguments with the charset
# of the locale. Without a UTF-8 locale (a systemd user service or cron job often has no LANG,
# and `LC_ALL=C` is common) every non-ASCII file name is mangled to "?" before the app sees it,
# which would corrupt the database and the paths handed to the Proton CLI. The JVM cannot fix
# this itself (-Dsun.jnu.encoding is ignored), so the locale has to be right *before* it starts.
#
# PROTONBACKUP_HOME overrides the install location; it exists for tests only.

set -u

APP_HOME="${PROTONBACKUP_HOME:-/usr/lib/protonbackup}"
NAME="$(basename "$0")"

# The daemon writes this path into the systemd units it installs, so the units call the wrapper
# (and with it the UTF-8 guarantee below), not the launcher inside APP_HOME.
PROTONBACKUP_EXEC="$(cd "$(dirname "$0")" && pwd)/$NAME"
export PROTONBACKUP_EXEC

is_utf8() {
    case "$1" in
        *[Uu][Tt][Ff]-8* | *[Uu][Tt][Ff]8*) return 0 ;;
        *) return 1 ;;
    esac
}

# The effective charset comes from LC_ALL, else LC_CTYPE, else LANG.
current="${LC_ALL:-${LC_CTYPE:-${LANG:-}}}"

if ! is_utf8 "$current"; then
    chosen=""
    if command -v locale >/dev/null 2>&1; then
        available="$(locale -a 2>/dev/null)"
        for candidate in C.UTF-8 C.utf8 en_US.UTF-8 en_US.utf8; do
            if printf '%s\n' "$available" | grep -qx "$candidate"; then
                chosen="$candidate"
                break
            fi
        done
    else
        # No `locale` tool (very minimal system): C.UTF-8 is built into glibc 2.35 and newer
        # and ships with Debian and Ubuntu for much longer, so it is the best guess.
        chosen="C.UTF-8"
    fi
    if [ -n "$chosen" ]; then
        LC_ALL="$chosen"
        export LC_ALL
    fi
fi

exec "$APP_HOME/bin/$NAME" "$@"
