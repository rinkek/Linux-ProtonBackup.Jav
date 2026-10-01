package protonbackup.core;

import java.io.IOException;
import java.util.List;

/** Runs an external program; a seam so everything that calls the Proton CLI is testable with a fake. */
@FunctionalInterface
public interface CommandRunner {

    /**
     * @throws IOException when the program cannot be started (missing, not executable)
     * @throws InterruptedException when the calling thread is interrupted; the child is killed first
     */
    CliResult run(String executable, List<String> arguments) throws IOException, InterruptedException;
}
