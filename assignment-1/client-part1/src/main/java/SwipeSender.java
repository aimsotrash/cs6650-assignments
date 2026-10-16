import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

// takes swipes from the queue and POSTs them to the server
public class SwipeSender implements Runnable {
  private static final int MAX_RETRIES = 5;

  private String serverUrl;
  private HttpClient client;
  private BlockingQueue<SwipeEvent> queue;
  private int numToSend;
  private CountDownLatch latch;

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
      try {
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        // Tomcat closes a kept-alive connection after 100 requests, so the next request needs a new one
        if (response.headers().firstValue("connection").orElse("").equalsIgnoreCase("close")) {
          SkierClient.serverClosedConnections.incrementAndGet();
        }
        if (response.statusCode() == 201) {
          return true;
        }
      } catch (IOException e) {
        // network problem, try again
      }
    }
    return false;
  }
}
