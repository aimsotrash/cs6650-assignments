# Server (skitrak.war)

One WAR deployed to Tomcat 9. It's my Lab 2 servlet with stricter validation, a health endpoint, and a WebSocket leaderboard that pushes a fake top 10 every 500 ms.

## Endpoints

All URLs start with `/skitrak` (the WAR is named `skitrak.war`).

| Endpoint | What it does |
|---|---|
| `POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}` | Records a swipe. Body: `{"time": 217, "liftID": 21}`. Returns **201** if valid, **400** if not |
| `GET /health` | Returns `{"message":"UP"}` |
| `ws://.../leaderboard/{resortID}` | WebSocket. Every 500 ms the server pushes a fake top 10 for that resort with a `serverTimestamp` |

**Validation (400 if it fails):** all four IDs must be positive integers, `time` must be 1–360, `liftID` must be 1–40, and the body must be valid JSON.

## Classes

- `SkierServlet`: the swipe endpoint (the Lab 2 servlet, without the per-request logging so it doesn't slow down)
- `HealthServlet`: the `/health` endpoint
- `LeaderboardEndpoint`: the WebSocket. Keeps a map of resortID to open sessions, removes sessions on close or error, and uses one timer thread to push
- `AppListener`: stops the leaderboard timer when the app is redeployed, otherwise an old timer keeps running
- `model/`: `LiftRide`, `ResponseMsg`, `LeaderboardEntry`, `LeaderboardMessage`

## Build

```bash
mvn clean package
```

Output: `target/skitrak.war`

## Run locally (IntelliJ)

Add a Tomcat 9 run configuration, deploy the `skitrak-server:war exploded` artifact with the application context `/skitrak`, and run it. Then:

```bash
curl http://localhost:8080/skitrak/health
```

## Deploy to EC2

Same setup as Lab 1:

1. Launch a t3.micro with Amazon Linux 2023 in `us-west-2`. The security group allows ports 22 and 8080 from my IP only.
2. Install Java 11 (`sudo yum install -y java-11-amazon-corretto-headless`) and Tomcat 9 in `/opt/tomcat9`, owned by `ec2-user`. I run it as a systemd service called `tomcat`.
3. Copy the WAR into `webapps`. Tomcat deploys it by itself in a few seconds. I didn't have a `.pem` key on my laptop, so I used EC2 Instance Connect: make a temporary key once, push it to the instance with the AWS CLI (it only works for 60 seconds), and copy the WAR with it right away:

```bash
ssh-keygen -t ed25519 -f eic -N ""
```

```bash
aws ec2-instance-connect send-ssh-public-key --region us-west-2 --instance-id YOUR_INSTANCE_ID --instance-os-user ec2-user --ssh-public-key file://eic.pub
```

```bash
scp -i eic target/skitrak.war ec2-user@YOUR_EC2_IP:/opt/tomcat9/webapps/skitrak.war
```

If you have a `.pem` key, just use it instead: `scp -i your-key.pem target/skitrak.war ec2-user@YOUR_EC2_IP:/opt/tomcat9/webapps/skitrak.war`

4. Check it:

```bash
curl http://YOUR_EC2_IP:8080/skitrak/health
```

To redeploy, copy the new WAR over the old one. `AppListener` stops the old leaderboard timer.

## Test it

Valid swipe (201):

```bash
curl -i -X POST "http://YOUR_EC2_IP:8080/skitrak/skiers/1/seasons/2026/days/1/skiers/123" -H "Content-Type: application/json" -d '{"time": 217, "liftID": 21}'
```

Invalid swipe, liftID 41 (400):

```bash
curl -i -X POST "http://YOUR_EC2_IP:8080/skitrak/skiers/1/seasons/2026/days/1/skiers/123" -H "Content-Type: application/json" -d '{"time": 100, "liftID": 41}'
```

Leaderboard: in Postman, New → WebSocket, connect to `ws://YOUR_EC2_IP:8080/skitrak/leaderboard/7`. A new message shows up about twice a second.

**Note:** AWS Academy gives the instance a new public IP every time the lab restarts, so update the IP in the commands.
