package model;

import java.util.List;

// same message as the fake one in assignment 1, now with real data and the day it is for
public class LeaderboardMessage {
  private String resortID;
  private String dayID;
  private String serverTimestamp;
  private List<LeaderboardEntry> top;

  public LeaderboardMessage(String resortID, String dayID, String serverTimestamp, List<LeaderboardEntry> top) {
    this.resortID = resortID;
    this.dayID = dayID;
    this.serverTimestamp = serverTimestamp;
    this.top = top;
  }
}
