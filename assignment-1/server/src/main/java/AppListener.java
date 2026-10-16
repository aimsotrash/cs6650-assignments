import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

// stops the leaderboard timer when the app gets redeployed, otherwise an old timer keeps running
public class AppListener implements ServletContextListener {

  @Override
  public void contextDestroyed(ServletContextEvent sce) {
    LeaderboardEndpoint.stopPusher();
  }
}
