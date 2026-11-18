package lv.ordersmp.clips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;

/**
 * Player settings, read from {@code config/ordersmp-clips.json}. A missing file is written with the
 * defaults so players can find and edit it; a broken file is logged and the defaults apply, so a
 * typo never stops the game from starting.
 *
 * @param bufferSeconds how much of the latest play is kept ready to save
 * @param fps capture frame rate
 * @param maxHeight largest recorded height; bigger windows are scaled down on the GPU
 * @param bitrateMbps video bitrate, or 0 to pick one from the recorded height
 * @param encoder preferred encoder name such as {@code h264_nvenc}, or empty to pick the best one
 * @param ffmpegPath path to the ffmpeg program, or empty to look for it on the system
 * @param showHud whether the recording mark is drawn
 */
public record ClipsConfig(
    int bufferSeconds, int fps, int maxHeight, int bitrateMbps, String encoder, String ffmpegPath, boolean showHud) {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /** The settings a new player starts with: two minutes at 1080p and 60 frames per second. */
  public static ClipsConfig defaults() {
    return new ClipsConfig(120, 60, 1080, 0, "", "", true);
  }

  /** Loads the settings, writing the defaults when the file does not exist yet. */
  public static ClipsConfig load(Path file, Logger log) {
    if (!Files.exists(file)) {
      ClipsConfig fresh = defaults();
      fresh.save(file, log);
      return fresh;
    }
    try {
      ClipsConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClipsConfig.class);
      if (read == null) {
        log.warn("{} is empty; the default settings apply", file.getFileName());
        return defaults();
      }
      return read.sanitized();
    } catch (IOException | JsonParseException e) {
      log.warn("{} could not be read ({}); the default settings apply", file.getFileName(), e.getMessage());
      return defaults();
    }
  }

  /** Writes these settings so the player can edit them. */
  public void save(Path file, Logger log) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    } catch (IOException e) {
      log.warn("{} could not be written: {}", file.getFileName(), e.getMessage());
    }
  }

  /** Keeps every value inside a range the recorder can handle, so a bad edit cannot break recording. */
  private ClipsConfig sanitized() {
    return new ClipsConfig(
        clamp(bufferSeconds, 10, 300),
        clamp(fps, 15, 60),
        clamp(maxHeight, 360, 2160) / 2 * 2,
        clamp(bitrateMbps, 0, 80),
        encoder == null ? "" : encoder.trim(),
        ffmpegPath == null ? "" : ffmpegPath.trim(),
        showHud);
  }

  /** Bitrate in bits per second, chosen from the recorded height when the player did not set one. */
  public int bitrateFor(int height) {
    if (bitrateMbps > 0) {
      return bitrateMbps * 1_000_000;
    }
    if (height <= 720) {
      return 6_000_000;
    }
    if (height <= 1080) {
      return 10_000_000;
    }
    return 16_000_000;
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
