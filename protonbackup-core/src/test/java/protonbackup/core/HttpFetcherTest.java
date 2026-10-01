package protonbackup.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The installer's download path against a local server: redirects, status codes, binary bodies. */
class HttpFetcherTest {

    private HttpServer server;
    private String base;
    private final byte[] binary = new byte[300_000];

    @BeforeEach
    void start() throws IOException {
        for (var i = 0; i < binary.length; i++) binary[i] = (byte) (i * 31);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/page", exchange -> respond(exchange, 200, "<h1>Proton Drive CLI 0.8.0</h1> café".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/page");
            respond(exchange, 302, new byte[0]);
        });
        server.createContext("/bin", exchange -> respond(exchange, 200, binary));
        server.createContext("/missing", exchange -> respond(exchange, 404, "no".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/error", exchange -> respond(exchange, 503, "busy".getBytes(StandardCharsets.UTF_8)));
        server.start();
        base = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
        exchange.close();
    }

    /**
     * The daemon builds a fetcher for every command, and most commands (every timer run but one a day)
     * never go to the network. A client costs about 110 ms to build and its selector thread makes the JVM
     * wait 300 ms longer on exit: measured on the packaged daemon, 540 ms against 140 ms.
     */
    @Test
    void noClientIsBuiltUntilTheFirstRequest() throws Exception {
        var fetcher = new HttpFetcher();
        assertFalse(fetcher.clientBuilt(), "constructing a fetcher must not build an HTTP client");

        fetcher.fetchText(base + "/page");
        assertTrue(fetcher.clientBuilt(), "the first request builds it");
    }

    @Test
    void fetchesATextPageAsUtf8() throws Exception {
        assertEquals("<h1>Proton Drive CLI 0.8.0</h1> café", new HttpFetcher().fetchText(base + "/page"));
    }

    @Test
    void followsRedirects() throws Exception {
        assertEquals("<h1>Proton Drive CLI 0.8.0</h1> café", new HttpFetcher().fetchText(base + "/moved"));
    }

    @Test
    void downloadsABinaryBodyUnchanged(@TempDir Path directory) throws Exception {
        var target = directory.resolve("proton-drive");

        new HttpFetcher().downloadTo(base + "/bin", target);

        assertArrayEquals(binary, Files.readAllBytes(target));
    }

    @Test
    void aNon2xxStatusIsAnIoExceptionForTextAndDownloads(@TempDir Path directory) {
        var fetcher = new HttpFetcher();

        var page = assertThrows(IOException.class, () -> fetcher.fetchText(base + "/missing"));
        var download = assertThrows(IOException.class, () -> fetcher.downloadTo(base + "/error", directory.resolve("x")));

        assertTrue(page.getMessage().contains("404"));
        assertTrue(download.getMessage().contains("503"));
    }

    @Test
    void aRefusedConnectionIsAnIoException() {
        server.stop(0);

        assertThrows(IOException.class, () -> new HttpFetcher().fetchText(base + "/page"));
    }
}
