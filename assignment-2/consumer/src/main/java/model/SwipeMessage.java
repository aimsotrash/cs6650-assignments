package model;

import java.time.Instant;

// a swipe as it comes off the queue (same format server-v2 publishes)
public class SwipeMessage {
  private String eventId;
  private String resortID;
  private String dayID;
  private String skierID;
  private String liftID;
  private int vertical;
  private int time;
  private String timestamp;
  private String serverId;

  private transient int skier;
  private transient long ingestMillis;

  // throws if the message is missing something; a message that fails here can't ever be processed
  public void validate() {
    if (eventId == null || resortID == null || dayID == null || skierID == null || timestamp == null || vertical <= 0) {
      throw new IllegalArgumentException("incomplete swipe message");
    }
    skier = Integer.parseInt(skierID);
    ingestMillis = Instant.parse(timestamp).toEpochMilli();
  }

  public String getEventId() { return eventId; }
  public String getResortID() { return resortID; }
  public String getDayID() { return dayID; }
  public String getSkierID() { return skierID; }
  public int getSkier() { return skier; }
  public int getVertical() { return vertical; }
  public String getServerId() { return serverId; }
  public long getIngestMillis() { return ingestMillis; }
}
