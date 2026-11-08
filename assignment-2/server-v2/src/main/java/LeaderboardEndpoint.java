import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// assignment 2: no more fake leaderboard. LeaderboardRelay gets the real ones from the backplane
// and calls push() for the sessions this server holds
@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

  // resortID -> sessions subscribed to that resort (on this server)
  private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();

  @OnOpen
  public void onOpen(Session session, @PathParam("resortID") String resortID) {
    // push() sends in blocking mode; tomcat's default timeout is 20 s, and a client whose connection went
    // dead would hold up every other subscriber on this server that long for each update
    session.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 1000L);
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

  // called by LeaderboardRelay (one thread) for every leaderboard update from the backplane
  static void push(String resortID, String json) {
    Set<Session> sessions = subscribers.get(resortID);
    if (sessions == null) {
      return;
    }
    for (Session s : sessions) {
      try {
        if (s.isOpen()) {
          // blocking send instead of the async one from assignment 1: updates now come back to back, and an
          // async send while the last one is still writing throws, so the newest standings could get dropped.
          // ponytail: a slow client holds up the others, async + keep-only-the-newest if that ever shows up
          s.getBasicRemote().sendText(json);
        }
      } catch (Exception ex) {
        // skip this client this time
      }
    }
  }
}
