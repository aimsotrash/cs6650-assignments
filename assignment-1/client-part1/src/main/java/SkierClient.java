import java.lang.management.ManagementFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class SkierClient {
  static final int TOTAL_SWIPES = 500000;
  static final int WARMUP_THREADS = 32;
  static final int WARMUP_SWIPES_PER_THREAD = 1000;
  static final int SEASON_ID = 2026;
  static final int DAY_ID = 1;

  // resortID is random 1 to numResorts (10 unless you pass a 4th arg)
  static int numResorts = 10;

  // shared counters, updated by all the sender threads
  static AtomicInteger successful = new AtomicInteger();
  static AtomicInteger failed = new AtomicInteger();
  static AtomicInteger retries = new AtomicInteger();
  static AtomicInteger serverClosedConnections = new AtomicInteger();

  public static void main(String[] args) throws Exception {
    // args: [server url] [main phase threads] [total swipes - only change this for testing] [number of resorts]
    String serverUrl = args.length > 0 ? args[0] : "http://localhost:8080/skitrak";
    int mainThreads = args.length > 1 ? Integer.parseInt(args[1]) : 200;
    int totalSwipes = args.length > 2 ? Integer.parseInt(args[2]) : TOTAL_SWIPES;
    numResorts = args.length > 3 ? Integer.parseInt(args[3]) : 10;

    // the warmup alone sends 32 x 1000, so fewer swipes would leave the warmup threads waiting forever
    if (totalSwipes < WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD) {
      System.out.println("Total swipes must be at least " + WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD);
      return;
    }

    BlockingQueue<SwipeEvent> queue = new LinkedBlockingQueue<>(10000);

    // one HttpClient for all threads, it keeps connections open and reuses them
    HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    // 1 thread makes all the swipes
    Thread generator = new Thread(new SwipeGenerator(queue, totalSwipes));
    generator.start();

    // one leaderboard subscriber per resort, connected for the whole run
    List<LeaderboardSubscriber> subscribers = new ArrayList<>();
    for (int resortID = 1; resortID <= numResorts; resortID++) {
      LeaderboardSubscriber subscriber = new LeaderboardSubscriber(serverUrl, resortID);
      subscriber.connect();
      subscribers.add(subscriber);
    }

    long start = System.currentTimeMillis();

    // warmup: 32 threads, 1000 swipes each
    CountDownLatch warmupDone = new CountDownLatch(WARMUP_THREADS);
    for (int i = 0; i < WARMUP_THREADS; i++) {
      new Thread(new SwipeSender(serverUrl, client, queue, WARMUP_SWIPES_PER_THREAD, warmupDone)).start();
    }
    warmupDone.await();
    long warmupTime = System.currentTimeMillis() - start;

    // main phase: split the rest of the swipes between mainThreads threads
    int remaining = Math.max(0, totalSwipes - WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD);
    CountDownLatch mainDone = new CountDownLatch(mainThreads);
    long mainStart = System.currentTimeMillis();
    long cpuStart = cpuTime();
    for (int i = 0; i < mainThreads; i++) {
      int count = remaining / mainThreads;
      if (i < remaining % mainThreads) {
        count++;
      }
      new Thread(new SwipeSender(serverUrl, client, queue, count, mainDone)).start();
    }
    mainDone.await();
    long end = System.currentTimeMillis();
    long cpuMain = cpuTime() - cpuStart;

    for (LeaderboardSubscriber subscriber : subscribers) {
      subscriber.stop();
    }

    long totalTime = end - start;
    long mainTime = end - mainStart;
    int warmupSwipes = WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD;

    System.out.println("===== Ingest =====");
    System.out.println("Server: " + serverUrl);
    System.out.println("Successful swipes: " + successful.get());
    System.out.println("Failed swipes: " + failed.get());
    System.out.println("Warmup (" + WARMUP_THREADS + " threads x " + WARMUP_SWIPES_PER_THREAD + "): "
        + warmupTime + " ms, " + (warmupSwipes * 1000L / warmupTime) + " swipes/s");
    System.out.println("Main phase (" + mainThreads + " threads): " + mainTime + " ms, "
        + (mainTime > 0 ? remaining * 1000L / mainTime : 0) + " swipes/s");
    System.out.println("Total run time: " + totalTime + " ms");
    System.out.println("Overall throughput: " + (successful.get() * 1000L / totalTime) + " swipes/s");
    System.out.println("HTTP: 1 shared HttpClient (keep-alive), up to " + Math.max(WARMUP_THREADS, mainThreads)
        + " connections open at once, " + serverClosedConnections.get()
        + " reopened after the server closed them (Connection: close), " + retries.get() + " retries");
    printCpu(cpuMain, mainTime, remaining);

    System.out.println("===== Leaderboard =====");
    int totalReconnects = 0;
    long connectTimeSum = 0;
    int connectCount = 0;
    long latencySum = 0;
    long latencyMax = 0;
    int latencyCount = 0;
    for (LeaderboardSubscriber subscriber : subscribers) {
      System.out.println("Resort " + subscriber.getResortID() + ": " + subscriber.getUpdates()
          + " updates, connect time " + subscriber.getConnectTimes().get(0) + " ms");
      totalReconnects += subscriber.getReconnects();
      for (long t : subscriber.getConnectTimes()) {
        connectTimeSum += t;
        connectCount++;
      }
      for (long lat : subscriber.getPushLatencies()) {
        latencySum += lat;
        latencyMax = Math.max(latencyMax, lat);
        latencyCount++;
      }
    }
    System.out.println("WebSocket connections: " + connectCount + " (reconnections: " + totalReconnects + ")");
    System.out.println("Average WebSocket connect time: " + (connectTimeSum / connectCount) + " ms");
    if (latencyCount > 0) {
      System.out.println("Push latency: mean " + (latencySum / latencyCount) + " ms, max " + latencyMax
          + " ms (" + latencyCount + " updates)");
    }

    // the WebSocket client keeps some threads running, so exit when we're done
    System.exit(0);
  }

  // CPU time used by all the threads of this client so far, in nanoseconds
  static long cpuTime() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
  }

  // how busy the client CPU was in the main phase, and the throughput it could reach at 100% CPU
  static void printCpu(long cpuNanos, long mainTimeMs, int mainSwipes) {
    if (mainTimeMs <= 0 || mainSwipes == 0) {
      return;
    }
    int cores = Runtime.getRuntime().availableProcessors();
    double busy = 100.0 * cpuNanos / (mainTimeMs * 1_000_000.0 * cores);
    double microsPerSwipe = cpuNanos / 1000.0 / mainSwipes;
    System.out.printf("Client CPU (main phase): %.0f%% busy on %d cores, %.0f us of CPU per swipe"
        + " -> limit at 100%% CPU about %,d swipes/s%n", busy, cores, microsPerSwipe, (long) (cores * 1_000_000 / microsPerSwipe));
  }
}
