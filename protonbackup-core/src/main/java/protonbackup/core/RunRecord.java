package protonbackup.core;

import java.time.Instant;

/** One row of run history. {@code finishedUtc} and {@code result} are {@code null} while a run is busy. */
public record RunRecord(
        long id, Instant startedUtc, Instant finishedUtc, int uploaded, int failed, String result) {}
