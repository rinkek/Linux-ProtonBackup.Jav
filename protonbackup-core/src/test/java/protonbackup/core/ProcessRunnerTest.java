package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uses real system programs, not mocks: the behaviors that matter (stdin closed, both output
 * streams drained) only mean something against a real child process.
 */
@Timeout(60)
class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesExitCodeStdoutAndStderr() throws Exception {
        var result = runner.run("/bin/sh", List.of("-c", "echo out; echo err >&2; exit 3"));

        assertEquals(3, result.exitCode());
        assertEquals("out\n", result.stdOut());
        assertEquals("err\n", result.stdErr());
        assertFalse(result.ok());
    }

    @Test
    void exitCodeZeroIsOk() throws Exception {
        assertTrue(runner.run("/bin/sh", List.of("-c", "exit 0")).ok());
    }

    /** {@code cat} with no input stands in for "a process that blocks on stdin", as the CLI's conflict prompt does. */
    @Test
    void stdinIsClosedSoAReadingProcessExitsInsteadOfHanging() throws Exception {
        var result = runner.run("/bin/cat", List.of(), Duration.ofSeconds(10));

        assertTrue(result.ok());
        assertEquals("", result.stdOut());
    }

    /** More than a pipe buffer (64 KiB) on both streams: fails by deadlock if either is not drained. */
    @Test
    void aChildThatWritesALotToBothStreamsDoesNotBlock() throws Exception {
        var script = "i=0; while [ $i -lt 400 ]; do printf '%0512d\\n' 0 >&2; printf '%0512d\\n' 1; i=$((i+1)); done";

        var result = runner.run("/bin/sh", List.of("-c", script), Duration.ofSeconds(30));

        assertTrue(result.ok());
        assertEquals(400 * 513, result.stdOut().length());
        assertEquals(400 * 513, result.stdErr().length());
    }

    @Test
    void outputIsDecodedAsUtf8WhateverTheLocale() throws Exception {
        var result = runner.run("/bin/sh", List.of("-c", "printf 'caf\\303\\251 \\346\\227\\245\\346\\234\\254'"));

        assertEquals("café 日本", result.stdOut());
    }

    @Test
    void argumentsAreNotInterpretedByAShell() throws Exception {
        var result = runner.run("/bin/echo", List.of("a b", "$HOME", "*", "x;y"));

        assertEquals("a b $HOME * x;y\n", result.stdOut());
    }

    @Test
    void aMissingExecutableIsAnIoException() {
        assertThrows(IOException.class, () -> runner.run("/does/not/exist/at/all", List.of()));
    }

    @Test
    void aTimeoutKillsTheChildAndItsChildren(@TempDir Path directory) throws Exception {
        var marker = directory.resolve("survivor");
        // The shell starts a grandchild that would create the marker file after 3 s if it survived.
        var script = "(sleep 3; touch " + marker + ") & sleep 30";

        assertThrows(ProcessTimeoutException.class,
                () -> runner.run("/bin/sh", List.of("-c", script), Duration.ofMillis(300)));

        Thread.sleep(3500);
        assertFalse(Files.exists(marker), "the grandchild must have been killed too");
    }

    @Test
    void interruptingTheCallerKillsTheChild() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var started = System.nanoTime();
        var thread = new Thread(() -> {
            try {
                runner.run("/bin/sleep", List.of("30"));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        Thread.sleep(300);

        thread.interrupt();
        thread.join(10_000);

        assertFalse(thread.isAlive());
        assertTrue(failure.get() instanceof InterruptedException, String.valueOf(failure.get()));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 20);
        // No sleep process from this test may remain: nothing to assert portably beyond a prompt return.
    }

    @Test
    void theEnvironmentIsPassedOnUnchanged() throws Exception {
        var result = runner.run("/bin/sh", List.of("-c", "printf %s \"$PATH\""));

        assertEquals(System.getenv("PATH"), result.stdOut());
    }

    @Test
    void aGrandchildHoldingThePipesOpenDoesNotHangTheCall() throws Exception {
        // The shell exits at once; the background sleep keeps inheriting stdout for 20 s.
        var started = System.nanoTime();

        var result = runner.run("/bin/sh", List.of("-c", "echo hi; sleep 20 &"), Duration.ofSeconds(30));

        assertEquals("hi\n", result.stdOut());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 15, "must give up waiting for the pipes");
    }
}
