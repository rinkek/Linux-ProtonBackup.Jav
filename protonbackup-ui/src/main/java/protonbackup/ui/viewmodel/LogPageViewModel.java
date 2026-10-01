package protonbackup.ui.viewmodel;

import java.time.ZoneId;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import protonbackup.core.FileEntry;
import protonbackup.core.RunRecord;
import protonbackup.ui.mvvm.Background;
import protonbackup.ui.mvvm.Background.Lane;
import protonbackup.ui.mvvm.PageViewModel;
import protonbackup.ui.mvvm.Refresher;
import protonbackup.ui.service.BackupService;

/** The recent sync runs and the files that currently have an error, with the reason for each. */
public final class LogPageViewModel extends PageViewModel {

    /** One row of the run history, already worded for the screen. */
    public record RunRow(long id, String started, String outcome, int uploaded, int failed) {

        static RunRow of(RunRecord run, ZoneId zone) {
            return new RunRow(run.id(), Descriptions.shortTime(run.startedUtc(), zone), Descriptions.runResult(run.result()), run.uploaded(), run.failed());
        }

        /** {@code 3 uploaded, 0 failed}. */
        public String counts() {
            return uploaded + " uploaded, " + failed + " failed";
        }
    }

    private record Snapshot(List<RunRecord> runs, List<FileEntry> failures) {}

    private static final int RUNS_SHOWN = 20;
    private static final int FAILURES_SHOWN = 200;

    private final ZoneId zone;
    private final ObservableList<RunRow> runs = FXCollections.observableArrayList();
    private final ObservableList<FileEntry> failures = FXCollections.observableArrayList();
    private final Refresher<Snapshot> reload = refresher(Lane.FAST, this::load, this::show);

    public LogPageViewModel(BackupService service, Background background) {
        this(service, background, ZoneId.systemDefault());
    }

    public LogPageViewModel(BackupService service, Background background, ZoneId zone) {
        super(service, background);
        this.zone = zone;
    }

    @Override
    public String title() {
        return "Log";
    }

    public ObservableList<RunRow> runs() {
        return runs;
    }

    public ObservableList<FileEntry> failures() {
        return failures;
    }

    @Override
    public void refresh() {
        reload.refresh();
    }

    private Snapshot load() {
        return new Snapshot(service.database().getRecentRuns(RUNS_SHOWN), service.database().getFailedFiles(FAILURES_SHOWN));
    }

    /** The lists are only touched when they differ, so a selection or a scroll position is not lost every two seconds. */
    private void show(Snapshot snapshot) {
        var rows = snapshot.runs().stream().map(run -> RunRow.of(run, zone)).toList();
        if (!rows.equals(runs)) runs.setAll(rows);
        if (!snapshot.failures().equals(failures)) failures.setAll(snapshot.failures());
    }
}
