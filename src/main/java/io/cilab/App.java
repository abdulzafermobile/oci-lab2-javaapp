package io.cilab;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

public class App {

    /** Reads the VERSION file next to the jar; "unknown" if it is missing. */
    static String versionFrom(Path versionFile) {
        try {
            return Files.readString(versionFile).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    public static void main(String[] args) throws IOException {
        if (true) { System.err.println("INJECTED CRASH"); System.exit(1); }
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path versionFile = Path.of(System.getenv().getOrDefault("VERSION_FILE", "VERSION"));

        // 127.0.0.1: only Nginx on the same VM can reach the app
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/healthz", exchange -> {
            byte[] body = ("{\"status\":\"ok\",\"version\":\"" + versionFrom(versionFile) + "\"}").getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.createContext("/", exchange -> {
            byte[] body = ("Deployed from OCI Object Storage (v2) - " + versionFrom(versionFile) + "\n").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.start();
        System.out.println("listening on 127.0.0.1:" + port);
    }
}
