# Netty HTTP Server Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Jetty+servlet HTTP server with a raw Netty server while keeping the wire protocol byte-identical, so the client and all existing tests pass unchanged.

**Architecture:** One `ServerBootstrap` (NIO) with a single routing handler (`ChatServerHandler`) that dispatches `FullHttpRequest` by method+path. JSON endpoints respond with `DefaultFullHttpResponse`; the SSE endpoint streams chunked responses fed by a scheduled poll of `ClientConnection.pending()` on the channel's event loop. The domain layer (`ChatServer`/`ChatSession`/storage) is untouched; `ChatProtocol`, `ChatJson`, `ClientConnection` are reused as-is.

**Tech Stack:** Java 25, Netty 4.2.19.Final (`io.netty:netty-codec-http`), Jackson (existing), JUnit 5 + Testcontainers (existing).

**Spec:** No spec document (user waived it). Design decisions agreed in chat: server-side only, client unchanged, raw Netty (no framework), non-blocking SSE via event-loop-scheduled polling.

## Global Constraints

- The wire protocol must stay byte-identical: same routes, status codes, JSON payloads (`ChatProtocol` records), SSE framing (`event: message\ndata: {...}\n\n`, `: connected\n\n` initial, `: keep-alive\n\n` idle), content types (`application/json`, `text/event-stream`, `text/plain; version=0.0.4; charset=utf-8`).
- Public surface of `ChatHttpServer` stays: `ChatHttpServer(ChatServer chat, int port)`, `void start() throws Exception`, `URI baseUri()`, `void close()`, plus package-private `connect(String)`, `disconnect(String)`, `connection(String)`, `user(String)` used by the handler.
- No changes to `src/test/java` existing tests and no changes to `HttpChatClient`/`HttpChatSession`. Only *additions* of new test methods are allowed.
- Compiler flags stay `-Xlint:all,-this-escape -Werror`: new code must compile warning-free.
- Netty version: exactly `4.2.19.Final`, single dependency `io.netty:netty-codec-http` (transitively brings transport/buffer/codec/handler). No other new dependencies.
- `jetty-ee10-servlet`, `slf4j-api`, `slf4j-simple` are removed from `pom.xml` when the last Jetty class is deleted. Prometheus, Jackson, Flyway, PostgreSQL driver stay.
- Status-code mapping preserved from `ChatServlet.execute`: `IllegalStateException` → 409, `IllegalArgumentException` → 400, other `RuntimeException` → 500, body `{"error": "<message>"}` (`ChatProtocol.ErrorPayload`).
- README is in Russian; keep it Russian.

## Review Focus

Conditions the existing tests do NOT pin, most likely to bite first:

1. **SSE keep-alive on idle stream** — an idle connected user's stream must emit `: keep-alive` after ~15s so proxies don't kill it. Pinned by `HttpChatProtocolTest.eventsStreamSendsKeepAliveWhenIdle` (Task 2, Step 2).
2. **SSE task leak on client disconnect** — when the SSE client goes away, the scheduled poll must be cancelled (`channelInactive`), otherwise the event loop accumulates tasks forever. Pinned by `HttpChatProtocolTest.eventsStreamCancellationDoesNotThrow` (Task 2, Step 2); review must confirm `future.cancel(false)` in `channelInactive`.
3. **Unknown route/method → 404, not a hang or 500** — e.g. `GET /sessions/ivan` (no handler for that method on that path). Pinned by `HttpChatProtocolTest.unknownRouteReturnsNotFound` (Task 2, Step 2).
4. **Blank username → 400 with JSON error** — `GET /users/` must return 400 `{"error": "Username must not be blank"}`, same as the servlets. Pinned by `HttpChatProtocolTest.blankUsernameReturnsBadRequest` (Task 2, Step 2).
5. **`close()` does not hang with an open SSE stream** — `ChatApplication`'s SIGTERM path and test `tearDown` call `close()` while SSE connections may be live; graceful shutdown must close the server channel, shut down both event loop groups, then `chat.close()`. Verified by existing `HttpChatProtocolTest.tearDown` running after the SSE tests each iteration (Task 2, Step 5).

