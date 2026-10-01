# ProtonBackup (Java)

One-way, automatic backup of local folders to [Proton Drive](https://proton.me/drive) on Linux. This is the Java 21 + JavaFX port of the
.NET original; it behaves the same (see `DIFFERENCES.md` and `docs/BEHAVIOR_COMPARISON.md`).

Point it at one or more folders, sign in once, and it keeps them mirrored to Proton Drive in the background. New and changed files are
uploaded; **nothing already on Proton Drive is ever deleted or replaced by a sync.**

## Features

- **First-run wizard**: download the CLI, sign in, pick a folder, turn on the timer.
- **Runs unattended**: a small daemon does the syncing from a systemd user timer, also when the window is closed.
- **Several source folders**, each with its own destination on Proton Drive.
- **Status** (last run, next run, synced/queued/failed counts, sign-in state), a **file tree** with an errors-only filter, **run history** with the reason for every failed file, and a tray icon.
- **Manual control**: sync now, force a full resync, cancel, remove a source.
- **Self-updating CLI**: checks daily for a newer Proton `proton-drive` CLI, with roll back.

## How it works

The window shows status and drives the daemon through systemd. Scanning and uploading happen in the daemon, which runs Proton's official
`proton-drive` CLI (downloaded on first use, SHA-512 verified, not part of the package). A local SQLite database is the only thing the two share.
After every batch the daemon checks the remote folder listing, because the CLI can exit 0 without having uploaded a file.

## Installing

A `.deb` for Debian, Ubuntu and derivatives (tested on Ubuntu 18.04 to 26.04 and Debian 11 to 13; the unpacked package also runs on Rocky 8 and 9 and Fedora).
It contains its own Java runtime: no Java needs to be installed.

```bash
sudo apt install ./packaging/protonbackup_0.4.5_amd64.deb
```

Only one implementation (.NET, Python or Java) is installed at a time: remove the other one completely, including its data, first.

## Getting started

1. Start **Proton Drive backup** from the application menu (or `protonbackup-ui`).
2. The welcome screen downloads and verifies the CLI, signs you in through your browser and lets you pick a folder.
3. Turn on the timer, or use **Sync now**.

## Removing

First let the app clean up its own files in your home folder (a package may not delete them); your files on Proton Drive are never touched:

```bash
protonbackup --cleanup
```

(or Settings, Removal in the window), then:

```bash
sudo apt remove protonbackup
```

If the package was removed first, it prints these commands for removing what is left by hand (run as yourself):

```bash
systemctl --user disable --now protonbackup-sync.timer
rm -rf ~/.local/share/ProtonBackup ~/.config/ProtonBackup ~/.cache/ProtonBackup
rm -f ~/.config/systemd/user/protonbackup-sync.service ~/.config/systemd/user/protonbackup-sync@.service ~/.config/systemd/user/protonbackup-sync.timer
rm -rf ~/.config/systemd/user/protonbackup-sync.timer.d
rm -f ~/.config/systemd/user/timers.target.wants/protonbackup-sync.timer ~/.local/share/systemd/timers/stamp-protonbackup-sync.timer
rm -rf ~/.openjfx
systemctl --user daemon-reload
```

## Building and testing

```bash
mvn verify                      # all tests (the window tests need a display)
packaging/build-deb.sh          # the .deb, built in a Temurin container; needs only Docker
packaging/release-gate.sh       # everything, including the 11-distribution and removal tests (about 40 minutes)
```

Layout: `protonbackup-core` (library), `protonbackup-daemon` (console app), `protonbackup-ui` (JavaFX), `packaging/`.
Documentation: `MIGRATION_PLAN.md` (the plan and its status), `ARCHITECTURE.md`, `docs/UI_ARCHITECTURE.md`, `docs/PACKAGING.md`,
`docs/BEHAVIOR_COMPARISON.md`, `docs/SPIKES.md`, `DIFFERENCES.md`.

---

*Not affiliated with or endorsed by Proton AG.*
