package protonbackup.ui.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import protonbackup.core.AppPaths;
import protonbackup.ui.service.BackupService;

/** A {@link BackupService} on a temporary home folder, with fakes for systemctl, the CLI and the network. */
public final class Fixture implements AutoCloseable {

    public static final String VERSION_PAGE = """
            <h1>Proton Drive CLI 0.9.0</h1>
            <table><tr><td>linux/x64</td>
            <td><a href="https://proton.me/download/drive/cli/0.9.0/linux-x64/proton-drive">link</a></td></tr></table>
            """;

    public final Path home;
    public final AppPaths paths;
    public final FakeSystem system = new FakeSystem();
    public final List<String> fetched = new ArrayList<>();
    public final List<String> downloaded = new ArrayList<>();
    public String versionPage = VERSION_PAGE;
    public boolean pageFetchFails;
    public BackupService service;

    public Fixture(Path home) throws IOException {
        this.home = home;
        this.paths = AppPaths.forHome(home);
        this.service = new BackupService(paths, environment());
    }

    private BackupService.Environment environment() {
        return new BackupService.Environment(
                system,
                url -> {
                    fetched.add(url);
                    if (pageFetchFails) throw new IOException("no route to host");
                    return versionPage;
                },
                (url, destination) -> {
                    downloaded.add(url);
                    Files.writeString(destination, "#!/bin/sh\necho ok\n");
                },
                duration -> {},
                "", // no PATH: only the app's own folder is searched for the CLI
                "/usr/bin/protonbackup-ui");
    }

    /** Puts a (fake) CLI where the app looks for it and looks it up again. */
    public Fixture withCli() throws IOException {
        Files.createDirectories(paths.binDir());
        Files.writeString(paths.binDir().resolve("proton-drive"), "fake");
        service.refreshCliPath();
        return this;
    }

    @Override
    public void close() {
        service.close();
    }
}
