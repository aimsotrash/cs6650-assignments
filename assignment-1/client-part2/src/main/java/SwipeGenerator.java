import java.util.Random;
import java.util.concurrent.BlockingQueue;

// one thread makes all the swipes and puts them in the queue for the sender threads
public class SwipeGenerator implements Runnable {
  private BlockingQueue<SwipeEvent> queue;
  private int total;
  private Random random = new Random();

  public SwipeGenerator(BlockingQueue<SwipeEvent> queue, int total) {
    this.queue = queue;
    this.total = total;
  }

  @Override
  public void run() {
    try {
      for (int i = 0; i < total; i++) {
        int skierID = random.nextInt(100000) + 1;
        int resortID = random.nextInt(SkierClient.numResorts) + 1;
        int liftID = random.nextInt(40) + 1;
        int time = random.nextInt(360) + 1;
        queue.put(new SwipeEvent(skierID, resortID, liftID, SkierClient.SEASON_ID, SkierClient.DAY_ID, time));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
