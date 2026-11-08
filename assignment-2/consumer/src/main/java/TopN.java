import model.LeaderboardEntry;

import java.util.ArrayList;
import java.util.List;

// the top n skiers of one resort on one day, best first (most vertical, ties: lower skierID first).
// totals only go up, so a skier who isn't in the list can only get in by passing the current last place
public class TopN {
  private final int n;
  private final List<long[]> top = new ArrayList<>();   // {skierID, vertical}, sorted best first

  public TopN(int n) {
    this.n = n;
  }

  // a skier's new running total. returns true if the standings changed
  public synchronized boolean offer(int skierID, long total) {
    for (long[] entry : top) {
      if (entry[0] == skierID) {
        if (total <= entry[1]) {
          return false;   // never go backwards
        }
        entry[1] = total;
        sort();
        return true;
      }
    }
    if (top.size() < n) {
      top.add(new long[] {skierID, total});
      sort();
      return true;
    }
    long[] last = top.get(n - 1);
    if (total > last[1] || (total == last[1] && skierID < last[0])) {
      top.set(n - 1, new long[] {skierID, total});
      sort();
      return true;
    }
    return false;
  }

  public synchronized List<LeaderboardEntry> entries() {
    List<LeaderboardEntry> entries = new ArrayList<>();
    for (int i = 0; i < top.size(); i++) {
      entries.add(new LeaderboardEntry(i + 1, (int) top.get(i)[0], (int) top.get(i)[1]));
    }
    return entries;
  }

  private void sort() {
    top.sort((a, b) -> a[1] != b[1] ? Long.compare(b[1], a[1]) : Long.compare(a[0], b[0]));
  }

  private synchronized String order() {
    StringBuilder sb = new StringBuilder();
    for (long[] e : top) {
      sb.append(e[0]).append(':').append(e[1]).append(' ');
    }
    return sb.toString().trim();
  }

  // self-check: java -cp consumer.jar TopN
  public static void main(String[] args) {
    TopN t = new TopN(3);
    check(t.offer(1, 100) && t.offer(2, 300) && t.offer(3, 200), "first three always get in");
    check(t.order().equals("2:300 3:200 1:100"), "sorted by vertical " + t.order());
    check(!t.offer(4, 50), "below last place stays out");
    check(t.offer(4, 150) && t.order().equals("2:300 3:200 4:150"), "passing last place gets in " + t.order());
    check(!t.offer(5, 150), "a tie with last place and a higher skierID stays out");
    check(t.offer(0, 150) && t.order().equals("2:300 3:200 0:150"), "a tie with a lower skierID gets in " + t.order());
    check(t.offer(3, 400) && t.order().equals("3:400 2:300 0:150"), "a skier in the list moves up " + t.order());
    check(!t.offer(3, 350), "an older (smaller) total is ignored");
    System.out.println("TopN self-check passed");
  }

  private static void check(boolean ok, String what) {
    if (!ok) {
      throw new AssertionError("TopN self-check failed: " + what);
    }
  }
}
