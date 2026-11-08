import com.rabbitmq.client.ConnectionFactory;

import java.net.InetAddress;

// settings come from environment variables (set them in /opt/tomcat9/bin/setenv.sh), defaults are for running locally
final class Config {
  static final String RABBIT_HOST = env("RABBIT_HOST", "localhost");
  static final String RABBIT_USER = env("RABBIT_USER", "guest");
  static final String RABBIT_PASS = env("RABBIT_PASS", "guest");
  static final int CHANNEL_POOL_SIZE = Integer.parseInt(env("CHANNEL_POOL_SIZE", "64"));

  // goes into every queue message as serverId
  static final String SERVER_ID = env("SERVER_ID", hostname());

  private Config() {}

  static ConnectionFactory connectionFactory() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RABBIT_HOST);
    factory.setUsername(RABBIT_USER);
    factory.setPassword(RABBIT_PASS);
    // the defaults (60 s connect, 60 s heartbeat, 10 min channel RPC) mean that if the broker host disappears
    // without closing the TCP connection, request threads hang for minutes before the pool notices and reconnects
    factory.setConnectionTimeout(5000);
    factory.setRequestedHeartbeat(10);
    factory.setChannelRpcTimeout(5000);
    return factory;
  }

  private static String env(String name, String def) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? def : value;
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
