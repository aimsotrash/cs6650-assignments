import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// one connection to rabbit, and a fixed set of channels that request threads borrow and give back,
// so a channel is only ever used by one thread at a time and we never open a channel per publish.
// every channel is in confirm mode, so the thread holding it can wait for the broker to confirm its own message.
//
// reconnecting is done here, not by the client library (automatic recovery is turned off):
// if the connection drops, the next borrow opens a new connection and a new pool of channels.
public class ChannelPool {
  public static final String ATTR = "channelPool";   // ServletContext attribute name

  // after a failed connect, don't try again for this long. while the broker is down, requests fail
  // straight away with 503 instead of every request thread waiting on its own connect attempt
  private static final long RECONNECT_WAIT_MS = 1000;
  // all channels busy for this long -> give up, the request gets a 503
  private static final long BORROW_TIMEOUT_MS = 2000;

  private final ConnectionFactory factory;
  private final int size;

  private volatile Connection connection;
  private volatile BlockingQueue<Channel> pool;
  private long lastConnectAttempt;

  public ChannelPool(ConnectionFactory factory, int size) {
    this.factory = factory;
    this.size = size;
    factory.setAutomaticRecoveryEnabled(false);
  }

  // one connection, all channels created up front. also (re)declares the exchange and resort queues on every
  // connect, so publishing works even on a fresh broker the consumer hasn't connected to yet
  public synchronized void init() throws IOException, TimeoutException {
    lastConnectAttempt = System.currentTimeMillis();
    Connection c = factory.newConnection("skitrak-producer-" + Config.SERVER_ID);
    try {
      BlockingQueue<Channel> channels = new ArrayBlockingQueue<>(size);
      for (int i = 0; i < size; i++) {
        channels.add(newChannel(c));
      }
      Topology.declare(channels.peek());
      pool = channels;
      connection = c;
    } catch (IOException e) {
      closeQuietly(c);
      throw e;
    }
    c.addShutdownListener(cause -> {
      if (!cause.isInitiatedByApplication()) {
        System.err.println("[ChannelPool] connection to rabbit lost: " + cause.getMessage());
      }
    });
  }

  public Channel borrowChannel() throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BORROW_TIMEOUT_MS);
    while (true) {
      ensureConnected();
      // short polls, so a thread waiting on the old pool notices when a reconnect made a new one
      Channel ch = pool.poll(50, TimeUnit.MILLISECONDS);
      if (ch != null) {
        if (ch.isOpen()) {
          return ch;
        }
        replace(ch);   // it died while sitting in the pool
        continue;
      }
      if (System.nanoTime() > deadline) {
        throw new IOException("no free channel after " + BORROW_TIMEOUT_MS + " ms");
      }
    }
  }

  public void returnChannel(Channel ch) {
    if (ch.isOpen()) {
      if (ch.getConnection() != connection || !pool.offer(ch)) {
        closeQuietly(ch);
      }
      return;
    }
    // the channel was closed (waitForConfirmsOrDie closes it on a nack or timeout, or the broker closed it)
    replace(ch);
  }

  public synchronized void close() {
    closeQuietly(connection);
  }

  private boolean isConnected() {
    Connection c = connection;
    return c != null && c.isOpen();
  }

  private void ensureConnected() throws IOException {
    if (isConnected()) {
      return;
    }
    synchronized (this) {
      if (isConnected()) {
        return;   // another thread reconnected while we waited for the lock
      }
      if (System.currentTimeMillis() - lastConnectAttempt < RECONNECT_WAIT_MS) {
        throw new IOException("rabbit unavailable");
      }
      closeQuietly(connection);
      try {
        init();
      } catch (TimeoutException e) {
        throw new IOException(e);
      }
      System.err.println("[ChannelPool] reconnected to rabbit, " + size + " channels");
    }
  }

  // put a new channel in place of a dead one, so the pool stays the same size.
  // if the whole connection is gone there is nothing to do: the next borrow reconnects and refills the pool
  private void replace(Channel dead) {
    Connection c = connection;
    if (dead.getConnection() != c || !c.isOpen()) {
      return;
    }
    try {
      pool.offer(newChannel(c));
    } catch (IOException e) {
      System.err.println("[ChannelPool] could not replace a channel: " + e.getMessage());
    }
  }

  private static Channel newChannel(Connection c) throws IOException {
    Channel ch = c.createChannel();
    ch.confirmSelect();
    return ch;
  }

  private static void closeQuietly(AutoCloseable c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (Exception e) {
      // already closed
    }
  }
}
