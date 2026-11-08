# AI Usage Reflection — CS6650 Assignment 2

**AI tool:** Claude Code

## 1. How I used AI

I used Claude Code while extending my Assignment 1 ski-resort service into the Assignment 2 message-driven system. I used it to compare the two assignment specifications, inspect the existing project structure, reason through the RabbitMQ and consumer design, debug failure-handling behavior, review deployment scripts, interpret load-test results, and organize the LaTeX report.

The main areas of assistance were:

- **Assignment analysis and migration:** identifying which parts of the Assignment 1 Tomcat application could be retained and which paths needed real RabbitMQ publishing, aggregation, and leaderboard delivery.
- **Swipe producer:** reviewing the event schema, lift-vertical lookup, topic-exchange routing, persistent messages, publisher confirms, and the relationship between a broker failure and the HTTP response.
- **Connection and channel management:** reviewing the fixed channel pool, exclusive channel borrowing, bounded waits, channel replacement, and coordinated reconnection when RabbitMQ became unavailable.
- **Consumer and aggregation:** reasoning about one ordered processing lane per resort queue, skier vertical totals, top-N calculation, manual acknowledgements, redelivery, duplicate detection, dead-letter handling, and consumer rescaling.
- **Leaderboard delivery:** understanding why a consumer cannot directly write to WebSocket sessions owned by other Tomcat instances, and reviewing the fanout backplane and per-server relay queues.
- **Deployment and monitoring:** reviewing the RabbitMQ, consumer, Tomcat, ALB, and systemd setup, together with the queue and host monitoring scripts.
- **Testing and performance analysis:** comparing instance counts, client and consumer concurrency, acknowledgement batching, prefetch, JVM garbage collectors, ALB warm-up, and broker behavior under load.
- **Report preparation:** organizing the architecture explanation, diagrams, measurements, and performance discussion in the LaTeX report.

I used AI as a design and debugging aid. I checked recommendations against the assignment, the implementation, and the measured results rather than treating an explanation as proof that the system worked.

## 2. Suggestions I accepted

### 2.1 Wait for publisher confirmation before returning success

I kept the recommendation to publish a persistent swipe event and wait for RabbitMQ publisher confirmation before returning HTTP 201. Returning success before the broker accepted the message could make the client believe a swipe was queued when it was not. In `server-v2/src/main/java/SkierServlet.java`, the event is published with a stable `eventId`, publisher confirmation is awaited, and a failed/ambiguous attempt is retried once with the same ID before the request ultimately succeeds or returns an error.

**Verification:** The forced AMQP-connection-loss run completed with zero failed swipes. The consumer dropped 13 duplicate copies using event-ID deduplication, and the reported applied count matched the sent count.

### 2.2 Preserve one ordered processing lane per resort

I kept the design in which each resort queue is assigned to one consumer channel at a time. Different resort queues can be processed in parallel, but messages from one resort are not split among multiple concurrent consumers. This makes the ordering rule explicit and avoids allowing a later swipe for a resort to overtake an earlier one.

**Where:** `consumer/src/main/java/ConsumerPool.java` and `SwipeConsumer.java`.

**Verification:** The consumer pool assigns the ten resort queues across workers and caps useful consumer concurrency at ten. The recorded 5/10/15/20 worker sweep did not show a reason to increase the number of ordered lanes beyond the ten queues.

### 2.3 Reuse the same event ID when retrying an ambiguous publish

I kept the stable-event-identity approach. If the connection fails around a publish confirmation, the producer may not know whether RabbitMQ accepted the first attempt. Generating a new ID on retry would make the retry look like a separate swipe. Reusing the same ID allows the consumer to recognize duplicate delivery.

**Where:** `SkierServlet.java` creates the event once and retries the same event; `consumer/src/main/java/Aggregator.java` tracks recently processed event IDs per resort.

**Verification:** The connection-drop test recorded 13 duplicate copies dropped by the deduplication logic, with the final applied count matching the submitted count.

### 2.4 Flush pending acknowledgement batches during shutdown and rescaling

I kept the fix for an issue exposed by acknowledgement batching. A consumer could update its in-memory aggregate while the corresponding delivery remained in an unfinished acknowledgement batch. If the consumer stopped or was rescaled before that batch was acknowledged, RabbitMQ could redeliver the messages and the aggregate could count them again.

**Where:** `consumer/src/main/java/ConsumerPool.java` coordinates consumer cancellation and acknowledgement flushing during stop/rescale.

**Verification:** An earlier run showed 250 extra applied swipes after a consumer restart with a partial batch. After the shutdown/rescale flush was added, the corrected run applied exactly 500,000 swipes.

## 3. Suggestions I did not carry into the final implementation

### 3.1 Do not use an unbounded `take()` for channel borrowing

An initial channel-pool sketch used `BlockingQueue.take()`. I did not keep that behavior. If all channels were unavailable while RabbitMQ was down or a publish was stuck, a request thread could remain blocked indefinitely. The implementation instead waits for a channel for at most two seconds, then fails the request path cleanly. Reconnection is coordinated and throttled rather than letting every request thread create a new connection.

