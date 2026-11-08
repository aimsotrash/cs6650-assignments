import com.google.gson.Gson;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import model.LeaderboardMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

// publishes leaderboard updates to the leaderboard fanout exchange (the backplane). every WebSocket server has its
// own queue bound to it and pushes the update to the subscribers it holds for that resort
public class Broadcaster {
  private static final Gson gson = new Gson();

  // on a separate connection from the consumers, so the consumers' acks keep flowing if rabbit blocks publishers
  private final Channel channel;
  // rabbit blocks publishing connections during a memory/disk alarm, and a publish would then wait forever,
  // holding up every consumer thread so the queues never drain and the alarm never clears. so while the
  // connection is blocked, updates are skipped (the next one after the alarm has the whole top N anyway)
  private volatile boolean blocked;
  final AtomicLong published = new AtomicLong();
  final AtomicLong failed = new AtomicLong();

  public Broadcaster(Connection connection) throws IOException {
    connection.addBlockedListener(reason -> blocked = true, () -> blocked = false);
    this.channel = connection.createChannel();
  }

  // called by every consumer thread. a channel can't be used by two threads at once, so publishes take turns
  // ponytail: one lock for all resorts, a channel per consumer thread if broadcasting ever becomes the bottleneck
  public void publish(String resortID, LeaderboardMessage update) {
    if (blocked) {
      failed.incrementAndGet();
      return;
    }
    byte[] body = gson.toJson(update).getBytes(StandardCharsets.UTF_8);
    synchronized (this) {
      try {
        // the routing key tells the WebSocket servers which resort it is (a fanout ignores it for routing)
        channel.basicPublish(Topology.LEADERBOARD_FANOUT, "resort." + resortID, null, body);
        published.incrementAndGet();
      } catch (IOException | RuntimeException e) {
        // not fatal: every update is the whole top N, the next one fixes it
        failed.incrementAndGet();
      }
    }
  }
}
