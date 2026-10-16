package model;

public class LeaderboardEntry {
  private int rank;
  private int skierID;
  private int vertical;

  public LeaderboardEntry(int rank, int skierID, int vertical) {
    this.rank = rank;
    this.skierID = skierID;
    this.vertical = vertical;
  }
}