**Where:** `server-v2/src/main/java/ChannelPool.java` uses a bounded poll/deadline and a one-second reconnect throttle.

### 3.2 Do not add an initial WebSocket snapshot without a current-state source

An initial-snapshot-on-connect suggestion was not carried into the final code. `LeaderboardEndpoint.onOpen` registers the new session, while the relay forwards snapshots when the consumer publishes them. The existing backplane is event-driven and does not itself provide a stored latest-state query. Sending an initial snapshot would therefore require an additional authoritative current-state mechanism, not just a message sent from the endpoint. I kept the existing scope and documented the behavior: a newly connected subscriber waits for the next leaderboard update.

**Where:** `server-v2/src/main/java/LeaderboardEndpoint.java` and `LeaderboardRelay.java`.

## 4. A decision I had to reason through

The main architectural decision was how to deliver a leaderboard update to clients connected to different ingest servers. A consumer process does not own the WebSocket sessions held in each Tomcat process. A single shared queue would distribute each update to one server, not broadcast a copy to every server with interested subscribers.

I used a fanout exchange with a separate broker-generated queue for each ingest server. The consumer publishes a complete top-N snapshot to `leaderboard.fanout`; each server's relay receives its own copy and forwards it to that server's local WebSocket sessions. This keeps ingest servers and the aggregation consumer independently scalable, at the cost of an extra broker path and one temporary queue per server.

I also had to distinguish a full snapshot from durable delivery. The final broadcaster does not use publisher confirms, and the relay consumes its per-server queue with auto-ack. A server can therefore miss an intermediate update. Since messages contain a full top-N snapshot, a later successful snapshot can repair the visible state, but a missed final snapshot with no later update can leave a subscriber stale. The implementation does not claim guaranteed delivery of every leaderboard transition.

## 5. A place where I corrected an AI suggestion

The unbounded channel-pool `take()` sketch was incomplete for a request-serving application. It describes how to wait for a free channel but does not define what happens when the broker is unavailable for an extended period. I compared that behavior with the requirement to keep the Tomcat request path responsive and used a bounded two-second wait with a controlled reconnect path instead. This was a case where a short code sketch needed more failure-mode reasoning before it was safe to use.

## 6. Testing and performance evidence

The following values are from the Assignment 2 README/results and report, not target values or estimates:

- **Broker reconnection:** the forced connection-drop test used 40,000 swipes. It reported zero failed swipes, 13 duplicate deliveries dropped by event-ID deduplication, and matching sent/applied totals.
- **Acknowledgement batching:** an early partial-batch shutdown/rescale case produced 250 extra applied swipes. After the acknowledgement flush correction, the rerun applied exactly 500,000.
- **Consumer concurrency:** the tested 5/10/15/20 worker configurations were broadly flat. The implementation capped useful concurrency at ten because there are ten ordered resort queues.
- **ALB warm-up:** a cold new-ALB run measured about 11,550 swipes/s per client with mean latency around 20.5 ms while hosts were mostly idle. Sustained warm-up traffic was needed before the larger ALB targets appeared and the final runs stabilized.
- **One-client ALB run:** the warmed four-server, one-million-swipe run with the client using ParallelGC reached about 42,032 swipes/s (45,019/s during the main phase), with mean/p99 latency around 5.5/12.7 ms. The rerun was about 41,846/s.
- **Client thread count:** in the final one-client comparison, 256 threads outperformed 384 and 512 threads. The 384- and 512-thread runs measured about 36,481/s and 34,097/s respectively.
- **Two-client run:** the four-server run with two clients submitting one million swipes each reached about 46,887 swipes/s overall (49,001/s during the main phase). It reported zero failed swipes, an average/peak queue depth around 284/433, and maximum consumer lag around 184 ms.
- **JVM tuning:** ZGC reduced queue-depth spikes associated with consumer garbage-collection pauses. The client-side ParallelGC configuration improved the best one-client million-swipe run from roughly 39.4K/s to roughly 42.0K/s.

The measurements show that throughput did not increase indefinitely with more client threads or more ingest servers. The broker became a limiting resource at the higher rates, and warm-up behavior materially affected the ALB measurements.

## 7. Limitations and takeaway

The most important limitation I identified is that the 50,000-event deduplication window is in memory and bounded per resort. It handled the duplicate copies observed in the connection-drop test, but it is not durable exactly-once processing across a consumer restart. A duplicate arriving after its ID has aged out of the window, or after process state is lost, can be applied again.

I also learned to separate the guarantees of each stage. Publisher confirmation establishes that RabbitMQ accepted a message; it does not prove that the consumer has processed it or that every WebSocket subscriber has received the latest snapshot. Queue-drain checks, aggregate counts, and end-to-end subscriber evidence are needed to validate those separate outcomes.

Overall, AI assistance was most useful for comparing design alternatives and identifying failure cases. I still had to check whether a proposal matched the actual code, preserve the assignment's ordering and throughput constraints, and interpret the measurements rather than assuming that more threads or another server would always improve performance.
