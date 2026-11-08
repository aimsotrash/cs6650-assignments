# Monitoring

Run these during a test, next to the client.

| What | How |
|---|---|
| Queue depth and publish/consume rates, once a second | `RABBIT_PASS=... python3 queue_monitor.py http://BROKER_IP:15672 queue_metrics.csv 300` (from anywhere allowed to reach port 15672). Writes a CSV row per second and prints the peak/average depth and rates at the end. |
| Queue charts for the report | RabbitMQ management UI, http://BROKER_IP:15672 → Queues and Streams (depth over time, message rates). |
| CPU, memory, network of a host | `bash host_stats.sh 300 > stats.csv` on each EC2 host (CSV every 5 s: CPU busy %, steal %, memory used MB, network in/out KB/s). |
| Consumer rate and lag | `journalctl -u skitrak-consumer -f` on the consumer host: every 5 s it prints swipes processed, rate, consumer lag (time from the server accepting a swipe to the consumer applying it; avg and max), queue depth, consumer threads, duplicates dropped, rejected messages and broadcasts. |
| Requests per server (load distribution) | each Tomcat's access log: `wc -l /opt/tomcat9/logs/localhost_access_log.$(date -u +%F).txt`, plus the ALB's `RequestCount` in CloudWatch. |
