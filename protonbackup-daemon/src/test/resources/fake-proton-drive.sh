#!/bin/sh
# Test double for the proton-drive CLI. The "remote" is a plain folder, $FAKE_REMOTE, so a real
# daemon process can run a real sync round against it without a network or an account.
#
# Like the real CLI it needs the conflict-strategy flags: without --file-conflict-strategy
# create-new-revision it stores nothing and still exits 0.
#
# Knobs (environment): FAKE_LOG      file that gets one line per call
#                      FAKE_UPLOAD_DELAY  seconds an upload takes (to interrupt a run half way)
#                      FAKE_SKIP     space separated names that are silently not stored
#                      FAKE_LOGGED_OUT  when set, every call but "version" says the session is gone

R="${FAKE_REMOTE:?FAKE_REMOTE is not set}"
[ -n "${FAKE_LOG:-}" ] && printf '%s\n' "$*" >> "$FAKE_LOG"

if [ "$1" = version ]; then echo "Proton Drive CLI cli-drive@0.8.0+fake"; exit 0; fi
if [ "$1" = auth ]; then exit 0; fi
if [ -n "${FAKE_LOGGED_OUT:-}" ]; then echo "You need to login first" >&2; exit 1; fi
[ "$1" = filesystem ] || { echo "unknown command $1" >&2; exit 2; }

verb="$2"
shift 2
case "$verb" in
list)
    dir="$R$1"
    [ -d "$dir" ] || { echo "Node not found" >&2; exit 1; }
    printf '['
    first=1
    find "$dir" -mindepth 1 -maxdepth 1 -printf '%y\t%s\t%f\n' | while IFS="$(printf '\t')" read -r type size name; do
        [ "$first" -eq 1 ] || printf ','
        first=0
        if [ "$type" = d ]; then
            printf '{"uid":"u-%s","type":"folder","name":{"ok":true,"value":"%s"}}' "$name" "$name"
        else
            printf '{"uid":"u-%s","type":"file","name":{"ok":true,"value":"%s"},"activeRevision":{"claimedSize":%s}}' "$name" "$name" "$size"
        fi
    done
    printf ']\n'
    ;;
info)
    [ -d "$R$1" ] || { echo "Node not found" >&2; exit 1; }
    echo '{}'
    ;;
create-folder)
    target="$R$1/$2"
    [ -e "$target" ] && { echo "A file or folder with that name already exists" >&2; exit 1; }
    mkdir "$target"
    ;;
upload)
    honour=0
    while [ $# -gt 0 ]; do
        case "$1" in
        --file-conflict-strategy) [ "$2" = create-new-revision ] && honour=1; shift 2 ;;
        --folder-conflict-strategy) shift 2 ;;
        --skip-thumbnails) shift ;;
        *) break ;;
        esac
    done
    for last; do :; done
    [ -d "$R$last" ] || { echo "Node not found" >&2; exit 1; }
    count=$(($# - 1))
    [ -n "${FAKE_UPLOAD_DELAY:-}" ] && sleep "$FAKE_UPLOAD_DELAY"
    i=0
    for file in "$@"; do
        i=$((i + 1))
        [ "$i" -le "$count" ] || break
        name=$(basename "$file")
        case " ${FAKE_SKIP:-} " in *" $name "*) continue ;; esac
        [ "$honour" -eq 1 ] && cp "$file" "$R$last/$name"
    done
    echo "Transfer summary: $count items"
    ;;
*)
    echo "unknown verb $verb" >&2
    exit 2
    ;;
esac
