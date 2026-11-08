# CS6650 Assignment 2: SkiTrak with a queue, consumers and a real leaderboard

Nikhil Pawar, Fall 2026

Builds on Assignment 1. The ingest server (same Tomcat 9 WAR, same validation) now looks up the lift's vertical
and publishes every valid swipe to RabbitMQ; a separate consumer app aggregates the swipes (each skier's vertical,
each resort's top N per day) and broadcasts the real leaderboard through a fanout exchange to every WebSocket
server, which pushes it to the subscribers it holds. Multiple ingest servers sit behind an AWS Application Load Balancer.
The load client is the Assignment 1 client (`assignment-1/client-part2`), unchanged.

```
Client -> ALB -> [ingest servers] -> skitrak.exchange -> resort.1..10 -> consumer -> leaderboard.fanout -> every ingest server -> its WebSocket subscribers
```

## Folders

| Folder | What's in it |
|---|---|
| `server-v2/` | The ingest server: Assignment 1's servlet, health check and WebSocket endpoint, plus the channel pool, publishing and the backplane listener |
| `consumer/` | The consumer app: consumer pool, aggregation, top N, broadcaster, health check |
| `deployment/` | How it's deployed on EC2: broker install, consumer service, Tomcat settings, ALB script ([README](deployment/README.md)) |
| `monitoring/` | Queue depth/rate sampler and per-host CPU/memory/network script ([README](monitoring/README.md)) |
| `results/` | Output of the test runs |
| `report/` | The report (`report.pdf`, 4 pages: architecture on pages 1-2, best results on 3-4; LaTeX source and images) |
| `transcripts/` | The Claude Code chat for this assignment, as Markdown (my messages, Claude's replies, one line per tool call) |
| [`AI_USAGE.md`](AI_USAGE.md) | How I used AI tools for this assignment |

## Build

Java 11 and Maven 3, from this folder:

```bash
mvn clean package
```

Builds `server-v2/target/skitrak.war` and `consumer/target/consumer.jar`. TopN has a self-check: `java -cp consumer/target/consumer.jar TopN`.

## server-v2

- `SkierServlet`: Assignment 1's validation (plus resortID 1-10, since there is one queue per resort). A valid swipe
  gets its vertical from the course's `lift_vertical.json` (`LiftVerticals`, the file is in `src/main/resources` unchanged)
  and is published as `{eventId, resortID, dayID, skierID, liftID, vertical, time, timestamp, serverId}` to
  `skitrak.exchange` with routing key `resort.{resortID}`, persistent, and **201 only after the broker's publisher confirm**.
  If the publish fails it tries once more with the same eventId, then answers 503 (the client retries).
- `ChannelPool`: one connection, a fixed set of channels (confirm mode) that request threads borrow and return, so a
  channel is only used by one thread at a time and there is no channel per publish. If the connection drops, the next
  borrow reconnects and builds a new pool (the client library's automatic recovery is off for this connection;
  reconnecting is done here). While the broker is down requests fail fast with 503 instead of each waiting on a connect.
- `LeaderboardRelay` + `LeaderboardEndpoint`: the WebSocket side of the backplane. Each server binds its own
  broker-named queue to `leaderboard.fanout` (Week 6 publish-subscribe) and pushes every update to the sessions it holds
  for that resort. Its own connection, with the library's automatic recovery.
- `Topology`: declares the exchanges and queues (also copied in `consumer/`): durable `resort.1`..`resort.10` with a
  1 h message TTL, a 200,000 message limit with `reject-publish` (a full queue nacks the publish instead of dropping the
  oldest swipe) and a dead-letter exchange (`skitrak.dlx` -> `skitrak.dead`).
- Settings (environment, `/opt/tomcat9/bin/setenv.sh`): `RABBIT_HOST`, `RABBIT_USER`, `RABBIT_PASS`, `CHANNEL_POOL_SIZE` (64), `SERVER_ID` (hostname).

## consumer

- `ConsumerPool`: `CONSUMER_THREADS` threads, each with its own channel, resort queues given out round robin
  (fair distribution). A resort's queue is consumed by exactly one channel and the RabbitMQ client hands a channel's
  messages to one thread at a time in order, so **each resort's swipes are applied in order**. That caps useful threads
  at 10 (one per resort queue); more are reported and ignored. Dynamic scaling: every 5 s, if more than 1,000 swipes are
  waiting it doubles the threads (up to `MAX_THREADS`), and after 30 s under 100 it halves them (down to `CONSUMER_THREADS`);
  a rescale cancels every consumer, waits for the messages already delivered to finish, then restarts, so order holds across it.
- `SwipeConsumer`: manual ack after the swipe is aggregated (at-least-once), optional batch ack (`ACK_BATCH`).
  A message that can't be parsed is rejected to the dead-letter queue; one that fails processing is requeued once,
  then dead-lettered (Week 6 poison messages).
- `Aggregator`: `skierVertical` (resort:day:skier -> vertical), `resortLeaderboards` (resort:day -> `TopN`),
  `swipesProcessed`; dedupes on `eventId` (the last 50,000 per resort), so a redelivered or re-published swipe isn't counted twice.
- `Broadcaster`: when a resort's top N changes, publishes `{resortID, dayID, serverTimestamp, top: [{rank, skierID, vertical}]}`
  to `leaderboard.fanout` (Assignment 1's leaderboard message, now real, plus the day). Skips updates while the broker
  blocks publishers (memory alarm), so the consumers can keep draining the queues.
- Health check `GET :8081/health`; a stats line every 5 s (rate, consumer lag, queue depth, threads, duplicates, broadcasts,
  swipes per ingest server); runs as a systemd service with `Restart=always`; graceful shutdown on stop.
- Settings (environment, `/etc/skitrak-consumer.env`): `RABBIT_HOST`, `RABBIT_USER`, `RABBIT_PASS`, `CONSUMER_THREADS` (10),
  `MAX_THREADS` (= `CONSUMER_THREADS`), `PREFETCH` (100), `ACK_BATCH` (1), `TOP_N` (20), `HEALTH_PORT` (8081).

## Results

All hosts t3.micro (broker, consumer, servers, client), client on EC2, the Assignment 1 client unchanged, 128 main-phase
threads unless noted, `TOP_N` 20, 10 consumer threads, prefetch 100. Every run: **0 failed swipes**, the consumer applied
exactly the number of swipes the client got 201 for, and the leaderboard updates the client received equal the
broadcasts the consumer published. Raw output, queue samples and per-host stats are in `results/<run>/`.

| Run | Throughput overall (main phase) swipes/s | Latency mean / p99 ms | Queue depth peak / avg | Consumer lag avg / max ms | CPU during the run |
|---|---|---|---|---|---|
| 1 server, 500K (direct) | 5,463 (5,775) | 21.2 / 62.7 | 53 / 26 | 12 / 267 | broker 95%, server 61%, client 73%, consumer 16% |
| ALB + 2 servers, 500K | 5,643 (6,100) | 20.2 / 57.6 | 123 / 36 | 10 / 300 | broker 91%, server 45%, client 72%, consumer 18% |
| ALB + 4 servers, 500K | 5,320 (5,685) | 21.7 / 67.7 | 1,308 (one 5 s sample) / 101 | 10 / 384 | broker 91%, servers 19-27%, client 72%, consumer 18% |
| ALB + 4 servers, 1M | 5,681 (5,973) | 21.1 / 53.4 | 203 / 48 | 12 / 545 | broker 94%, servers 19-22%, client 71%, consumer 17% |

- **Screenshot runs (11/7, redeployed from the same AMI and scripts), ALB + 4 servers, 1M each:** 5,979/s and 5,852/s,
  0 failed, 250,000 +/- 1 swipes per server, updates received = broadcasts (3,612 and 3,639). Console screenshots and
  ALB metrics are in `report/images/` and the report (`report/report.pdf`).
- **Distribution** (swipes per server, counted by the consumer from each message's `serverId`): 2 servers 250,000 / 250,000;
  4 servers 124,998 / 125,000 / 125,001 / 125,001; 1M run 250,000 each.
- **Sticky sessions** (`results/stickiness/`): without a cookie the ALB split 8 requests 4/4; with the `AWSALB` cookie 8/8
  HTTP requests and 5/5 WebSocket upgrades went to the same server.
- **Reconnection:** all AMQP connections force-closed mid-run (40K swipes): 0 failed, 0 retries, the server's pool reconnected,
  the consumer and relay recovered, 13 duplicate copies were dropped by the eventId dedupe, applied = sent.
  A cold start of every host in any order also came up by itself (the consumer restarted under systemd until the broker was up).

**Bottleneck: the broker.** RabbitMQ's CPU is at 90-95% in every run (on a t3.micro, each swipe is a persistent message,
a publisher confirm, a consumer ack and possibly a fanout publish). That's why 1, 2 and 4 ingest servers all land at
about 5.3-5.7K/s: adding servers spreads their load (61% -> 45% -> ~22% CPU each) but can't push more through the broker.

### Tuning sweeps (ALB + 4 servers, 500K)

| Setting | Throughput overall (main) | Latency mean / p99 ms | Queue peak / avg | Notes |
|---|---|---|---|---|
| consumer threads 5 / 10 / 15 / 20 | 5,384 / 5,320 / 5,278 / 5,393 | ~21 / 64-76 | <= 231 / ~50 (10: one 1,308 sample) | flat: the consumer is at ~17% CPU and the broker saturates first; above 10 the pool is capped by the 10 resort queues (one thread per queue keeps each resort in order) |
| client threads 64 / 128 / 256 / 512 | 4,489 / 5,320 / 5,844 / 6,235 | 13 / 22 / 38 / 70 mean | <= 378 / 37-101 | more concurrency gets a bit more out of the saturated broker, latency grows; client CPU 65% -> 85% |
| `ACK_BATCH` 50 | 5,826 (6,383) | 19.4 / 59.3 | 384 / 248 | +10%: fewer ack frames, broker CPU 84%; depth includes up to 50 unacked per consumer |
| `TOP_N` 10 / 20 / 50 | 5,234 / 5,320 / 5,152 | ~21-22 / 60-65 | <= 127 | leaderboard updates 1,621 / 2,825 / 6,254: N sets how often the top N changes, throughput drops slightly at 50 |
| dynamic scaling, 1 -> 10 threads, live load | 5,423 | 21.2 / 70.1 | 942 / 98 | never passed the 1,000 trigger: one thread keeps up when the broker is the limit |
| dynamic scaling, 150K backlog | - | - | 34,660 at the first check | 1 thread drained ~23K/s, the pool went 1 -> 2 threads, queue empty 5 s later, back to 1 after 30 s calm |

The sweeps found one real bug: with `ACK_BATCH` > 1, the messages of an unfinished ack batch were left unacked when
the consumer was restarted, redelivered, and counted again by the new consumer (250 extra swipes in the next run).
`ConsumerPool.stop()` now acks them on a graceful stop or rescale; the rerun applied exactly 500,000.

### Bigger instances and tuning (for the bonus)

The broker was the bottleneck on t3.micro, so I moved to bigger instances. The Learner Lab only allows sizes up to
`.large` (anything bigger is terminated, at most 9 instances and 32 vCPUs), so "bigger" means the fastest `.large`:
**m8azn.large** (2 full AMD cores, no hyperthreading, 5.0 GHz sustained, 8 GB), the top `.large` in us-west-2a.
Changed one thing at a time (instance types by stop / change type / start, same private IPs and disks; settings in
`setenv.sh` and `/etc/skitrak-consumer.env`; no code changes except the ack flush below):

| Run (1M, ALB + 4 servers) | Overall (main phase) swipes/s | Latency mean / p99 ms | Queue peak / avg | Broker CPU |
|---|---|---|---|---|
| all t3.micro, 128 threads | 5,852-5,979 | 20.1-20.7 / 45-49 | 100-1,205 / 53-89 | 91-95% |
| broker m8azn.large, rest t3.micro | 8,131 (8,535) | 14.7 / 37.0 | 2,385 (1 sample) / 111 | client 93% |
| all m8azn.large, consumer heap 2 GB (G1) | 25,450 (26,605) | 4.7 / 11.1 | 56 / 27 | 84% |
| + 256 client threads, channel pool 200 | 26,647 (28,293) | 8.8 / 20.2 | 81 / 45 | 91% |
| 512 client threads | 26,970 (28,322) | 17.4 / 39.5 | 152 / 74 | 95% |
| 256 threads + `ACK_BATCH` 50 | 27,762 (28,867) | 8.6 / 23.5 | 360 / 228 | 90% |
| `PREFETCH` 500 + `ACK_BATCH` 100 | 28,127 (29,661) | 8.4 / 21.1 | 688 / 556 | 89% |
| `PREFETCH` 100 + `ACK_BATCH` 50, consumer on ZGC | **28,134 (29,661)** | 8.4 / 20.7 | 347 / 239 | 90% |
| same, confirmation run | 27,972 (29,102) | 8.5 / 22.7 | 332 / 226 | 91% |

| Run (500K, all m8azn.large, ZGC, `PREFETCH` 100, `ACK_BATCH` 50, 256 threads) | Overall (main phase) | Latency mean / p99 ms | Queue peak / avg | Broker CPU |
|---|---|---|---|---|
| ALB + 4 servers, channel pool 200 | 26,388 (29,330) | 8.2 / 19.7 | 343 / 239 | 80% |
| ALB + 4 servers, channel pool 64 | 26,424 (28,664) | 8.4 / 24.8 | 338 / 194 | 69% |
| ALB + 4 servers, pool 128, 512 threads | 25,294 (27,982) | 17.0 / 40.1 | 319 / 200 | 80% |
| **1 server, no ALB, channel pool 64** | **34,018 (37,407)** | 6.5 / 27.9 | 322 / 203 | 77% |
| 1 server, no ALB, pool 200 | 33,799 (36,943) | 6.6 / 17.1 | 311 / 171 | 63% |
| 1 server, no ALB, pool 200, 512 threads | 30,495 (33,423) | 14.3 / 30.9 | 392 / 203 | 50% |

### Warmed-up ALB (final runs, all m8azn.large, final settings, channel pool 64)

My first runs on a new ALB were all stuck at about 11.5K swipes/s per client (20 ms) with every host mostly idle.
A new ALB starts with small nodes and only adds bigger ones after a few minutes of steady traffic, and the Java client
looks up the ALB's name once and keeps its connections, so a run only uses the new nodes if it starts after the
scale-up. So before measuring I sent ~5.5 min of load (2 clients x 4M swipes; the new nodes showed up after 3-5 min)
and then started the runs below. Same servers, same client, same ALB: 11,550 -> 34,199 swipes/s.

| Run (256 threads, 1 client host) | Overall (main phase) swipes/s | Latency mean / p99 ms | Queue peak / avg | Broker CPU | Swipes per server |
|---|---|---|---|---|---|
| ALB + 4, new ALB (not warmed up), 500K | 11,550 (11,757) | 20.5 / 45.2 | 224 / 93 | 67% | 125,000 each |
| 1 server, no ALB, 500K | 33,602 (36,217) | 6.6 / 25.7 | 263 / 158 | 75% | 500,000 |
| ALB + 2, 500K | 34,758 (40,068) | 6.1 / 13.9 | 336 / 215 | 90% | 250,002 / 249,998 |
| ALB + 4, 500K | 34,199 (39,284) | 6.2 / 14.6 | 367 / 168 | 81% | 124,999-125,002 |
| ALB + 4, 1M | 39,430 (42,032) | 5.9 / 13.7 | 328 / 229 | 94% | 249,999-250,002 |
| ALB + 4, 1M, client JVM on `-XX:+UseParallelGC` | **42,032 (45,019)** | 5.5 / 12.7 | 385 / 223 | 97% | 249,999-250,002 |
| same, rerun | 41,846 (44,688) | 5.6 / 12.5 | 375 / 256 | 95% | 249,998-250,002 |
| ALB + 4, 1M, 384 threads | 36,481 (38,482) | 9.7 / 21.3 | 389 / 237 | 86% | |
| ALB + 4, 1M, 512 threads | 34,097 (35,718) | 13.8 / 29.9 | 367 / 176 | 78% | |
| ALB + 4, 1M, `ACK_BATCH` 100 + `PREFETCH` 250 | 39,912 (43,235) | 5.8 / 12.2 | 675 / 435 | 94% | |
| ALB + 4, 1M, Erlang busy-wait off on the broker | 39,402 (43,089) | 5.8 / 12.8 | 377 / 314 | 95% | |

With one client host, the client (86-94% CPU) and the broker (88-97%) both run out at about 40K/s, so 1, 2 and 4
servers come out close. To push more load than one client host can, I ran the same client (same jar, same command)
on two client hosts at once, each sending its own swipes; throughput below is the sum of the two clients:

| Run (2 client hosts, 256 threads each) | Overall (main phase) swipes/s | Latency mean / p99 ms | Queue peak / avg | Consumer lag max | Swipes per server |
|---|---|---|---|---|---|
| ALB + 4, new ALB (not warmed up), 500K each | 22,812 (23,105) | 20.8 / 45.7 | 306 / 216 | 29 ms | 250,000 each |
| 1 server, no ALB, 500K each | 40,185 (41,755) | 11.5 / 37.7 | 319 / 231 | 117 ms | 1,000,000 |
| ALB + 2, 500K each | 44,366 (47,895) | 10.0 / 43.0 | 1,544 (one 5 s sample) / 465 | 149 ms | 500,000 each |
| ALB + 2, 500K each, rerun | 44,618 (47,733) | 10.0 / 44.0 | 380 / 292 | 145 ms | 500,000 each |
| ALB + 4, 500K each | 45,046 (48,459) | 10.0 / 30.2 | 430 / 323 | 150 ms | 250,000 each |
| **ALB + 4, 1M each (highest)** | **46,887 (49,001)** | 10.1 / 25.3 | 433 / 284 | 184 ms | 500,000 each |

Every run: 0 failed swipes, the consumer applied exactly the number sent, a flat queue under 1,000 (except the marked
single samples). With one client, leaderboard updates received = broadcasts; with two, the client that starts later
or finishes first misses the updates published while it isn't subscribed (e.g. 4,609 and 4,635 received of 4,635).

What I learned:
- **t3.micro -> m8azn.large: 5.9K -> 28K/s through the ALB (4.8x), 34K/s on one server** (all before I warmed up the
  ALB; with a warmed-up ALB it's 42K/s with one client host, see below). The broker went first (+38%),
  which made the t3.micro client the limit (93% CPU); upgrading everything gave the big jump.
- **Queue spikes were GC pauses in the consumer.** On a 1 GB t3.micro the JVM uses the serial collector; at 30K swipes/s
  even G1's ~40-50 ms pauses still put one 5 s sample over 1,000 (one pause queues 30K/s x 0.04 s = 1,200 swipes).
  With ZGC (`-XX:+UseZGC`, sub-millisecond pauses) the queue stayed at 140-350 the whole run. `JAVA_OPTS` is in `consumer.env`.
- **Batched acks** (`ACK_BATCH` 50) take ack frames off the broker: +4% here, +10% on t3.micro. The tail of a batch is
  now acked by a flush every 5 s (`ConsumerPool.flushAcks`), so no counted swipes sit unacked after a run.
- **A new ALB has to be warmed up before it's measured.** Without that, every ALB run (1, 2 or 4 targets) was stuck
  near 11.5K/s per client at 20 ms while each server alone did 33.5K/s directly; I first blamed the ALB path in general.
  After a few minutes of steady load the ALB added nodes, and runs started after that got 34-42K/s with one client.
- **More servers help once one server is the limit.** With two client hosts, one server tops out at 40.2K/s (it
  queues behind Tomcat's 200 request threads and its one AMQP connection), while ALB + 2 and ALB + 4 reach 44.4K and
  45.0K (+11-12%, main phase +15-16%). After that the broker is the limit (90-100% CPU on its 2 cores): 2 -> 4 servers
  barely changes anything. With a single broker that's as far as this hardware goes.
- **The client host is CPU-bound at ~40K/s.** Running its JVM with the parallel garbage collector instead of G1 (a
  `java` option, the client jar is unchanged) took the best one-client run from 39.4K to 42.0K/s.
- **Consumer lag stays low on average (0-5 ms) but the max goes over 100 ms above ~44K/s**, when the broker is at
  98-100% CPU; at up to 42K/s it stayed under 100 ms.
- **More client threads stop helping at ~256:** Tomcat runs at most 200 requests at once, so more threads just queue,
  and on a 2-core client host they cost CPU (384 threads: 36.5K/s, 512: 34.1K/s). The channel pool size made no
  difference at 256 threads, so it stays at 64.
