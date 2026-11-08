import com.rabbitmq.client.ConnectionFactory;

// settings come from environment variables (see deployment/), defaults are for running locally
final class Config {
  static final String RABBIT_HOST = env("RABBIT_HOST", "localhost");
  static final String RABBIT_USER = env("RABBIT_USER", "guest");
  static final String RABBIT_PASS = env("RABBIT_PASS", "guest");

  // threads to start with; the pool scales up to MAX_THREADS when the queues back up and back down to this
  static final int CONSUMER_THREADS = envInt("CONSUMER_THREADS", 10);
  static final int MAX_THREADS = envInt("MAX_THREADS", CONSUMER_THREADS);
  static final int PREFETCH = envInt("PREFETCH", 100);
  static final int ACK_BATCH = envInt("ACK_BATCH", 1);
  // leaderboard size; 20 is the course's starting value, other N can be compared by setting it
  static final int TOP_N = envInt("TOP_N", 20);
  static final int HEALTH_PORT = envInt("HEALTH_PORT", 8081);

  private Config() {}

  static ConnectionFactory connectionFactory() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RABBIT_HOST);
    factory.setUsername(RABBIT_USER);
    factory.setPassword(RABBIT_PASS);
    // automatic recovery is on by default: after a dropped connection the client reconnects and re-creates the
    // channels, qos and consumers; the unacked messages are redelivered and the dedupe skips the ones already counted
    factory.setNetworkRecoveryInterval(2000);
    return factory;
  }

  private static String env(String name, String def) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? def : value;
  }

  private static int envInt(String name, int def) {
    return Integer.parseInt(env(name, String.valueOf(def)));
  }
}
