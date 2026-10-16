import com.google.gson.Gson;
import model.LeaderboardEntry;
import model.LeaderboardMessage;

import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

  // resortID -> sessions subscribed to that resort
  private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();
  private static final ScheduledExecutorService pusher = Executors.newSingleThreadScheduledExecutor();
  private static final Gson gson = new Gson();
  private static final Random random = new Random();

  static {
    // send a fake leaderboard every 500ms
    pusher.scheduleAtFixedRate(LeaderboardEndpoint::pushAll, 500, 500, TimeUnit.MILLISECONDS);
  }

  @OnOpen
  public void onOpen(Session session, @PathParam("resortID") String resortID) {
    subscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);
  }

  @OnClose
  public void onClose(Session session, @PathParam("resortID") String resortID) {
    Set<Session> sessions = subscribers.get(resortID);
    if (sessions != null) {
      sessions.remove(session);
    }
  }

  @OnError
  public void onError(Session session, @PathParam("resortID") String resortID, Throwable t) {
    Set<Session> sessions = subscribers.get(resortID);
    if (sessions != null) {
      sessions.remove(session);
    }
  }

  private static void pushAll() {
    for (Map.Entry<String, Set<Session>> e : subscribers.entrySet()) {
      String json = syntheticLeaderboard(e.getKey());
      for (Session s : e.getValue()) {
        // if an exception gets out of here the timer stops, so catch it
        try {
          if (s.isOpen()) {
            s.getAsyncRemote().sendText(json);
          }
        } catch (Exception ex) {
          // skip this client this time
        }
      }
    }
  }

  private static String syntheticLeaderboard(String resortID) {
    List<LeaderboardEntry> top = new ArrayList<>();
    for (int rank = 1; rank <= 10; rank++) {
      int skierID = random.nextInt(100000) + 1;
      int vertical = 50000 - rank * 1000;
      top.add(new LeaderboardEntry(rank, skierID, vertical));
    }

    LeaderboardMessage message = new LeaderboardMessage(resortID, Instant.now().toString(), top);
    return gson.toJson(message);
  }

  // called from AppListener
  public static void stopPusher() {
    pusher.shutdownNow();
  }
}
