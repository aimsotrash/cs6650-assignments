import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

// assignment 2: opens the rabbit pieces when the app is deployed and closes them when it's undeployed
// (in assignment 1 this only stopped the fake leaderboard timer)
public class AppListener implements ServletContextListener {
  private ChannelPool pool;
  private LeaderboardRelay relay;

  @Override
  public void contextInitialized(ServletContextEvent sce) {
    pool = new ChannelPool(Config.connectionFactory(), Config.CHANNEL_POOL_SIZE);
    try {
      pool.init();
    } catch (Exception e) {
      // not fatal: the first swipe that borrows a channel connects again
      System.err.println("[AppListener] rabbit not ready at startup: " + e);
    }
    sce.getServletContext().setAttribute(ChannelPool.ATTR, pool);

    relay = new LeaderboardRelay();
    relay.start();
  }

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    relay.stop();
    pool.close();
  }
}
