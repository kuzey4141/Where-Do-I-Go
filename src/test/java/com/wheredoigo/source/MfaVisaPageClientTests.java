package com.wheredoigo.source;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaVisaPageClientTests {
    private HttpServer server;
    private URI sourceUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sourceUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/visa");
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsGetRequestAndReadsTurkishHtml() throws Exception {
        String html = "<html><body>Türk vatandaşları için vize bilgileri</body></html>";
        server.createContext("/visa", exchange -> {
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("text/html", exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        assertEquals(html, new MfaVisaPageClient(sourceUrl).fetchHtml());
    }

    @Test
    void rejectsUpstreamErrors() {
        server.createContext("/visa", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        IOException error = assertThrows(IOException.class,
                () -> new MfaVisaPageClient(sourceUrl).fetchHtml());
        assertTrue(error.getMessage().contains("503"));
    }

    @Test
    void rejectsNonHtmlResponses() {
        server.createContext("/visa", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 2);
            try (var output = exchange.getResponseBody()) {
                output.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        });

        assertThrows(IOException.class, () -> new MfaVisaPageClient(sourceUrl).fetchHtml());
    }

    @Test
    void followsRedirects() throws Exception {
        server.createContext("/visa", exchange -> {
            exchange.getResponseHeaders().set("Location", "/destination");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/destination", exchange -> {
            byte[] body = "<html>Redirected</html>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });

        assertEquals("<html>Redirected</html>", new MfaVisaPageClient(sourceUrl).fetchHtml());
    }

    @Test
    void rejectsEmptyHtml() {
        server.createContext("/visa", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        assertThrows(IOException.class, () -> new MfaVisaPageClient(sourceUrl).fetchHtml());
    }
}
