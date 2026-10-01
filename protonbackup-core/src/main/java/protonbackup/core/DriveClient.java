package protonbackup.core;

import java.io.IOException;
import java.util.List;

/** What the sync engine needs from the Proton CLI; implemented by {@link ProtonDriveCli} and by test fakes. */
public interface DriveClient {

    SessionState checkSession() throws IOException, InterruptedException;

    /** Creates a remote folder and any missing parents, top to bottom. */
    void ensureFolder(String remotePath) throws IOException, InterruptedException;

    /** Uploads files into one remote folder. The exit code alone is no proof of success. */
    CliResult upload(List<String> localPaths, String remoteParent) throws IOException, InterruptedException;

    /** The folder's entries, or {@code null} when the listing could not be fetched. */
    List<RemoteNode> list(String remotePath) throws IOException, InterruptedException;
}
