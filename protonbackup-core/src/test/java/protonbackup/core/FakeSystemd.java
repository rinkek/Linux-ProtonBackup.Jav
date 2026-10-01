package protonbackup.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Records what removal asks of systemd, in order. */
class FakeSystemd implements SystemdControl {

    final List<String> calls = new ArrayList<>();
    boolean failDisable;

    @Override
    public CliResult disableTimer() throws IOException {
        calls.add("disable");
        if (failDisable) throw new IOException("systemctl not found");
        return new CliResult(0, "", "");
    }

    @Override
    public CliResult stopSync() {
        calls.add("stop");
        return new CliResult(0, "", "");
    }

    @Override
    public CliResult reload() {
        calls.add("reload");
        return new CliResult(0, "", "");
    }
}