---

### Task 1: Add Netty dependency

**Files:**
- Modify: `pom.xml`

**Interfaces:**
- Consumes: nothing
- Produces: `io.netty:netty-codec-http:${netty.version}` on the compile classpath (Task 2 builds on it). Jetty deps remain until Task 2 removes them.

- [ ] **Step 1: Add Netty version property and dependency**

In `pom.xml` `<properties>` add:

```xml
<netty.version>4.2.19.Final</netty.version>
```

In `<dependencies>`, before the Jetty dependency, add (comment in Russian, style-matched to neighbors):

```xml
<dependency>
    <!-- Сервер HTTP на Netty: codec-http даёт HttpRequest/Response,
         агрегацию тел и Server-Sent Events поверх chunked-кодирования. -->
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-http</artifactId>
    <version>${netty.version}</version>
</dependency>
```

- [ ] **Step 2: Verify compilation**

Run: `mvn -q compile`
Expected: success, no output (warnings-as-errors must not fire).

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add netty-codec-http dependency"
```

---

### Task 2: Rewrite the server on Netty, delete servlets

**Files:**
- Rewrite: `src/main/java/ru/archlabs/chat/http/ChatHttpServer.java`
- Create: `src/main/java/ru/archlabs/chat/http/ChatServerHandler.java`
- Delete: `src/main/java/ru/archlabs/chat/http/ChatServlet.java`, `SessionsServlet.java`, `UserServlet.java`, `MessagesServlet.java`, `NotificationsServlet.java`, `EventsServlet.java`, `MetricsServlet.java`
- Modify: `pom.xml` (remove `jetty-ee10-servlet`, `slf4j-api`, `slf4j-simple` + `jetty.version`/`slf4j.version` properties)
- Test: add methods to `src/test/java/ru/archlabs/chat/HttpChatProtocolTest.java` (existing class, existing helpers `get`/`post`/`put`/`delete`/`openEvents`)

**Interfaces:**
- Consumes: `ChatHttpServer` package-private methods `connect(String): ClientConnection` (throws `IllegalStateException` on duplicate), `disconnect(String)` (throws `IllegalArgumentException` on unknown), `connection(String): ClientConnection` (nullable), `user(String): UserState` (throws `IllegalArgumentException` on unknown); `ChatServer.metrics()` via existing `ChatMetrics.Source` pattern already in `ChatHttpServer`; `ClientConnection.pending(): BlockingQueue<Message>`, `ClientConnection.session(): ChatSession`; `ChatProtocol` constants/records; `ChatJson.read(reader, type)` / `ChatJson.read(string, type)` / `ChatJson.write(object)`.
- Produces: unchanged public `ChatHttpServer` surface for `ChatApplication`, `HttpChatServerTest`, `HttpChatProtocolTest`. New class `ChatServerHandler` is package-private, constructed per-channel as `new ChatServerHandler(ChatHttpServer server)`.

- [ ] **Step 1: Add the four new protocol tests**

Add to `HttpChatProtocolTest` (reusing the class's private helpers; assertions exact):

```java
@Test
void unknownRouteReturnsNotFound() throws Exception {
    assertEquals(404, get("/nonsense").statusCode());
}

@Test
void blankUsernameReturnsBadRequest() throws Exception {
    var response = get("/users/");
    assertEquals(400, response.statusCode());
    assertEquals(
            "Username must not be blank",
            ChatJson.read(response.body(), ChatProtocol.ErrorPayload.class).error());
}

