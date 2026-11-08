import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.nio.charset.StandardCharsets;
import java.util.Map;

// the WebSocket side of the broadcast backplane (the publish-subscribe pattern from week 6).
// a WebSocket connection lives on one server, but the consumer that computes a resort's leaderboard runs on
// another host. so every server binds its own queue to the leaderboard fanout exchange: it gets every update
// from every consumer and pushes each one to the sessions it holds for that resort.
public class LeaderboardRelay {
  private volatile Connection connection;
  private volatile boolean stopped;

  // its own connection, not the publishing pool's: if rabbit blocks publishers (flow control), updates still arrive.
  // the client library's automatic recovery (on by default) reconnects it and re-creates the queue, binding and consumer
  public void start() {
    // automatic recovery only starts after a first successful connect, so keep trying until then
    Thread connector = new Thread(() -> {
      while (!stopped && connection == null) {
        try {
          connect();
        } catch (Exception e) {
          System.err.println("[LeaderboardRelay] can't reach rabbit (" + e + "), trying again in 5 s");
          try {
            Thread.sleep(5000);
          } catch (InterruptedException ie) {
            return;
          }
        }
      }
    });
    connector.setDaemon(true);
    connector.start();
  }

  private void connect() throws Exception {
    Connection c = Config.connectionFactory().newConnection("skitrak-ws-" + Config.SERVER_ID);
    try {
      Channel channel = c.createChannel();
      channel.exchangeDeclare(Topology.LEADERBOARD_FANOUT, "fanout");
      // a queue just for this server: named by the broker, deleted when this server disconnects.
      // capped: if this server falls behind, the oldest standings are dropped (a newer one is right behind them)
      String queueName = channel.queueDeclare("", false, true, true, Map.of("x-max-length", 1000)).getQueue();
      channel.queueBind(queueName, Topology.LEADERBOARD_FANOUT, "");
      // auto ack: every update is the whole top N, so if one gets lost the next one fixes it
      channel.basicConsume(queueName, true, (consumerTag, delivery) -> {
        // an exception here would make the client close this channel, and recovery wouldn't bring it back
        try {
          // the consumer publishes with routing key resort.{resortID}; a fanout ignores it but passes it along
          String resortID = delivery.getEnvelope().getRoutingKey().substring("resort.".length());
          LeaderboardEndpoint.push(resortID, new String(delivery.getBody(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
          System.err.println("[LeaderboardRelay] skipped a bad update: " + e);
        }
      }, consumerTag -> { });
    } catch (Exception e) {
      c.close();
      throw e;
    }
    connection = c;
  }

  public void stop() {
    stopped = true;
    try {
      if (connection != null) {
        connection.close();
      }
    } catch (Exception e) {
      // already closed
    }
  }
}
