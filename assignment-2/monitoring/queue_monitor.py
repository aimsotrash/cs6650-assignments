#!/usr/bin/env python3
"""Samples the resort queues once a second during a test run, from the RabbitMQ management API.

    RABBIT_PASS=... python3 queue_monitor.py http://BROKER_IP:15672 queue_metrics.csv [seconds]

Writes one CSV row per sample (total depth, ready, unacked, publish/deliver/ack rates, dead-letter depth)
and prints the peak/average depth and rates at the end (after [seconds], or on Ctrl-C).
The management API recomputes rates every 5 s, so the rate columns change in 5 s steps.
"""
import base64
import csv
import json
import os
import sys
import time
import urllib.request


def rate(queue, stat):
    return queue.get("message_stats", {}).get(stat + "_details", {}).get("rate", 0.0)


def main():
    url, out = sys.argv[1], sys.argv[2]
    duration = float(sys.argv[3]) if len(sys.argv) > 3 else float("inf")
    user = os.environ.get("RABBIT_USER", "skitrak")
    auth = "Basic " + base64.b64encode((user + ":" + os.environ["RABBIT_PASS"]).encode()).decode()

    rows = []
    start = time.time()
    with open(out, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["time", "elapsed_s", "depth", "ready", "unacked",
                         "publish_rate", "deliver_rate", "ack_rate", "dead_letters"])
        try:
            while time.time() - start < duration:
                request = urllib.request.Request(url + "/api/queues/%2F", headers={"Authorization": auth})
                try:
                    queues = json.load(urllib.request.urlopen(request, timeout=5))
                except Exception as e:  # keep sampling through a broker restart
                    print("poll failed:", e)
                    time.sleep(1)
                    continue
                resorts = [q for q in queues if q["name"].startswith("resort.")]
                dead = sum(q.get("messages", 0) for q in queues if q["name"] == "skitrak.dead")
                row = [time.strftime("%H:%M:%S"), round(time.time() - start),
                       sum(q.get("messages", 0) for q in resorts),
                       sum(q.get("messages_ready", 0) for q in resorts),
                       sum(q.get("messages_unacknowledged", 0) for q in resorts),
                       round(sum(rate(q, "publish") for q in resorts)),
                       round(sum(rate(q, "deliver_get") for q in resorts)),
                       round(sum(rate(q, "ack") for q in resorts)),
                       dead]
                writer.writerow(row)
                f.flush()
                rows.append(row)
                time.sleep(1)
        except KeyboardInterrupt:
            pass

    active = [r for r in rows if r[5] > 0 or r[2] > 0] or rows
    if not active:
        return
    print("samples: %d (%d with traffic)" % (len(rows), len(active)))
    print("queue depth: peak %d, average %.0f" % (max(r[2] for r in rows), sum(r[2] for r in active) / len(active)))
    print("publish rate: peak %d/s, average %.0f/s" % (max(r[5] for r in rows), sum(r[5] for r in active) / len(active)))
    print("ack (consume) rate: peak %d/s, average %.0f/s" % (max(r[7] for r in rows), sum(r[7] for r in active) / len(active)))
    print("dead letters at the end: %d" % rows[-1][8])


if __name__ == "__main__":
    main()
