package ru.archlabs.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ChatMetricsTest {
    private final ChatMetrics metrics = new ChatMetrics();

    @Test
    void countsProcessedRequestsPerOperation() {
        metrics.requestProcessed("connect");
        metrics.requestProcessed("connect");
        metrics.requestProcessed("send");

        var scrape = metrics.scrape();
        assertTrue(scrape.contains("chat_requests_total{operation=\"connect\"} 2.0"), scrape);
        assertTrue(scrape.contains("chat_requests_total{operation=\"send\"} 1.0"), scrape);
    }

    @Test
    void countsDeliveredNotificationsPerChannel() {
        metrics.notificationsDelivered("live", 3);
        metrics.notificationsDelivered("backlog", 2);

        var scrape = metrics.scrape();
        assertTrue(scrape.contains("chat_notifications_total{channel=\"live\"} 3.0"), scrape);
        assertTrue(scrape.contains("chat_notifications_total{channel=\"backlog\"} 2.0"), scrape);
    }

    @Test
    void ignoresNonPositiveNotificationCounts() {
        metrics.notificationsDelivered("live", 0);
        metrics.notificationsDelivered("backlog", -1);

        var scrape = metrics.scrape();
        assertFalse(scrape.contains("chat_notifications_total{"), scrape);
    }

    @Test
    void scrapeDescribesMetricsForPrometheus() {
        metrics.requestProcessed("connect");
        metrics.notificationsDelivered("live", 1);

        var scrape = metrics.scrape();
        assertTrue(scrape.contains("# HELP chat_requests_total Processed user requests"), scrape);
        assertTrue(scrape.contains("# TYPE chat_requests_total counter"), scrape);
        assertTrue(
                scrape.contains("# HELP chat_notifications_total Notifications delivered to users"),
                scrape);
        assertTrue(scrape.contains("# TYPE chat_notifications_total counter"), scrape);
    }

    @Test
    void scrapeWithoutRequestsContainsNoSamples() {
        var scrape = metrics.scrape();

        assertFalse(scrape.contains("chat_requests_total{"), scrape);
        assertFalse(scrape.contains("chat_notifications_total{"), scrape);
    }

    @Test
    void instancesDoNotShareCounters() {
        var other = new ChatMetrics();
        metrics.requestProcessed("connect");
        other.requestProcessed("send");

        assertTrue(metrics.scrape().contains("chat_requests_total{operation=\"connect\"} 1.0"));
        assertFalse(metrics.scrape().contains("operation=\"send\""));
        assertFalse(other.scrape().contains("operation=\"connect\""));
    }
}