@Test
void eventsStreamSendsKeepAliveWhenIdle() throws Exception {
    post("/sessions/ivan", "");

    var request =
            HttpRequest.newBuilder(base.resolve("/events/ivan"))
                    .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                    .GET()
                    .build();
    var lines = client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
    try {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var reader = executor.submit(
                    () -> lines.filter(line -> line.equals(": keep-alive")).findFirst());
            assertTrue(reader.get(16, TimeUnit.SECONDS).isPresent(), "expected : keep-alive within 16s");
        } finally {
            executor.shutdownNow();
        }
    } finally {
        lines.close();
    }
}

@Test
void eventsStreamCancellationDoesNotThrow() throws Exception {
    post("/sessions/ivan", "");

    var request =
            HttpRequest.newBuilder(base.resolve("/events/ivan"))
                    .header("Accept", ChatProtocol.CONTENT_TYPE_EVENT_STREAM)
                    .GET()
                    .build();
    var stream = client.send(request, HttpResponse.BodyHandlers.ofLines()).body();
    assertTrue(stream.findFirst().isPresent(), "expected the initial : connected comment");
    stream.close();

    // Сервер не падает и отвечает на следующий запрос после обрыва SSE-клиента.
    assertEquals(200, get("/users/ivan").statusCode());
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `mvn test -Dtest=HttpChatProtocolTest`
Expected: the four new test methods must appear in the run output and compile cleanly. PASS or FAIL both acceptable here (the Jetty implementation may coincidentally satisfy some of them); this step only proves the tests are wired in. The green gate is Task 2 Step 6.

- [ ] **Step 3: Implement `ChatServerHandler`**

New file `src/main/java/ru/archlabs/chat/http/ChatServerHandler.java`, package-private, `extends SimpleChannelInboundHandler<FullHttpRequest>`. Body outline the implementer follows; routing by `request.method()` + path prefix:

- Path parsing: `URI.create(request.uri()).getPath()`; segments after the first `/` — `/{section}/{username}` where section ∈ sessions|users|messages|notifications|events; `/metrics` exact.
- Blank username (missing/empty segment) → 400 `ErrorPayload("Username must not be blank")` — mirror the old `ChatServlet.username()` logic exactly.
- Dispatch table:
  - `POST /sessions/{u}` → `server.connect(u)`, 201 `SessionPayload(u)`; duplicate → `IllegalStateException` → 409.
  - `DELETE /sessions/{u}` → `server.disconnect(u)`, 204, empty body, `Content-Length: 0`.
  - `GET /users/{u}` → 200 `UserPayload.from(server.user(u))`; `IllegalArgumentException` → 404 (this is the one route where IAE maps to 404, as in `UserServlet`).
  - `POST /messages/{u}` → session lookup (`connection == null` → `IllegalStateException("Session is not active: " + u)` → 409), body `SendMessageRequest`, `session().send(text)`, 201 `MessagePayload`.
  - `GET /messages/{u}` → session lookup, `session().history()`, 200 list of `MessagePayload`.
  - `PUT /notifications/{u}` → session lookup, body `NotificationsRequest`, `session().setNotificationsEnabled(enabled)`, 204.
  - `GET /metrics` → 200, `Content-Type: text/plain; version=0.0.4; charset=utf-8`, body `metrics.scrape()`.
  - `GET /events/{u}` → SSE, see below.
  - anything else → 404 `ErrorPayload("Unknown route: " + method + " " + path)`.
- Error mapping helper (mirror of `ChatServlet.execute`): `IllegalStateException` → 409, `IllegalArgumentException` → 400, other `RuntimeException` → 500, JSON body `ErrorPayload(message)`. The `GET /users/{u}` route catches `IllegalArgumentException` itself before the generic mapping (404).
- JSON responses: `DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.copiedBuffer(ChatJson.write(payload), UTF_8))`, header `Content-Type: application/json`, `Content-Length` = readable bytes. 204 responses: empty content, `Content-Length: 0`.
- SSE (`GET /events/{u}`):
  - `connection == null` → 404 `ErrorPayload("Unknown user: " + u)`.
  - Write head: `DefaultHttpResponse(HTTP_1_1, OK)` with `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `Transfer-Encoding: chunked`; flush `": connected\n\n"` as `DefaultHttpContent`.
  - Then `ScheduledFuture<?> poll = ctx.executor().scheduleWithFixedDelay(...)` (initial 0ms, delay 100ms): each tick drains `connection.pending()` (poll until null) writing `event: message\ndata: <json>\n\n` per message as `DefaultHttpContent` via `ctx.writeAndFlush`; if nothing drained and `System.nanoTime() - lastWriteNanos >= 15s`, write `": keep-alive\n\n"` and update `lastWriteNanos` (init to now at stream start).
  - Store the `ScheduledFuture` in a field; override `channelInactive` to `future.cancel(false)` before `super.channelInactive(ctx)`.
  - Writes are already on the channel's event loop (the task runs there), so no cross-thread write is needed.
- `exceptionCaught`: log via `logger` and `ctx.close()` (never propagate up and kill the worker).

- [ ] **Step 4: Rewrite `ChatHttpServer` on Netty**

Same class name/package; keep public surface and the `connect`/`disconnect`/`connection`/`user` methods verbatim (they are already transport-neutral). Replace Jetty internals:

```java
private final ServerBootstrap bootstrap;
private final NioEventLoopGroup bossGroup;    // 1 thread
private final NioEventLoopGroup workerGroup;  // default threads
private Channel serverChannel;
```

- Constructor: keep `chat`/`metrics`/`connections` fields as-is; build `bootstrap` with `group(bossGroup, workerGroup)`, `channel(NioServerSocketChannel.class)`, and

```java
bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
    @Override
    protected void initChannel(SocketChannel channel) {
        channel.pipeline().addLast(
                new HttpServerCodec(),
                new HttpObjectAggregator(1 << 20),
                new ChatServerHandler(ChatHttpServer.this));
    }
});
```
- `start()`: `serverChannel = bootstrap.bind(port).sync().channel();` — keeps `throws Exception`.
- `baseUri()`: `URI.create("http://localhost:" + ((InetSocketAddress) serverChannel.localAddress()).getPort())`.
- `close()`: close `serverChannel` (`syncUninterruptibly`), `bossGroup.shutdownGracefully()`, `workerGroup.shutdownGracefully()`, then `chat.close()`; wrap failures in `IllegalStateException("Cannot stop HTTP chat server", ...)` as today.

- [ ] **Step 5: Delete servlet classes and Jetty/SLF4J from pom**

Delete the seven files listed in Files. In `pom.xml` remove `jetty-ee10-servlet` dep, `slf4j-api` dep, `slf4j-simple` dep, and the `jetty.version`/`slf4j.version` properties.

- [ ] **Step 6: Run the full test suite**

Run: `mvn test`
Expected: `Tests run: 32, Failures: 0, Errors: 0, Skipped: 0` (28 existing + 4 new), BUILD SUCCESS. `-Xlint:all,-this-escape -Werror` must not fire.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/ru/archlabs/chat/http pom.xml src/test/java/ru/archlabs/chat/HttpChatProtocolTest.java
git commit -m "feat: replace Jetty servlets with Netty HTTP server"
```

---

### Task 3: Update README for Netty

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: nothing (documentation only)
- Produces: README text consistent with the code; nothing programmatic.

- [ ] **Step 1: Rewrite Jetty/SLF4J mentions**

`grep -n "Jetty\|jetty\|SLF4J\|slf4j\|сервлет\|Servlet" README.md`. Update each hit: the server is Netty (`HttpServerCodec` + routing handler), SSE streams are delivered by a task scheduled on the event loop (no thread per client), the structure section lists `ChatServerHandler` instead of the servlet list. Keep the «Сетевой протокол» section's request/response tables — the protocol itself did not change. Keep Russian.

- [ ] **Step 2: Sanity-read the changed sections**

Run: `grep -c "Jetty\|сервлет" README.md`
Expected: `0`.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: describe the Netty-based HTTP server"
```
