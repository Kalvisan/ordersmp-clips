package lv.ordersmp.clips.audio;

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
 * The list of a clip's sound tracks, kept beside them as {@code tracks.json}: game sound, the
 * player's own voice and one track per other speaker, each with the name the editor shows.
 */
public final class AudioTracks {

  /** What a track holds; each kind has its own default volume. */
  public enum Kind { GAME, MINE, VOICE }

  /**
   * One track.
   *
   * @param file file name inside the clip's audio folder
   * @param kind what it holds
   * @param name the name to show, such as a player name
   */
  public record Track(String file, Kind kind, String name) {}

  private static final Gson GSON = new Gson();
  private static final String LIST = "tracks.json";

  private AudioTracks() {}

  /** The tracks kept for {@code clip}; none for clips saved before Clips had sound. */
  public static List<Track> load(Path clip) {
    Path file = ClipLibrary.audioFolder(clip).resolve(LIST);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    try {
      List<Track> tracks = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Track>>() {}.getType());
      return tracks == null ? List.of() : tracks.stream().filter(track -> track.file() != null && track.kind() != null).toList();
    } catch (IOException | JsonParseException e) {
      return List.of();
    }
  }

  /** Keeps {@code tracks} as the list for {@code clip}. */
  public static void save(Path clip, List<Track> tracks) throws IOException {
    Path folder = ClipLibrary.audioFolder(clip);
    Files.createDirectories(folder);
    Files.writeString(folder.resolve(LIST), GSON.toJson(tracks), StandardCharsets.UTF_8);
  }
}
