package protonbackup.ui.viewmodel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import protonbackup.core.RunRecord;
import protonbackup.core.SessionState;

/** The plain-language texts the pages show, kept in one place and free of any UI so they can be tested. */
public final class Descriptions {

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd-MM HH:mm", Locale.ROOT);

    private Descriptions() {}

    /** A run's result as a person would say it. {@code null} means the run is still going. */
    public static String runResult(String result) {
        if (result == null) return "running";
        return switch (result) {
            case "ok" -> "completed";
            case "partial" -> "partly failed";
            case "cancelled" -> "cancelled";
            case "error" -> "error";
            case "already-running" -> "skipped";
            case "session-expired" -> "session expired";
            case "session-unknown" -> "session unknown";
            default -> result;
        };
    }

    public static String session(SessionState state) {
        return switch (state) {
            case ACTIVE -> "signed in";
            case EXPIRED -> "session expired";
            case UNKNOWN -> "unknown";
        };
    }

    /** {@code 29-09 22:46}, in the local time zone. */
    public static String shortTime(Instant instant, ZoneId zone) {
        return SHORT.format(instant.atZone(zone));
    }

    /** The "Last run" line of the Status page: the newest run, or {@code "no runs yet"}. */
    public static String lastRun(List<RunRecord> newestFirst, ZoneId zone) {
        if (newestFirst.isEmpty()) return "no runs yet";
        var run = newestFirst.get(0);
        if (run.finishedUtc() == null) return "started " + shortTime(run.startedUtc(), zone) + ", still running";
        return shortTime(run.finishedUtc(), zone) + " — " + run.uploaded() + " uploaded, " + run.failed() + " failed ("
                + runResult(run.result()) + ")";
    }
}
