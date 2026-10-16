# Client Part 2

The same client as Part 1, plus the performance analysis: latency of every swipe, statistics, push latency percentiles and a throughput chart.

## What's added to Part 1

- `SwipeSender` records the start time (wall clock, for the CSV), the latency (`System.nanoTime()`, so it isn't rounded to whole ms), the status code and the resortID of every swipe (the attempt that finished it). Each sender keeps its own list, so there is no locking while sending.
- `RequestRecord`: one line of the CSV.
- At the end, **one writer** writes all records to the CSV (like the single writer in Lab 2 Exercise 3, so no broken lines).
- `SkierClient` calculates the statistics and the throughput per 10 second bucket.
- `ThroughputChart` draws the throughput-over-time line chart as a PNG (no extra library).

## Build

```bash
mvn clean package
```

Output: `target/client-part2.jar`

## Run

```bash
java -jar target/client-part2.jar <server url> [main phase threads] [total swipes] [output folder] [number of resorts]
```

- `output folder`: where the files go (default `results`)
- `number of resorts`: resortID is random 1 to this, with one leaderboard subscriber per resort (default 10)

The final run I used, from an m8azn.large client EC2 (a clone of my server) in the same subnet as the server, using the server's private IP so both clocks are synced by Amazon Time Sync (20,734 swipes/s):

```bash
java -jar client-part2.jar http://SERVER_PRIVATE_IP:8080/skitrak 128 500000 results
```

Before that I used a c7g.large client with 32 threads (12,361 swipes/s) and a free-tier t3.micro client with 64 threads (8,247 swipes/s).

## Output

Printed:
- Everything from Part 1
- Ingest latency in ms (with microsecond decimals): mean, median, p95, p99, min, max
- Throughput per resort
- Total leaderboard updates delivered
- Push latency (receive time − `serverTimestamp`): mean, median, p95, p99

Files in the output folder:
- `swipe_latencies.csv`: `timestamp,requestType,latency,statusCode,resortID` for every swipe (`requestType` is always `SWIPE`, `latency` is in ms with microsecond decimals)
- `throughput.csv`: swipes/s for each 10 second bucket
- `throughput.png`: the throughput-over-time chart

**Note on push latency:** it compares the client's clock with the server's clock. From my home server (it runs Windows) the clocks were off even after syncing, so the number can be negative. That's why the final Part 2 run was done from an EC2 instance.

My final output is in [`../results/`](../results/README.md).
