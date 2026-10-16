import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

// same client as part 1, plus latency for every swipe, statistics, push latency percentiles and a chart
public class SkierClient {
  static final int TOTAL_SWIPES = 500000;
  static final int WARMUP_THREADS = 32;
  static final int WARMUP_SWIPES_PER_THREAD = 1000;
  static final int SEASON_ID = 2026;
  static final int DAY_ID = 1;

  // resortID is random 1 to numResorts (10 unless you pass a 5th arg)
  static int numResorts = 10;

  // shared counters, updated by all the sender threads
  static AtomicInteger successful = new AtomicInteger();
  static AtomicInteger failed = new AtomicInteger();
  static AtomicInteger retries = new AtomicInteger();
  static AtomicInteger serverClosedConnections = new AtomicInteger();

  public static void main(String[] args) throws Exception {
    // args: [server url] [main phase threads] [total swipes] [output folder] [number of resorts]
    String serverUrl = args.length > 0 ? args[0] : "http://localhost:8080/skitrak";
    int mainThreads = args.length > 1 ? Integer.parseInt(args[1]) : 200;
    int totalSwipes = args.length > 2 ? Integer.parseInt(args[2]) : TOTAL_SWIPES;
    String outputFolder = args.length > 3 ? args[3] : "results";
    numResorts = args.length > 4 ? Integer.parseInt(args[4]) : 10;

    // the warmup alone sends 32 x 1000, so fewer swipes would leave the warmup threads waiting forever
    if (totalSwipes < WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD) {
      System.out.println("Total swipes must be at least " + WARMUP_THREADS * WARMUP_SWIPES_PER_THREAD);
      return;
    }

    new File(outputFolder).mkdirs();

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

    // keep every sender so we can get their records at the end
    List<SwipeSender> senders = new ArrayList<>();

    long start = System.currentTimeMillis();

    // warmup: 32 threads, 1000 swipes each
    CountDownLatch warmupDone = new CountDownLatch(WARMUP_THREADS);
    for (int i = 0; i < WARMUP_THREADS; i++) {
      SwipeSender sender = new SwipeSender(serverUrl, client, queue, WARMUP_SWIPES_PER_THREAD, warmupDone);
      senders.add(sender);
      new Thread(sender).start();
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
      SwipeSender sender = new SwipeSender(serverUrl, client, queue, count, mainDone);
      senders.add(sender);
      new Thread(sender).start();
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

    // ---------- same output as part 1 ----------
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

    // ---------- part 2: latency of every swipe ----------
    // put all the records together, then write the CSV with one writer (like lab 2 exercise 3)
    List<RequestRecord> allRecords = new ArrayList<>();
    for (SwipeSender sender : senders) {
      allRecords.addAll(sender.getRecords());
    }

    String csvFile = outputFolder + "/swipe_latencies.csv";
    try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(csvFile)))) {
      writer.println("timestamp,requestType,latency,statusCode,resortID");
      for (RequestRecord r : allRecords) {
        writer.println(r.getStartTime() + ",SWIPE," + ms(r.getLatencyNanos()) + "," + r.getStatusCode() + "," + r.getResortID());
      }
    }

    long[] latencies = new long[allRecords.size()];
    long latencySum = 0;
    for (int i = 0; i < allRecords.size(); i++) {
      latencies[i] = allRecords.get(i).getLatencyNanos();
      latencySum += latencies[i];
    }
    Arrays.sort(latencies);

    System.out.println("===== Ingest latency (ms) =====");
    System.out.println("Requests: " + latencies.length);
    System.out.printf("Mean: %.3f%n", latencySum / 1e6 / latencies.length);
    System.out.println("Median: " + ms(percentile(latencies, 50)));
    System.out.println("p95: " + ms(percentile(latencies, 95)));
    System.out.println("p99: " + ms(percentile(latencies, 99)));
    System.out.println("Min: " + ms(latencies[0]));
    System.out.println("Max: " + ms(latencies[latencies.length - 1]));

    // throughput per resort
    int[] perResort = new int[numResorts + 1];
    for (RequestRecord r : allRecords) {
      if (r.getStatusCode() == 201) {
        perResort[r.getResortID()]++;
      }
    }
    System.out.println("===== Throughput per resort =====");
    for (int resortID = 1; resortID <= numResorts; resortID++) {
      System.out.println("Resort " + resortID + ": " + perResort[resortID] + " swipes, "
          + (perResort[resortID] * 1000L / totalTime) + " swipes/s");
    }

    // ---------- throughput over time, in 10 second buckets ----------
    int numBuckets = (int) (totalTime / 10000) + 1;
    int[] bucketCounts = new int[numBuckets];
    for (RequestRecord r : allRecords) {
      if (r.getStatusCode() == 201) {
        long finished = r.getStartTime() + r.getLatencyNanos() / 1_000_000 - start;
        int bucket = (int) (finished / 10000);
        if (bucket >= numBuckets) {
          bucket = numBuckets - 1;
        }
        bucketCounts[bucket]++;
      }
    }

    double[] rates = new double[numBuckets];
    String throughputFile = outputFolder + "/throughput.csv";
    try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(throughputFile)))) {
      writer.println("secondsFromStart,swipesPerSecond");
      for (int i = 0; i < numBuckets; i++) {
        // the last bucket is usually shorter than 10 seconds
        double seconds = 10.0;
        if (i == numBuckets - 1) {
          seconds = (totalTime - i * 10000L) / 1000.0;
        }
        rates[i] = seconds > 0 ? bucketCounts[i] / seconds : 0;
        writer.printf("%d,%.1f%n", i * 10, rates[i]);
      }
    }
    String chartFile = outputFolder + "/throughput.png";
    ThroughputChart.draw(rates, chartFile);

    // ---------- leaderboard ----------
    System.out.println("===== Leaderboard =====");
    int totalUpdates = 0;
    int totalReconnects = 0;
    long connectTimeSum = 0;
    int connectCount = 0;
    List<Long> allPushLatencies = new ArrayList<>();
    for (LeaderboardSubscriber subscriber : subscribers) {
      System.out.println("Resort " + subscriber.getResortID() + ": " + subscriber.getUpdates()
          + " updates, connect time " + subscriber.getConnectTimes().get(0) + " ms");
      totalUpdates += subscriber.getUpdates();
      totalReconnects += subscriber.getReconnects();
      for (long t : subscriber.getConnectTimes()) {
        connectTimeSum += t;
        connectCount++;
      }
      allPushLatencies.addAll(subscriber.getPushLatencies());
    }
    System.out.println("Total leaderboard updates delivered: " + totalUpdates);
    System.out.println("WebSocket connections: " + connectCount + " (reconnections: " + totalReconnects + ")");
    System.out.println("Average WebSocket connect time: " + (connectTimeSum / connectCount) + " ms");

    if (!allPushLatencies.isEmpty()) {
      long[] push = new long[allPushLatencies.size()];
      long pushSum = 0;
      for (int i = 0; i < push.length; i++) {
        push[i] = allPushLatencies.get(i);
        pushSum += push[i];
      }
      Arrays.sort(push);
      System.out.println("===== Push latency (receive time - serverTimestamp, ms) =====");
      System.out.printf("Mean: %.2f%n", (double) pushSum / push.length);
      System.out.println("Median: " + percentile(push, 50));
      System.out.println("p95: " + percentile(push, 95));
      System.out.println("p99: " + percentile(push, 99));
    }

    System.out.println("Files: " + csvFile + ", " + throughputFile + ", " + chartFile);

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

  // nanoseconds -> milliseconds with up to 3 decimals (microsecond detail)
  static String ms(long nanos) {
    return String.valueOf(nanos / 1000 / 1000.0);
  }

  // value at the given percentile of a sorted array (nearest rank)
  static long percentile(long[] sorted, double p) {
    int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    if (index < 0) {
      index = 0;
    }
    return sorted[index];
  }
}
