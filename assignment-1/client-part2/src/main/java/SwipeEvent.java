public class SwipeEvent {
  private int skierID;
  private int resortID;
  private int liftID;
  private int seasonID;
  private int dayID;
  private int time;

  public SwipeEvent(int skierID, int resortID, int liftID, int seasonID, int dayID, int time) {
    this.skierID = skierID;
    this.resortID = resortID;
    this.liftID = liftID;
    this.seasonID = seasonID;
    this.dayID = dayID;
    this.time = time;
  }

  public int getSkierID() { return skierID; }
  public int getResortID() { return resortID; }
  public int getLiftID() { return liftID; }
  public int getSeasonID() { return seasonID; }
  public int getDayID() { return dayID; }
  public int getTime() { return time; }
}
