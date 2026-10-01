package protonbackup.core;

import java.time.Duration;

/** Waiting, as a seam: retry logic is tested without real delays. */
@FunctionalInterface
public interface Sleeper {

    Sleeper SYSTEM = duration -> Thread.sleep(duration.toMillis());

    void sleep(Duration duration) throws InterruptedException;
}
