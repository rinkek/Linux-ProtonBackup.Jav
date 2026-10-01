package protonbackup.core;

import java.util.Locale;

/** Outcome of one call of an external program. */
public record CliResult(int exitCode, String stdOut, String stdErr) {

    public boolean ok() {
        return exitCode == 0;
    }

    /** Standard output, followed by standard error when there is any. */
    public String output() {
        return stdErr == null || stdErr.isBlank() ? stdOut : stdOut + stdErr;
    }

    /** The CLI reports a missing session as plain text, not as JSON. */
    public boolean notLoggedIn() {
        return outputContains("You need to login first");
    }

    public boolean alreadyExists() {
        return outputContains("already exists");
    }

    public boolean notFound() {
        return outputContains("Node not found");
    }

    private boolean outputContains(String text) {
        return output().toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }
}
