package protonbackup.core;

/**
 * Names of the systemd units the app installs. The defaults are what the package ships; tests that
 * talk to the real systemd use other names so they can never touch the user's own units.
 */
public record UnitNames(String service, String timer, String sourceTemplate) {

    public static final UnitNames DEFAULT = withBase("protonbackup-sync");

    /** {@code base.service}, {@code base.timer} and {@code base@.service}. */
    public static UnitNames withBase(String base) {
        return new UnitNames(base + ".service", base + ".timer", base + "@.service");
    }

    /** The service run for one source, e.g. {@code protonbackup-sync@7.service}. */
    public String sourceService(long sourceId) {
        var base = sourceTemplate.substring(0, sourceTemplate.length() - "@.service".length());
        return base + "@" + sourceId + ".service";
    }

    /** The timer's drop-in folder, where the interval lives. */
    public String timerDropInDir() {
        return timer + ".d";
    }

    /** Where {@code systemctl --user enable} links the timer (the unit's {@code WantedBy=timers.target}). */
    public String timerWantsDir() {
        return "timers.target.wants";
    }

    /** The stamp file systemd keeps for the timer in {@code ~/.local/share/systemd/timers}. */
    public String timerStamp() {
        return "stamp-" + timer;
    }
}
