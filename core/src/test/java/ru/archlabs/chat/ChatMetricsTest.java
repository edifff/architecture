package ru.archlabs.chat;

import org.junit.jupiter.api.Test;
import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatMetricsTest {
    @Test
    void countsRequestsNotificationsAndOnlineUsers() {
        try (var server = new ChatServerImpl()) {
            var alice = server.connect("alice");
            var bob = server.connect("bob");

            bob.send("hello");

            var metrics = server.metrics();
            assertEquals(2, metrics.requestCount("connect"));
            assertEquals(1, metrics.requestCount("send"));
            assertEquals(2, metrics.notificationCount("live"));
            assertEquals(2, metrics.onlineUsers());

            alice.close();
            assertEquals(1, metrics.onlineUsers());
            assertEquals(1, metrics.requestCount("disconnect"));
        }
    }

    @Test
    void exposesPrometheusTextFormat() throws Exception {
        try (var server = new ChatServerImpl();
                var metricsServer = new MetricsHttpServer(server.metrics(), 0)) {
            server.connect("alice");
            metricsServer.start();

            var request =
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + metricsServer.port() + "/metrics"))
                            .GET()
                            .build();
            var response =
                    HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("archlabs_requests_total"));
            assertTrue(response.body().contains("archlabs_online_users"));
        }
    }
}
