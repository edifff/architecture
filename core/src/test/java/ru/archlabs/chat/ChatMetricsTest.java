package ru.archlabs.chat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import ru.archlabs.chat.metrics.ChatMetrics;
import ru.archlabs.chat.metrics.MetricsHttpServer;
import ru.archlabs.chat.service.ChatServerImpl;


final class ChatMetricsTest {
    @Test
    void countsRequestsNotificationsAndOnlineUsers() {
        try (ChatServerImpl server = new ChatServerImpl()) {
            ChatSession alice = server.connect("alice");
            ChatSession bob = server.connect("bob");

            bob.send("hello");

            ChatMetrics metrics = server.metrics();
            Assertions.assertEquals(2, metrics.requestCount("connect"));
            Assertions.assertEquals(1, metrics.requestCount("send"));
            Assertions.assertEquals(2, metrics.notificationCount("live"));
            Assertions.assertEquals(2, metrics.onlineUsers());

            alice.close();
            Assertions.assertEquals(1, metrics.onlineUsers());
            Assertions.assertEquals(1, metrics.requestCount("disconnect"));
        }
    }

    @Test
    void exposesPrometheusTextFormat() throws Exception {
        try (ChatServerImpl server = new ChatServerImpl();
                MetricsHttpServer metricsServer = new MetricsHttpServer(server.metrics(), 0)) {
            server.connect("alice");
            metricsServer.start();

            HttpRequest request =
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + metricsServer.port() + "/metrics"))
                            .GET()
                            .build();
            HttpResponse<String> response =
                    HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofString());

            Assertions.assertEquals(200, response.statusCode());
            Assertions.assertTrue(response.body().contains("archlabs_requests_total"));
            Assertions.assertTrue(response.body().contains("archlabs_online_users"));
        }
    }
}
