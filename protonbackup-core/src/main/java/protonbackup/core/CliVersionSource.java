package protonbackup.core;

/** What the update check needs to know about installed and published CLI versions. */
public interface CliVersionSource {

    /** The installed version, or {@code null} when there is no working CLI. */
    String getInstalledVersion() throws InterruptedException;

    /** The newest published release, or {@code null} when the version page could not be read. */
    CliRelease fetchRelease() throws InterruptedException;
}
