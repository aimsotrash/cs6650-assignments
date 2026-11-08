import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import model.LeaderboardMessage;
import model.SwipeMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

// consumes one resort's queue (the "consumer push" pattern from week 6, with manual acks).
// for each swipe: dedupe + aggregate, broadcast the leaderboard if the top N changed, then ack.
// the ack comes after the swipe was counted, so a crash before that means rabbit delivers it again (at least once),
// and the dedupe on eventId keeps the second delivery from being counted twice
public class SwipeConsumer extends DefaultConsumer {
  private static final Gson gson = new Gson();
  static final AtomicLong rejected = new AtomicLong();

  // ack bookkeeping for one channel, shared by the consumers on it. the channel's thread updates it for every
  // message; the monitor thread flushes it every 5 s, so access is synchronized
  static final class Acks {
    long handled;   // newest delivery tag that was aggregated
    long acked;     // newest tag covered by an ack (multiple = true)
    int pending;    // handled since the last ack

    // ack everything handled since the last ack: called every 5 s, so the end of a batch doesn't sit unacked
    // once a queue goes quiet, and on stop/rescale
    synchronized void flush(Channel channel) throws IOException {
      if (handled > acked) {
        channel.basicAck(handled, true);
        acked = handled;
        pending = 0;
      }
    }
  }

  private final Aggregator aggregator;
  private final Broadcaster broadcaster;
  private final int ackBatch;
  private final Acks acks;
  private final CountDownLatch cancelled = new CountDownLatch(1);

  public SwipeConsumer(Channel channel, Aggregator aggregator, Broadcaster broadcaster, int ackBatch, Acks acks) {
    super(channel);
    this.aggregator = aggregator;
    this.broadcaster = broadcaster;
    this.ackBatch = ackBatch;
    this.acks = acks;
  }

  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
      throws IOException {
    long tag = envelope.getDeliveryTag();

    SwipeMessage swipe;
    try {
      swipe = gson.fromJson(new String(body, StandardCharsets.UTF_8), SwipeMessage.class);
      swipe.validate();
    } catch (RuntimeException e) {
      // can't be read, retrying won't help: reject it and the queue dead-letters it to skitrak.dead
      rejected.incrementAndGet();
      getChannel().basicReject(tag, false);
      return;
    }

    try {
      LeaderboardMessage update = aggregator.apply(swipe);
      if (update != null) {
        broadcaster.publish(swipe.getResortID(), update);
      }
    } catch (RuntimeException e) {
      // poison message (week 6): requeue it once, if it fails again dead-letter it
      rejected.incrementAndGet();
      getChannel().basicNack(tag, false, !envelope.isRedeliver());
      return;
    }

    // ack every ackBatch messages (multiple = true acks everything up to this tag on the channel; the channel's
    // messages are handled one at a time in order, so everything before this tag is already done).
    // the rest of a batch is acked by the 5 s flush (Acks.flush); after a crash it is redelivered
    synchronized (acks) {
      acks.handled = tag;
      if (++acks.pending >= ackBatch) {
        getChannel().basicAck(tag, true);
        acks.acked = tag;
        acks.pending = 0;
      }
    }
  }

  @Override
  public void handleCancelOk(String consumerTag) {
    cancelled.countDown();
  }

  // stop getting messages and wait until the ones already delivered to us are handled: the client calls
  // handleCancelOk only after every earlier delivery on this channel
  void cancelAndWait() {
    try {
      getChannel().basicCancel(getConsumerTag());
      cancelled.await(10, TimeUnit.SECONDS);
    } catch (IOException | RuntimeException e) {
      // channel already closed: its unacked messages go back to the queue
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
