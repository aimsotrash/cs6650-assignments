import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// the consumer app: Queue -> Consumer -> Aggregator -> Leaderboard Broadcaster -> (backplane) -> WebSocket servers
public class ConsumerApp {

  public static void main(String[] args) throws Exception {
    ConnectionFactory factory = Config.connectionFactory();

    // the rabbit client runs consumer callbacks on this pool, one thread per channel at most
    ExecutorService consumerThreads = Executors.newFixedThreadPool(Math.min(Config.MAX_THREADS, Topology.NUM_RESORTS));
    Connection connection = factory.newConnection(consumerThreads, "skitrak-consumer");
    Connection broadcastConnection = factory.newConnection("skitrak-consumer-broadcast");

    // this channel stays open: it declares the queues (so recovery can re-declare them) and reads queue depths
    Channel admin = connection.createChannel();
    Topology.declare(admin);

    Aggregator aggregator = new Aggregator();
    Broadcaster broadcaster = new Broadcaster(broadcastConnection);
    ConsumerPool pool = new ConsumerPool(connection, aggregator, broadcaster, Config.CONSUMER_THREADS, Config.MAX_THREADS);
    pool.start();

    // health check: 200 while the rabbit connection is up, 503 if not (systemd restarts the app if it dies)
    HttpServer health = HttpServer.create(new InetSocketAddress(Config.HEALTH_PORT), 0);
    health.createContext("/health", exchange -> {
      boolean up = connection.isOpen();
      byte[] body = ("{\"message\":\"" + (up ? "UP" : "DOWN") + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(up ? 200 : 503, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    health.start();

    // every 5 s: print progress and consumer lag, and scale the pool with the queue depth
    ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    long[] last = {0};
    monitor.scheduleAtFixedRate(() -> {
      try {
        long depth = 0;
        for (int resortID = 1; resortID <= Topology.NUM_RESORTS; resortID++) {
          depth += admin.messageCount(Topology.queue(resortID));
        }
        long processed = aggregator.swipesProcessed.get();
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " processed=" + processed
            + " rate=" + (processed - last[0]) / 5 + "/s lag " + aggregator.takeLag() + " queued=" + depth
            + " threads=" + pool.threads() + " duplicates=" + aggregator.duplicates.get()
            + " rejected=" + SwipeConsumer.rejected.get() + " broadcasts=" + broadcaster.published.get()
            + " broadcastFailures=" + broadcaster.failed.get() + " perServer=" + aggregator.swipesPerServer);
        last[0] = processed;
        pool.flushAcks();
        pool.checkLoad(depth);
      } catch (Exception e) {
        // e.g. while the connection is recovering; try again next time
        System.out.println("[monitor] " + e);
      }
    }, 5, 5, TimeUnit.SECONDS);

    // graceful shutdown (systemctl stop / Ctrl-C): stop taking messages, let the delivered ones finish, close
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      monitor.shutdownNow();
      health.stop(0);
      pool.stop();
      try {
        connection.close();
        broadcastConnection.close();
      } catch (Exception e) {
        // already closed
      }
      consumerThreads.shutdown();
      System.out.println("consumer stopped, processed " + aggregator.swipesProcessed.get() + " swipes");
    }));
  }
}
