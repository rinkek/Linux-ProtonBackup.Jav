package protonbackup.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Consumer;

/** Checks daily whether a newer CLI is available. A failed check is only logged and must never block a sync. */
public final class CliUpdateCheck {

    /** Result of a check; {@code dismissed} is true when the user already said "later" for this version. */
    public record UpdateStatus(String installed, String available, boolean updateAvailable, boolean dismissed) {}

    public static final Duration INTERVAL = Duration.ofDays(1);

    private final SettingsStore store;
    private final CliSettings settings;
    private final CliVersionSource source;
    private final Consumer<String> log;
    private final Clock clock;

    public CliUpdateCheck(SettingsStore store, CliVersionSource source, Consumer<String> log) {
        this(store, source, log, Clock.systemUTC());
    }

    public CliUpdateCheck(SettingsStore store, CliVersionSource source, Consumer<String> log, Clock clock) {
        this.store = store;
        this.settings = new CliSettings(store);
        this.source = source;
        this.log = log;
        this.clock = clock;
    }

    /** True when never checked, when the last check is a day old, or when the stored time is unreadable. */
    public boolean isDue() {
        var last = store.getSetting("cli_last_check_utc");
        if (last == null) return true;
        try {
            return Duration.between(Instant.parse(last), clock.instant()).compareTo(INTERVAL) >= 0;
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    /** The status, or {@code null} when no check was due or the version page could not be read. */
    public UpdateStatus check(boolean force) throws InterruptedException {
        if (!force && !isDue()) return null;

        var installed = source.getInstalledVersion();
        var release = source.fetchRelease();
        store.setSetting("cli_last_check_utc", clock.instant().toString());

        if (release == null) return null;
        settings.setLastSeenVersion(release.version());

        var newer = installed != null && CliVersionPage.isNewer(release.version(), installed);
        if (newer) {
            log.accept("A newer Proton Drive CLI is available: " + release.version() + " (currently " + installed + ").");
        }
        return new UpdateStatus(installed, release.version(), newer, newer && release.version().equals(settings.dismissedVersion()));
    }

    public void dismiss(String version) {
        settings.setDismissedVersion(version);
    }
}
