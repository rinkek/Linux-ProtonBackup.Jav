package protonbackup.core;

import java.io.IOException;
import java.time.Duration;

/** A program did not finish within the allowed time and was killed. */
public final class ProcessTimeoutException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProcessTimeoutException(String executable, Duration timeout) {
        super(executable + " did not finish within " + timeout.toSeconds() + " s and was killed");
    }
}
