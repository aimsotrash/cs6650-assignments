import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.util.Map;

// the exchange and queue names, and declaring them. the server and the consumer both declare them like in the
// rabbit tutorials: whichever starts first creates them, declaring again with the same settings does nothing.
// consumer/src/main/java/Topology.java is a copy, keep the two the same (rabbit refuses a declare with other settings)
final class Topology {
  static final String SWIPE_EXCHANGE = "skitrak.exchange";
  static final String LEADERBOARD_FANOUT = "leaderboard.fanout";
  static final String DEAD_LETTER_EXCHANGE = "skitrak.dlx";
  static final String DEAD_LETTER_QUEUE = "skitrak.dead";
  static final int NUM_RESORTS = 10;

  private Topology() {}

  // queue name and routing key for a resort
  static String queue(int resortID) {
    return "resort." + resortID;
  }

  static void declare(Channel channel) throws IOException {
    channel.exchangeDeclare(SWIPE_EXCHANGE, "topic", true);
    channel.exchangeDeclare(LEADERBOARD_FANOUT, "fanout");

    // poison messages and expired swipes go here instead of being dropped
    channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, "fanout", true);
    channel.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, null);
    channel.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, "");

    // message TTL and a length limit. when a queue is full the broker nacks new publishes (reject-publish), so the
    // server answers 503 and the client retries. the default (drop the oldest message) would silently lose swipes
    Map<String, Object> args = Map.of(
        "x-message-ttl", 3600000,   // 1 hour
        "x-max-length", 200000,
        "x-overflow", "reject-publish",
        "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);
    for (int resortID = 1; resortID <= NUM_RESORTS; resortID++) {
      channel.queueDeclare(queue(resortID), true, false, false, args);
      channel.queueBind(queue(resortID), SWIPE_EXCHANGE, queue(resortID));
    }
  }
}
