import model.LeaderboardMessage;
import model.SwipeMessage;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

// the aggregation state: every skier's vertical per resort and day, and every resort's top N per day.
// a resort is only ever handled by the one thread consuming its queue; the maps are concurrent anyway because
// different resorts (threads) share them, and a resort can move to another thread when the pool rescales
public class Aggregator {
  // eventIds seen, per resort, for dedupe. a redelivered message comes back within about a prefetch's worth of
  // messages, and a producer duplicate within seconds, so the last 50k per resort is plenty
  // ponytail: in-memory window, a duplicate that shows up more than 50k swipes later for that resort counts again
  private static final int DEDUPE_WINDOW = 50000;

  final ConcurrentHashMap<String, Long> skierVertical = new ConcurrentHashMap<>();       // resort:day:skier -> vertical
  final ConcurrentHashMap<String, TopN> resortLeaderboards = new ConcurrentHashMap<>();  // resort:day -> top N
  final AtomicLong swipesProcessed = new AtomicLong();
  final AtomicLong duplicates = new AtomicLong();
  // swipes per ingest server (the serverId in each message): how evenly the load balancer spread the load
  final ConcurrentHashMap<String, LongAdder> swipesPerServer = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Set<String>> recentEventIds = new ConcurrentHashMap<>();

  // consumer lag (time from the server accepting the swipe to us applying it), reset by the stats log
  private final LongAdder lagSum = new LongAdder();
  private final LongAdder lagCount = new LongAdder();
  private final AtomicLong lagMax = new AtomicLong();

  // applies one (already validated) swipe. returns the resort's new leaderboard if its top N changed,
  // null if it didn't or if this eventId was already counted (a redelivery or a duplicate publish)
  public LeaderboardMessage apply(SwipeMessage swipe) {
    Set<String> seen = recentIds(swipe.getResortID());
    if (seen.contains(swipe.getEventId())) {
      duplicates.incrementAndGet();
      return null;
    }
    String resortDay = swipe.getResortID() + ":" + swipe.getDayID();
    long total = skierVertical.merge(resortDay + ":" + swipe.getSkierID(), (long) swipe.getVertical(), Long::sum);
    // marked as seen only once it's counted, so a swipe whose processing failed is counted when it's retried
    // (check-then-add is safe: a resort's swipes are handled by one thread at a time)
    seen.add(swipe.getEventId());
    swipesProcessed.incrementAndGet();
    swipesPerServer.computeIfAbsent(String.valueOf(swipe.getServerId()), k -> new LongAdder()).increment();

    long lag = System.currentTimeMillis() - swipe.getIngestMillis();
    lagSum.add(lag);
    lagCount.increment();
    lagMax.accumulateAndGet(lag, Math::max);

    TopN top = resortLeaderboards.computeIfAbsent(resortDay, k -> new TopN(Config.TOP_N));
    if (!top.offer(swipe.getSkier(), total)) {
      return null;
    }
    return new LeaderboardMessage(swipe.getResortID(), swipe.getDayID(), Instant.now().toString(), top.entries());
  }

  // "avg=12 max=85" since the last call
  public String takeLag() {
    long count = lagCount.sumThenReset();
    long sum = lagSum.sumThenReset();
    long max = lagMax.getAndSet(0);
    return count == 0 ? "-" : "avg=" + (sum / count) + "ms max=" + max + "ms";
  }

  private Set<String> recentIds(String resortID) {
    return recentEventIds.computeIfAbsent(resortID, k -> Collections.synchronizedSet(Collections.newSetFromMap(
        new LinkedHashMap<String, Boolean>() {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > DEDUPE_WINDOW;
          }
        })));
  }
}
