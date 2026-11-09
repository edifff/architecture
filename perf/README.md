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

Environment: JDK 25, Docker 29.6.2, 14.3 GiB RAM, Docker Compose layered stack
(`api -> chat -> storage`), H2 file storage with `WRITE_DELAY=0` so each message is
durable. Each row sends 20 messages per user; the reported duration covers sends only.

| Users | Messages | Duration | Throughput | Errors |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 20 | 6.536 s | 3.1 req/s | 0 |
| 10 | 200 | 12.990 s | 15.4 req/s | 0 |
| 50 | 1000 | 42.677 s | 23.4 req/s | 0 |

For comparison, the same 10-user/200-message run against the single-process app
(`arch-labs-app`, H2 on a host path) took 13.792 s after warm-up, or 14.5 req/s. The
gRPC hops did not dominate at this load: the durable H2 commit is the bottleneck.

## What the numbers mean

- Throughput rises from one client to ten clients because requests overlap while
  waiting for the storage commit. It rises only modestly at fifty clients, where the
  single H2 writer and per-message fsync saturate first.
- The api and chat containers stayed at low CPU during the runs. That is expected:
  their work is small compared with the synchronous storage transaction.
- The api layer is stateless and can be replicated behind a load balancer. The chat
  layer keeps live sessions and their listener queues in memory, so replicas need
  sticky sessions or an external session/notification bus. The storage layer is the
  current single-writer limit; PostgreSQL or a partitioned storage service is the next
  scaling step.
- To compare a component change, keep the client, users, and message count fixed and
  use the same Prometheus scrape interval. The counters are
  `archlabs_requests_total` and `archlabs_notifications_total`.

Prometheus scrapes all layers at `http://localhost:9090`; the individual metric
endpoints are api `:9100`, chat `:9101`, and storage `:9102`.
