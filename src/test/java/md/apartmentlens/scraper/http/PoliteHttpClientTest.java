package md.apartmentlens.scraper.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PoliteHttpClientTest {

    HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> {
            byte[] body = "hello".getBytes();
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        // Sends the headers and part of the body, then stalls.
        server.createContext("/stall", ex -> {
            ex.sendResponseHeaders(200, 1000);
            OutputStream out = ex.getResponseBody();
            out.write(new byte[10]);
            out.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void aResponseIsRead() throws Exception {
        PoliteHttpClient http = new PoliteHttpClient("test", Duration.ZERO, 1, Duration.ofSeconds(5));
        assertThat(new String(http.get(uri("/ok"), Map.of()).body())).isEqualTo("hello");
    }

    @Test
    void aServerThatStallsWhileSendingTheBodyTimesOut() {
        PoliteHttpClient http = new PoliteHttpClient("test", Duration.ZERO, 1, Duration.ofSeconds(1));
        long start = System.nanoTime();
        assertThatThrownBy(() -> http.get(uri("/stall"), Map.of())).isInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }
}
