package ru.archlabs.chat.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

/**
 * Prometheus counters for processed requests and delivered/stored notifications.
 */
public final class ChatMetrics {
    private final CollectorRegistry registry = new CollectorRegistry();
    private final Counter requests;
    private final Counter notifications;
    private final Gauge onlineUsers;

    public ChatMetrics() {
        requests =
                Counter.build()
                        .name("archlabs_requests_total")
                        .help("Number of chat requests processed")
                        .labelNames("operation")
                        .register(registry);
        notifications =
                Counter.build()
                        .name("archlabs_notifications_total")
                        .help("Number of notifications by kind")
                        .labelNames("kind")
                        .register(registry);
        onlineUsers =
                Gauge.build()
                        .name("archlabs_online_users")
                        .help("Current number of online users")
                        .register(registry);
    }

    public void requestProcessed(String operation) {
        requests.labels(operation).inc();
    }

    public void notificationDelivered(String kind, int count) {
        if (count > 0) {
            notifications.labels(kind).inc(count);
        }
    }

    public void notificationStored(int count) {
        notificationDelivered("stored", count);
    }

    public void onlineUsers(int count) {
        onlineUsers.set(count);
    }

    public double requestCount(String operation) {
        return requests.labels(operation).get();
    }

    public double notificationCount(String kind) {
        return notifications.labels(kind).get();
    }

    public double onlineUsers() {
        return onlineUsers.get();
    }

    public CollectorRegistry registry() {
        return registry;
    }
}
