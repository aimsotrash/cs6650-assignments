import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

// watches one resort's leaderboard for the whole run, and reconnects if the connection drops
@ClientEndpoint
public class LeaderboardSubscriber {
  private static WebSocketContainer container = ContainerProvider.getWebSocketContainer();

  private String url;
  private int resortID;
  private Session session;
  private volatile boolean running = true;

  private AtomicInteger updates = new AtomicInteger();
  private AtomicInteger reconnects = new AtomicInteger();
  private List<Long> connectTimes = new CopyOnWriteArrayList<>();
  private List<Long> pushLatencies = new CopyOnWriteArrayList<>();

  public LeaderboardSubscriber(String serverUrl, int resortID) {
    this.url = serverUrl.replace("http://", "ws://") + "/leaderboard/" + resortID;
    this.resortID = resortID;
  }

  public void connect() throws Exception {
    long start = System.currentTimeMillis();
    session = container.connectToServer(this, URI.create(url));
    connectTimes.add(System.currentTimeMillis() - start);
  }

  @OnMessage
  public void onMessage(String message) {
    long receiveTime = System.currentTimeMillis();
    updates.incrementAndGet();

    // get serverTimestamp out of the JSON
    int start = message.indexOf("\"serverTimestamp\":\"") + "\"serverTimestamp\":\"".length();
    int end = message.indexOf("\"", start);
    long serverTime = Instant.parse(message.substring(start, end)).toEpochMilli();
    pushLatencies.add(receiveTime - serverTime);
  }

  @OnClose
  public void onClose(Session s) {
    // if it closed in the middle of the run, reconnect (in a new thread so we don't block this one)
    if (running) {
      new Thread(this::reconnect).start();
    }
  }

  private void reconnect() {
    for (int attempt = 1; attempt <= 5 && running; attempt++) {
      try {
        Thread.sleep(1000L * attempt);
        connect();
        reconnects.incrementAndGet();
        return;
      } catch (Exception e) {
        // try again
      }
    }
  }

  public void stop() {
    running = false;
    try {
      if (session != null) {
        session.close();
      }
    } catch (Exception e) {
      // already closed
    }
  }

  public int getResortID() { return resortID; }
  public int getUpdates() { return updates.get(); }
  public int getReconnects() { return reconnects.get(); }
  public List<Long> getConnectTimes() { return connectTimes; }
  public List<Long> getPushLatencies() { return pushLatencies; }
}
