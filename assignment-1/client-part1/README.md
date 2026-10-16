# Client Part 1

Sends 500,000 swipe POSTs to the server and keeps one WebSocket subscriber open per resort the whole time, then prints the metrics.

## How it works

- **Generator:** 1 thread (`SwipeGenerator`) makes all 500,000 random swipes and puts them in a `LinkedBlockingQueue` (room for 10,000). It is much faster than the network, so the senders never wait for a swipe.
- **Warmup:** 32 threads, 1,000 swipes each, then they end. Timed separately with a `CountDownLatch`.
- **Main phase:** the other 468,000 swipes split across N threads (a command line argument).
- **HTTP:** one shared `java.net.http.HttpClient` for all threads. It keeps connections open and reuses them, at most one per sending thread at a time. Tomcat closes a kept-alive connection after 100 requests (its default), and then the client opens a new one; the output counts these reconnections.
- **Retries:** a failed send is retried up to 5 times (waiting 100, 200, 400, 800, 1600 ms). Swipes that still fail are counted as failed.
- **Leaderboard:** 10 `LeaderboardSubscriber`s (one per resort) connect before the warmup and stay connected. If a connection drops, it reconnects (up to 5 tries).

Swipe values: skierID 1–100000, resortID 1–10 (configurable), liftID 1–40, time 1–360, seasonID 2026, dayID 1.

## Build

```bash
mvn clean package
```

Output: `target/client-part1.jar` (includes everything it needs).

## Run

```bash
java -jar target/client-part1.jar <server url> [main phase threads] [total swipes] [number of resorts]
```

- `server url`: for example `http://YOUR_EC2_IP:8080/skitrak` (default `http://localhost:8080/skitrak`)
- `main phase threads`: default 200
- `total swipes`: default 500,000 (only change this for testing; at least 32,000, because the warmup alone sends 32 × 1,000)
- `number of resorts`: resortID is random 1 to this, with one leaderboard subscriber per resort (default 10)

The final run I used, from an m8azn.large client EC2 (a clone of my server, 2 cores at 5.0 GHz) in the same subnet as the server, using the server's private IP (21,671 swipes/s):

```bash
java -jar client-part1.jar http://SERVER_PRIVATE_IP:8080/skitrak 128
```

Before that I used a c7g.large client with 32 threads (12,803 swipes/s) and a free-tier t3.micro client with 64 threads (8,089 swipes/s). From home I used 512 threads with the server's public IP, because the round trip is much longer there (best 12,922 swipes/s).

In IntelliJ, run `SkierClient` with the same values in **Program arguments**.

## Output

- Successful and failed swipes
- Warmup time and throughput, main phase time and throughput, total time, overall throughput
- HTTP: connections open at once, connections reopened after Tomcat closed them, and the number of retries
- Client CPU in the main phase: how busy it was, CPU per swipe, and the throughput it could reach at 100% CPU
- Updates received per leaderboard subscriber, WebSocket connect times, reconnections
- Push latency mean and max (receive time − `serverTimestamp`)

My final output is in [`../results/part1_output.txt`](../results/part1_output.txt).
