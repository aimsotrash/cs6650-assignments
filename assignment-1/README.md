# CS6650 Assignment 1: SkiTrak

Nikhil Pawar, Fall 2026

A swipe ingest server (one WAR on Tomcat 9, deployed on EC2) and a multithreaded load client that sends 500,000 lift-ride swipes over HTTP while watching resort leaderboards over WebSockets.

## Folders

| Folder | What's in it |
|---|---|
| [`server/`](server/README.md) | The server: swipe POST endpoint, `/health`, and the `/leaderboard/{resortID}` WebSocket, in one WAR |
| [`client-part1/`](client-part1/README.md) | Load client: generator thread, warmup + main phase, retries, leaderboard subscribers, basic metrics |
| [`client-part2/`](client-part2/README.md) | Same client plus per-swipe latency CSV, statistics, push latency percentiles and the throughput chart |
| [`results/`](results/README.md) | Output from the final runs, the CSV, the chart and my experiment notes |
| `report/` | The write-up (LaTeX source and the PDF) |
| [`AI_USAGE.md`](AI_USAGE.md) | How I used AI tools for this assignment |
## Requirements

- Java 11 or newer (I used Amazon Corretto 11)
- Maven 3 (the one bundled with IntelliJ works)
- Tomcat 9 (9.0.x, the `javax` namespace) for the server

## Build everything

From this folder:

```bash
mvn clean package
```

This builds `server/target/skitrak.war`, `client-part1/target/client-part1.jar` and `client-part2/target/client-part2.jar`.

## Final results (500,000 swipes, 0 failed)

| Run | Client | Main threads | Overall throughput | Ingest latency (mean / p95 / p99) | Push latency (mean / p95 / p99) |
|---|---|---|---|---|---|
| Part 1 (final) | m8azn.large client EC2 (a clone of my server), same subnet as the server | 128 | 21,671 swipes/s | – | mean 1 ms, max 20 ms |
| Part 2 (final) | m8azn.large client EC2 (a clone of my server), same subnet as the server | 128 | 20,734 swipes/s | 5.699 / 9.241 / 13.681 ms | 1.59 / 3 / 24 ms |
| Part 1 (10/14) | c7g.large client EC2, same subnet as the server | 32 | 12,803 swipes/s | – | 2 ms mean (max 28) |
| Part 2 (10/14) | c7g.large client EC2, same subnet as the server | 32 | 12,361 swipes/s | 2.57 / 5 / 10 ms (whole ms) | 2.23 / 6 / 12 ms |
| Part 1 (10/14) | t3.micro client EC2 (free tier) | 64 | 8,089 swipes/s | – | 3 ms mean (max 33) |
| Part 2 (10/14) | t3.micro client EC2 (free tier) | 64 | 8,247 swipes/s | 7.23 / 15 / 24 ms (whole ms) | 3.12 / 9 / 27 ms |

The server is always the same free-tier t3.micro. With the m8azn client the server became the limit (about 95% CPU) while the client was about half idle. I also ran the client from home (best 12,922 swipes/s, wired) and on my LAN (38,299 swipes/s, but with the server off EC2). More detail is in [`results/`](results/README.md) and the report.
