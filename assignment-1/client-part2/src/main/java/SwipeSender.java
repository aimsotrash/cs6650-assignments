import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

// takes swipes from the queue and POSTs them to the server
// part 2: also records the latency of every request in its own list
public class SwipeSender implements Runnable {
  private static final int MAX_RETRIES = 5;

  private String serverUrl;
  private HttpClient client;
  private BlockingQueue<SwipeEvent> queue;
  private int numToSend;
  private CountDownLatch latch;
  private List<RequestRecord> records = new ArrayList<>();

  public SwipeSender(String serverUrl, HttpClient client, BlockingQueue<SwipeEvent> queue,
                     int numToSend, CountDownLatch latch) {
    this.serverUrl = serverUrl;
    this.client = client;
    this.queue = queue;
    this.numToSend = numToSend;
    this.latch = latch;
  }

  @Override
  public void run() {
    try {
      for (int i = 0; i < numToSend; i++) {
        SwipeEvent swipe = queue.take();
        if (send(swipe)) {
          SkierClient.successful.incrementAndGet();
        } else {
          SkierClient.failed.incrementAndGet();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      latch.countDown();
    }
  }

  // try once, and if it doesn't work retry up to 5 times, waiting longer each time
  private boolean send(SwipeEvent swipe) throws InterruptedException {
    String url = serverUrl + "/skiers/" + swipe.getResortID()
        + "/seasons/" + swipe.getSeasonID()
        + "/days/" + swipe.getDayID()
        + "/skiers/" + swipe.getSkierID();
    String body = "{\"time\": " + swipe.getTime() + ", \"liftID\": " + swipe.getLiftID() + "}";

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();

    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      if (attempt > 0) {
        SkierClient.retries.incrementAndGet();
        long wait = 100 * (long) Math.pow(2, attempt - 1);   // 100, 200, 400, 800, 1600 ms
        Thread.sleep(wait);
      }

      long start = System.currentTimeMillis();   // wall clock time, for the CSV timestamp
      long startNanos = System.nanoTime();       // monotonic clock for the latency (currentTimeMillis only has whole ms)
      int statusCode = -1;   // stays -1 if there was a network error
      try {
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        statusCode = response.statusCode();
        // Tomcat closes a kept-alive connection after 100 requests, so the next request needs a new one
        if (response.headers().firstValue("connection").orElse("").equalsIgnoreCase("close")) {
          SkierClient.serverClosedConnections.incrementAndGet();
        }
      } catch (IOException e) {
        // network problem, try again
      }
      long latencyNanos = System.nanoTime() - startNanos;

      // only record the attempt that finished this swipe (the one that worked, or the last one)
      if (statusCode == 201 || attempt == MAX_RETRIES) {
        records.add(new RequestRecord(start, latencyNanos, statusCode, swipe.getResortID()));
      }
      if (statusCode == 201) {
        return true;
      }
    }
    return false;
  }

  public List<RequestRecord> getRecords() {
    return records;
  }
}
