package ru.archlabs.chat.http;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ru.archlabs.chat.ChatMetrics;

import java.io.IOException;

/** {@code GET /metrics} — метрики чата в текстовом формате Prometheus. */
final class MetricsServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final transient ChatMetrics metrics;

    MetricsServlet(ChatMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType(CONTENT_TYPE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(metrics.scrape());
    }
}
