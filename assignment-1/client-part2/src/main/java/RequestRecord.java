// one line of the CSV: when the request was sent, how long it took, and what came back
public class RequestRecord {
  private long startTime;
  private long latencyNanos;   // from System.nanoTime(), so it isn't rounded to whole ms
  private int statusCode;
  private int resortID;

  public RequestRecord(long startTime, long latencyNanos, int statusCode, int resortID) {
    this.startTime = startTime;
    this.latencyNanos = latencyNanos;
    this.statusCode = statusCode;
    this.resortID = resortID;
  }

  public long getStartTime() { return startTime; }
  public long getLatencyNanos() { return latencyNanos; }
  public int getStatusCode() { return statusCode; }
  public int getResortID() { return resortID; }
}
