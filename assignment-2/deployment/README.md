# Deployment

Everything runs on EC2 in `us-west-2` (AWS Academy Learner Lab), Amazon Linux 2023, all in `us-west-2a`.
Same setup as Lab 1 / Assignment 1: Corretto 11, Tomcat 9 in `/opt/tomcat9` (systemd service `tomcat`), SSH with
EC2 Instance Connect. No containers.

Instance types: first everything on **t3.micro**; the final (fastest) runs had every host on **m8azn.large**.
The Learner Lab only allows sizes up to `.large` (bigger ones are terminated; at most 9 instances and 32 vCPUs), and
m8azn.large is the fastest `.large` (2 full AMD cores at 5.0 GHz, 8 GB). To change a type: stop the instance,
`aws ec2 modify-instance-attribute --instance-id <id> --instance-type '{"Value": "m8azn.large"}'`, start it. The private
IP and the disk stay, so nothing else changes, but the public IP changes (update the ALB security group rule for the client).

| Host | What runs there | Security group (inbound) |
|---|---|---|
| `cs6650-a2-rabbitmq` | RabbitMQ 4.4 (native RPM install), management UI | `cs6650-a2-rabbitmq-sg`: 5672 from `cs6650-lab1-sg`; 22 and 15672 from my IP |
| `cs6650-a1-server` + 3 copies | Tomcat 9 + `skitrak.war` (server-v2) | `cs6650-lab1-sg`: 8080 from the ALB SG and the client SG; 22, 8080 from my IP |
| `cs6650-a2-consumer` | `consumer.jar` as the systemd service `skitrak-consumer` | `cs6650-lab1-sg` |
| `cs6650-a2-client` (+ a second one for the 2-client runs) | the Assignment 1 client (`client-part2.jar`) | `cs6650-a1-client-sg`: 22 from my IP |
| `cs6650-a2-alb` | Application Load Balancer, HTTP :80 -> servers :8080 | `cs6650-a2-alb-sg`: 80 from the client's public IP and my IP |

## Order

1. **Broker.** Launch the instance, copy `rabbitmq-setup.sh` over and run `sudo RABBIT_PASS=<password> bash rabbitmq-setup.sh`.
   It adds rabbitmq.com's el9 repositories (the ones for Amazon Linux 2023), installs Erlang + RabbitMQ,
   enables the management plugin (http://BROKER:15672) and creates the `skitrak` user (`guest` only works from localhost).
   The exchanges and queues are declared by the apps themselves (`Topology.java`), not here:
   - `skitrak.exchange` (topic), routing key `resort.{resortID}`, durable queues `resort.1` .. `resort.10`,
     each with `x-message-ttl` 1 h, `x-max-length` 200,000, `x-overflow: reject-publish` (a full queue nacks the
     publish, so the server answers 503 and the client retries, instead of dropping the oldest swipe) and
     `x-dead-letter-exchange: skitrak.dlx` -> queue `skitrak.dead` (poison messages and expired swipes).
   - `leaderboard.fanout` (fanout): the backplane. Each server binds its own broker-named queue to it.
2. **Consumer.** Fill in `consumer.env` (broker private IP, password), copy it with `consumer.jar`,
   `skitrak-consumer.service` and `consumer-setup.sh`, run `sudo bash consumer-setup.sh`.
   Health check: `curl localhost:8081/health`. Logs (a line every 5 s with rate, lag, queue depth, threads):
   `journalctl -u skitrak-consumer -f`. Change a setting: edit `/etc/skitrak-consumer.env`, `sudo systemctl restart skitrak-consumer`.
   Final settings: `PREFETCH=100`, `ACK_BATCH=50`, 10 threads, and on m8azn.large
   `JAVA_OPTS=-Xms2g -Xmx2g -XX:+UnlockExperimentalVMOptions -XX:+UseZGC` (ZGC removed the GC-pause queue spikes);
   on a 1 GB t3.micro keep `JAVA_OPTS=-Xmx512m`.
3. **Ingest server.** On the Assignment 1 server: put the filled-in `setenv.sh` in `/opt/tomcat9/bin/`,
   copy `skitrak.war` to `/opt/tomcat9/webapps/`, `sudo systemctl restart tomcat`.
   Then an AMI of it (`cs6650-a2-server`) for the other servers (each one gets its own hostname, which becomes its `serverId`).
4. **Load balancer.** `CLIENT_IP=<client public IP> bash alb-setup.sh <server instance ids>`: target group on 8080 with
   the health check `/skitrak/health` (interval 30 s, timeout 5 s, healthy 2, unhealthy 3), sticky sessions with the
   ALB cookie (`AWSALB`), idle timeout 120 s, listener HTTP :80. The ALB is internet-facing, so even from inside the
   VPC its name resolves to public IPs and the client's requests arrive from the client's public IP: a security group
   rule naming the client's security group doesn't match them (my first 2-server run hung on exactly that). The public
   IP changes whenever the lab restarts the instance, so the rule has to be updated then.

   **Warm the ALB up before measuring.** A new ALB starts with small nodes: my first runs on a new ALB were stuck at
   about 11.5K swipes/s per client at 20 ms, with every host mostly idle. Sustained traffic makes it add bigger nodes
   (new IPs; in my test 2 clients x 4M swipes, the new nodes appeared after 3-5 minutes and DNS switched to them).
   The Java client resolves the ALB's name once and keeps its connections on the nodes it got, so only a client
   started *after* the scale-up uses the new nodes: run the warm-up, then start the measured runs. After that the
   same setup gave 34-42K swipes/s with one client instead of 11.5K.
5. **Client.** `sudo dnf install -y java-11-amazon-corretto-headless`, copy `client-part2.jar` (Assignment 1, unchanged), run
   `java -jar client-part2.jar http://<ALB DNS or server private IP>/skitrak <threads> <swipes> <output folder>`.
   The fastest runs used 256 threads (Tomcat serves at most 200 requests at once, so more threads only queue),
   `CHANNEL_POOL_SIZE=64` on each server, and the client JVM on the parallel garbage collector
   (`java -XX:+UseParallelGC -jar client-part2.jar ...`, the jar unchanged): the client host is CPU-bound at about
   40K swipes/s and G1 costs it more CPU. For more load than one client host can send, run the same command on a
   second client host at the same time (and allow its public IP in the ALB security group).

Start the broker first, then the consumer, then the servers. (A server that starts before the broker is reachable still
comes up: the channel pool connects on the first swipe and the backplane listener retries every 5 s.)
