import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;
import model.LiftRide;
import model.ResponseMsg;
import model.SwipeMessage;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

// removed the logging from lab 2 so it doesn't slow down the server
// assignment 2: a valid swipe is now published to rabbit, 201 only after the broker confirmed it
public class SkierServlet extends HttpServlet {
  private static final long CONFIRM_TIMEOUT_MS = 5000;

  private Gson gson = new Gson();
  private ChannelPool pool;

  @Override
  public void init() {
    pool = (ChannelPool) getServletContext().getAttribute(ChannelPool.ATTR);
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // Check we have a URL
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }

    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
    } else {
      res.setStatus(HttpServletResponse.SC_OK);

      // Extract parameters
      String resortID = urlParts[1];
      String seasonID = urlParts[3];
      String dayID = urlParts[5];
      String skierID = urlParts[7];

      // Create response
      ResponseMsg response = new ResponseMsg(
          String.format("GET request processed for skier %s at resort %s, season %s, day %s",
              skierID, resortID, seasonID, dayID)
      );

      res.getWriter().write(gson.toJson(response));
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // Check we have a URL
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }

    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
      return;
    }

    // Parse JSON body
    try {
      StringBuilder jsonBody = new StringBuilder();
      BufferedReader reader = req.getReader();
      String line;
      while ((line = reader.readLine()) != null) {
        jsonBody.append(line);
      }

      LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

      if (liftRide == null || !isLiftRideValid(liftRide)) {
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
        return;
      }

      // Extract URL parameters
      String resortID = urlParts[1];
      String seasonID = urlParts[3];
      String dayID = urlParts[5];
      String skierID = urlParts[7];

      // look up the lift's vertical and put the swipe on the queue
      SwipeMessage message = new SwipeMessage(UUID.randomUUID().toString(), resortID, dayID, skierID,
          String.valueOf(liftRide.getLiftID()), LiftVerticals.of(liftRide.getLiftID()), liftRide.getTime(),
          Instant.now().toString(), Config.SERVER_ID);
      if (!publish(resortID, gson.toJson(message).getBytes(StandardCharsets.UTF_8))) {
        // the client retries anything that isn't 201
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.getWriter().write(gson.toJson(new ResponseMsg("queue unavailable, try again")));
        return;
      }

      res.setStatus(HttpServletResponse.SC_CREATED);
      ResponseMsg response = new ResponseMsg(
          String.format("Lift ride recorded: skier %s, resort %s, season %s, day %s, time %d, lift %d",
              skierID, resortID, seasonID, dayID, liftRide.getTime(), liftRide.getLiftID())
      );

      res.getWriter().write(gson.toJson(response));

    } catch (JsonSyntaxException e) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
    }
  }

  private boolean isUrlValid(String[] urlParts) {
    // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
    // urlParts = ["", "1", "seasons", "2019", "days", "1", "skiers", "123"]

    if (urlParts.length != 8) {
      return false;
    }

    try {
      // Check structure
      if (!urlParts[2].equals("seasons") ||
          !urlParts[4].equals("days") ||
          !urlParts[6].equals("skiers")) {
        return false;
      }

      // Validate numeric parameters
      int resortID = Integer.parseInt(urlParts[1]);
      int seasonID = Integer.parseInt(urlParts[3]);
      int dayID = Integer.parseInt(urlParts[5]);
      int skierID = Integer.parseInt(urlParts[7]);

      // ids have to be positive, and the resort needs a queue (resort.1 to resort.10)
      return resortID > 0 && resortID <= Topology.NUM_RESORTS && seasonID > 0 && dayID > 0 && skierID > 0;

    } catch (NumberFormatException e) {
      return false;
    }
  }

  // borrow a channel, publish (persistent), wait for the broker's confirm, give the channel back.
  // if the connection drops mid-publish the message may be in the queue even though its confirm never came,
  // so try once more with the same body (same eventId): if the first copy did get in, the consumer drops the
  // second one. a 503 instead would make the client retry with a new eventId and the swipe would count twice
  // (the connection drop test counted 9 of 40,000 swipes twice without this)
  private boolean publish(String resortID, byte[] body) {
    for (int attempt = 1; attempt <= 2; attempt++) {
      Channel channel = null;
      try {
        channel = pool.borrowChannel();
        channel.basicPublish(Topology.SWIPE_EXCHANGE, "resort." + resortID, MessageProperties.PERSISTENT_TEXT_PLAIN, body);
        channel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
        return true;
      } catch (IOException | TimeoutException | RuntimeException e) {
        // RuntimeException: the channel or connection was closed under us (AlreadyClosedException)
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        if (channel != null) {
          pool.returnChannel(channel);
        }
      }
    }
    return false;
  }

  private boolean isLiftRideValid(LiftRide liftRide) {
    // time has to be 1-360 and liftID 1-40
    return liftRide.getTime() >= 1 &&
        liftRide.getTime() <= 360 &&
        liftRide.getLiftID() >= 1 &&
        liftRide.getLiftID() <= 40;
  }
}
