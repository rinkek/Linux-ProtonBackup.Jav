package protonbackup.core;

import java.io.IOException;

/** The part of the systemd manager that removal of the app needs. */
public interface SystemdControl {

    CliResult disableTimer() throws IOException, InterruptedException;

    CliResult stopSync() throws IOException, InterruptedException;

    CliResult reload() throws IOException, InterruptedException;
}
