package lv.ordersmp.clips.fight;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lv.ordersmp.clips.ClipLibrary;

/**
 * The moments inside an automatic clip (first hit, kills, the player's death), kept beside the
 * clip so the editor can mark them on its timeline.
 */
public final class ClipEvents {

  /** What happened. */
  public enum Type { FIRST_HIT, KILL, DEATH }

  /**
   * One moment.
   *
   * @param type what happened
   * @param time seconds from the start of the clip file
   * @param name the other player
   */
  public record Event(Type type, double time, String name) {}

  private static final Gson GSON = new Gson();

  private ClipEvents() {}

  /** The moments kept for {@code clip}; none when there are none or the file cannot be read. */
  public static List<Event> load(Path clip) {
    Path file = ClipLibrary.eventsFile(clip);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      List<Event> events = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Event>>() {}.getType());
      return events == null ? List.of() : events.stream().filter(event -> event.type() != null).toList();
    } catch (IOException | JsonParseException e) {
      return List.of();
    }
  }

  /** Keeps {@code events} beside {@code clip}. */
  public static void save(Path clip, List<Event> events) throws IOException {
    Path file = ClipLibrary.eventsFile(clip);
    Files.createDirectories(file.getParent());
    Files.writeString(file, GSON.toJson(events), StandardCharsets.UTF_8);
  }
}
