import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// the consumer threads. each thread has its own channel (channels aren't shared between threads) and consumes a
// subset of the resort queues, given out round robin: with 4 threads, thread 1 gets resorts 1, 5, 9, thread 2 gets
// 2, 6, 10, and so on. a resort's queue is only ever consumed on one channel, and the rabbit client hands a channel's
// messages to one thread at a time, in order, so every resort's swipes are applied in the order they were queued.
// that also means more threads than resorts can't help: the pool never goes above 10.
public class ConsumerPool {
  // scale up when this many swipes are waiting (the assignment's target is a queue depth under 1000),
  // scale back down after 6 checks (30 s) in a row with less than SCALE_DOWN_DEPTH waiting
  private static final long SCALE_UP_DEPTH = 1000;
  private static final long SCALE_DOWN_DEPTH = 100;
  private static final int CALM_CHECKS = 6;

  private final Connection connection;
  private final Aggregator aggregator;
  private final Broadcaster broadcaster;
  private final int minThreads;
  private final int maxThreads;

  private final List<Channel> channels = new ArrayList<>();
  private final List<SwipeConsumer> consumers = new ArrayList<>();
  private final List<SwipeConsumer.Acks> acks = new ArrayList<>();   // one per channel
  private int calmChecks;

  public ConsumerPool(Connection connection, Aggregator aggregator, Broadcaster broadcaster, int minThreads, int maxThreads) {
    this.connection = connection;
    this.aggregator = aggregator;
    this.broadcaster = broadcaster;
    this.minThreads = Math.min(minThreads, Topology.NUM_RESORTS);
    this.maxThreads = Math.max(this.minThreads, Math.min(maxThreads, Topology.NUM_RESORTS));
  }

  public synchronized void start() throws IOException {
    start(minThreads);
  }

  private void start(int threads) throws IOException {
    // ack batches can't be bigger than prefetch, or rabbit stops sending before a batch is full
    int ackBatch = Math.min(Config.ACK_BATCH, Config.PREFETCH);
    for (int i = 0; i < threads; i++) {
      Channel channel = connection.createChannel();
      channel.basicQos(Config.PREFETCH);
      channels.add(channel);
      acks.add(new SwipeConsumer.Acks());
    }
    for (int resortID = 1; resortID <= Topology.NUM_RESORTS; resortID++) {
      int thread = (resortID - 1) % threads;
      Channel channel = channels.get(thread);
      SwipeConsumer consumer = new SwipeConsumer(channel, aggregator, broadcaster, ackBatch, acks.get(thread));
      channel.basicConsume(Topology.queue(resortID), false, consumer);
      consumers.add(consumer);
    }
    System.out.println("[ConsumerPool] " + threads + " consumer threads for " + Topology.NUM_RESORTS + " resort queues");
  }

  // cancel every consumer and wait for the messages already delivered to be handled, then close the channels,
  // so nothing is half done when a new set of threads starts (that keeps the per-resort order across a rescale)
  public synchronized void stop() {
    for (SwipeConsumer consumer : consumers) {
      consumer.cancelAndWait();
    }
    for (int i = 0; i < channels.size(); i++) {
      try {
        // ack what's left of the last ack batch: otherwise those (already counted) messages are redelivered,
        // and after a restart, with the in-memory dedupe gone, they'd be counted again
        acks.get(i).flush(channels.get(i));
        channels.get(i).close();
      } catch (Exception e) {
        // already closed; anything unacked on it goes back to the queue
      }
    }
    consumers.clear();
    channels.clear();
    acks.clear();
  }

  // called every 5 s by the monitor: acks the end of each channel's current ack batch
  public synchronized void flushAcks() {
    for (int i = 0; i < channels.size(); i++) {
      try {
        acks.get(i).flush(channels.get(i));
      } catch (Exception e) {
        // channel closed or recovering; its unacked messages are redelivered and the dedupe skips them
      }
    }
  }

  public synchronized int threads() {
    return channels.size();
  }

  // dynamic scaling, called every 5 s with the number of swipes waiting in the resort queues
  public synchronized void checkLoad(long queueDepth) throws IOException {
    int threads = channels.size();
    if (queueDepth > SCALE_UP_DEPTH && threads < maxThreads) {
      rescale(Math.min(maxThreads, threads * 2), queueDepth);
    } else if (queueDepth < SCALE_DOWN_DEPTH && threads > minThreads) {
      if (++calmChecks >= CALM_CHECKS) {
        rescale(Math.max(minThreads, threads / 2), queueDepth);
      }
    } else {
      calmChecks = 0;
    }
  }

  private void rescale(int threads, long queueDepth) throws IOException {
    System.out.println("[ConsumerPool] queue depth " + queueDepth + ": going from " + channels.size() + " to " + threads + " threads");
    calmChecks = 0;
    stop();
    start(threads);
  }
}
