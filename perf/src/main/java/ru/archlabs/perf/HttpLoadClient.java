package ru.archlabs.perf;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small HTTP load client for the api container.
 */
public final class HttpLoadClient {
    private HttpLoadClient() {
    }

    public static void main(String[] args) throws Exception {
        String base = "http://localhost:8080";
        int users = 10;
        int messages = 20;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--base" -> base = args[++i];
                case "--users" -> users = Integer.parseInt(args[++i]);
                case "--messages" -> messages = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
        }

        HttpClient client =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        String run = Long.toHexString(System.nanoTime());
        ArrayList<String> names = new ArrayList<String>();
        for (int i = 0; i < users; i++) {
            names.add("load-" + run + "-" + i);
        }
        for (String name : names) {
            post(client, base + "/sessions/" + name, "");
        }

        AtomicInteger errors = new AtomicInteger();
        long started = System.nanoTime();
        String endpoint = base;
        int perUser = messages;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String name : names) {
                executor.submit(
                        () -> {
                            try {
                                for (int i = 0; i < perUser; i++) {
                                    HttpResponse<String> response =
                                            post(
                                                    client,
                                                    endpoint + "/messages/" + name,
                                                    "{\"text\":\"load-" + name + "-" + i + "\"}");
                                    if (response.statusCode() != 201) {
                                        errors.incrementAndGet();
                                    }
                                }
                            } catch (Exception exception) {
                                errors.incrementAndGet();
                            }
                        });
            }
        }
        long elapsed = System.nanoTime() - started;
        int total = users * messages;
        double seconds = elapsed / 1_000_000_000.0;
        System.out.printf(
                "users=%d messages=%d total=%d duration_ms=%d rps=%.1f errors=%d%n",
                users, messages, total, elapsed / 1_000_000, total / seconds, errors.get());

        for (String name : names) {
            delete(client, base + "/sessions/" + name);
        }
    }

    private static HttpResponse<String> post(HttpClient client, String url, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void delete(HttpClient client, String url) throws Exception {
        client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }
}
