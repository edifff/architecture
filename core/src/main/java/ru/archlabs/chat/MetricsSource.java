package ru.archlabs.chat;

/**
 * Источник метрик: реализуется серверной логикой, чтобы транспортный слой мог
 * получить тот же экземпляр ChatMetrics и отдать его на GET /metrics.
 */
public interface MetricsSource {
    ChatMetrics metrics();
}
