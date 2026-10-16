# Results

Output from my runs (500,000 swipes each, 0 failed). The server is always the same t3.micro in us-west-2a. The final runs are from 10/15, with the client on an m8azn.large EC2 instance (a clone of my server, 2 cores at 5.0 GHz) in the same subnet, using the server's private IP.

| File | What it is |
|---|---|
| `part1_output.txt` | **Final Part 1:** m8azn.large client (128 main threads), 21,671 swipes/s overall |
| `part2_output.txt` | **Final Part 2:** m8azn.large client (128 main threads), 20,734 swipes/s overall, with all the statistics |
| `swipe_latencies.csv` | One line per swipe from the final Part 2 run (500,000 lines): `timestamp,requestType,latency,statusCode,resortID`, latency in ms with microsecond decimals |
| `throughput.csv`, `throughput.png` | Swipes per second in each 10 second bucket of the final Part 2 run, and the chart |
| `earlier_runs/oct14_c7g_t3/` | My 10/14 final runs: c7g.large client (12,803 swipes/s) and free-tier t3.micro client (8,089 swipes/s), with their CSVs and charts (latency in whole ms back then) |
| `earlier_runs/` | My 10/13 runs: home server wired (12,922/s) and Wi-Fi (11,226/s), the best c7g.large run (13,605/s), and a t3.micro Part 2 run (7,378/s) with its CSV and chart |
| `experiment_m8azn_server_part1.txt` | Experiment: m8azn.large client and m8azn.large server (not free tier), best of four runs: 45,330 swipes/s overall, client is the limit |
| `experiment_client_location.txt` | Every experiment: where the client ran (EC2, home, laptop, campus, on the server, LAN), round trips, thread sweeps, CPU numbers, all three days |

## Summary

| | Part 1 (final) | Part 2 (final) | Part 1 (10/14) | Part 2 (10/14) |
|---|---|---|---|---|
| Client | m8azn.large | m8azn.large | c7g.large | c7g.large |
| Main phase threads | 128 | 128 | 32 | 32 |
| Round trip of one swipe (no load) | 0.29 ms | 0.29 ms | 0.49 ms | 0.49 ms |
| Warmup (32 threads × 1000) | 1.9 s, 16,486/s | 2.2 s, 14,856/s | 6.3 s, 5,076/s | 5.3 s, 6,062/s |
| Main phase | 21.1 s, 22,147/s | 22.0 s, 21,311/s | 32.7 s, 14,290/s | 35.2 s, 13,306/s |
| Total / overall | 23.1 s, **21,671/s** | 24.1 s, 20,734/s | 39.1 s, 12,803/s | 40.4 s, 12,361/s |
| HTTP connections reopened (Tomcat closes one after 100 requests) | 4,976 | 4,985 | not counted then | not counted then |
| Client CPU in the main phase | 53% busy, 48 µs per swipe | 49% busy, 46 µs per swipe | 88–99% busy | 88–99% busy |
| Ingest latency mean / median / p95 / p99 | – | 5.699 / 5.418 / 9.241 / 13.681 ms | – | 2.57 / 2 / 5 / 10 ms (whole ms) |
| Push latency | mean 1 ms, max 20 ms | 1.59 / 1 / 3 / 24 ms | mean 2 ms, max 28 ms | 2.23 / 2 / 6 / 12 ms |

All three EC2 instances sync their clocks with Amazon Time Sync, so the push latencies are real. With the m8azn client the limit moved from my client to the t3.micro server: in the same setup the server was about 95% busy while the client was about half idle (`experiment_client_location.txt`, 10/15). The c7g.large client had been the limit before (88–99% CPU).
