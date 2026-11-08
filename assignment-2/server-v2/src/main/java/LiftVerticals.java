import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

// vertical drop in feet of each of the 40 lifts, read from the course's lift_vertical.json
// (src/main/resources/lift_vertical.json, the file as given, unchanged)
public final class LiftVerticals {
  private static final int[] VERTICAL = load();

  private LiftVerticals() {}

  // liftID has already been checked to be 1-40
  public static int of(int liftID) {
    return VERTICAL[liftID];
  }

  private static int[] load() {
    try (Reader in = new InputStreamReader(LiftVerticals.class.getResourceAsStream("/lift_vertical.json"),
        StandardCharsets.UTF_8)) {
      JsonObject lifts = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("lifts");
      int[] vertical = new int[41];   // index 0 unused
      for (int liftID = 1; liftID <= 40; liftID++) {
        vertical[liftID] = lifts.get(String.valueOf(liftID)).getAsInt();
      }
      return vertical;
    } catch (Exception e) {
      throw new IllegalStateException("can't read lift_vertical.json", e);
    }
  }
}
