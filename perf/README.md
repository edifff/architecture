# Performance notes

## Load client

`perf/load.sh` compiles a dependency-free JDK HTTP client and drives the api layer:

```shell
perf/load.sh <users> <messages-per-user> [base-url]
perf/load.sh 10 20
perf/load.sh 50 20
```

It creates the users, sends messages concurrently on virtual threads, records the
elapsed time and error count, then disconnects the users. Connect and disconnect time
are outside the measured send interval.

## Results

Environment: JDK 25, Docker 29.6.2, 14.3 GiB RAM, Docker Compose sharded stack
(`api -> chat-1..3 -> storage-1..3`), H2 file storage with `WRITE_DELAY=0` so each
message is durable. Each row sends 20 messages per user; the reported duration covers
sends only.

| Users | Messages | Duration | Throughput | Errors |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 20 | 6.536 s | 3.1 req/s | 0 |
| 10 | 200 | 12.990 s | 15.4 req/s | 0 |
| 50 | 1000 | 42.677 s | 23.4 req/s | 0 |

## What the numbers mean

- Throughput rises from one client to ten clients because requests overlap while
  waiting for the storage commit. It rises only modestly at fifty clients, where the
  per-shard H2 writer and per-message fsync saturate first.
- The api and chat containers stayed at low CPU during the runs. That is expected:
  their work is small compared with the synchronous storage transaction.
- The api layer is stateless and can be replicated behind a load balancer. The chat
  layer keeps live sessions and their listener queues in memory, so replicas need
  sticky sessions or an external session/notification bus. Each storage shard is a
  single-writer limit for the users it owns; PostgreSQL or more shards is the next
  scaling step.
- To compare a component change, keep the client, users, and message count fixed and
  use the same Prometheus scrape interval. The counters are
  `archlabs_requests_total` and `archlabs_notifications_total`.

Prometheus scrapes all layers at `http://localhost:9090`; the individual metric
endpoints are api `:9100`, chat shards `:9101`–`:9103`, and storage shards
`:9111`–`:9113`.

## Sharded stack

Three identical chat/storage pairs back the input container. The api routes each user
with `FNV-1a(user) mod 3` and fans a normal send out to the other two shards, so the send
path performs one storage commit on the sender's shard and two notification-only
`Deliver` calls. Measure the workload with:

```shell
docker compose up -d --build
perf/load.sh 10 20
perf/load.sh 50 20
```

The per-shard metrics show that three writers now commit in parallel instead of one H2
writer serializing all users. The api fan-out and the synchronous gather are the new
costs on top of the request; they are visible as `api_send`, `api_deliver`, and
`api_status` counters on the api endpoint.
