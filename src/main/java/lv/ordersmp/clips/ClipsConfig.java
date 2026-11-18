package lv.ordersmp.clips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import lv.ordersmp.clips.audio.AudioTracks;
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
 * @param studioUrl address of the OrderSMP Studio that clips upload to
 * @param recordingPartMinutes length of each file Recording mode writes
 * @param clipKills save a clip when the player kills another player
 * @param clipDeaths save a clip when the player dies in a fight
 * @param clipLongFights save a clip of a fight of 20 seconds or more that ends without a kill
 * @param sendToStudio upload new automatic clips to Studio by themselves
 * @param volumeGame default volume of game sound in new clips, in percent
 * @param volumeMine default volume of the player's own voice in new clips, in percent
 * @param volumeOthers default volume of other players' voices in new clips, in percent
 * @param markMinimal draw the recording mark as an icon only, without its text
 * @param beforeSeconds seconds of an automatic clip before the first hit
 * @param afterSeconds seconds of an automatic clip after the last kill or the death
 * @param closeKillsOneClip kills close together become one clip instead of one clip each
 * @param recordMyVoice keep the player's own Simple Voice Chat voice in clips
 * @param recordOthers keep the voices the player hears in clips
 * @param maxSizeGb largest size of the clips folder in gigabytes, or 0 for no limit
 * @param deleteAfterDays days after which clips not marked Kept are deleted, or 0 to keep them
 */
public record ClipsConfig(
    int bufferSeconds, int fps, int maxHeight, int bitrateMbps, String encoder, String ffmpegPath, boolean showHud,
    String studioUrl, int recordingPartMinutes, Boolean clipKills, Boolean clipDeaths, Boolean clipLongFights, Boolean sendToStudio,
    Integer volumeGame, Integer volumeMine, Integer volumeOthers, Boolean markMinimal, Integer beforeSeconds, Integer afterSeconds,
    Boolean closeKillsOneClip, Boolean recordMyVoice, Boolean recordOthers, Integer maxSizeGb, Integer deleteAfterDays) {

  /** The public OrderSMP Studio. */
  public static final String DEFAULT_STUDIO_URL = "https://studio.ordersmp.net";

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  /**
   * The settings a new player starts with: two minutes at 1080p and 60 frames per second. Nothing
   * is deleted by itself until the player sets a size limit or a number of days.
   */
  public static ClipsConfig defaults() {
    return new ClipsConfig(120, 60, 1080, 0, "", "", true, DEFAULT_STUDIO_URL, 10, true, true, false, false, 100, 100, 100,
        false, 5, 4, true, true, true, 0, 0);
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

  /**
   * These settings with {@code change} applied, kept inside the ranges the recorder can handle.
   * Every Options control changes settings through this one method.
   */
  public ClipsConfig with(Consumer<Builder> change) {
    Builder builder = new Builder(this);
    change.accept(builder);
    return builder.build().sanitized();
  }

  /** Keeps every value inside a range the recorder can handle, so a bad edit cannot break recording. */
  private ClipsConfig sanitized() {
    return new ClipsConfig(
        clamp(bufferSeconds, 10, 600),
        clamp(fps, 15, 60),
        clamp(maxHeight, 360, 2160) / 2 * 2,
        clamp(bitrateMbps, 0, 80),
        encoder == null ? "" : encoder.trim(),
        ffmpegPath == null ? "" : ffmpegPath.trim(),
        showHud,
        studioUrl(studioUrl),
        recordingPartMinutes == 0 ? 10 : clamp(recordingPartMinutes, 1, 60),
        clipKills == null || clipKills,
        clipDeaths == null || clipDeaths,
        clipLongFights != null && clipLongFights,
        sendToStudio != null && sendToStudio,
        percent(volumeGame),
        percent(volumeMine),
        percent(volumeOthers),
        markMinimal != null && markMinimal,
        beforeSeconds == null ? 5 : clamp(beforeSeconds, 0, 15),
        afterSeconds == null ? 4 : clamp(afterSeconds, 0, 15),
        closeKillsOneClip == null || closeKillsOneClip,
        recordMyVoice == null || recordMyVoice,
        recordOthers == null || recordOthers,
        maxSizeGb == null ? 0 : clamp(maxSizeGb, 0, 500),
        deleteAfterDays == null ? 0 : clamp(deleteAfterDays, 0, 365));
  }

  /** The default volume in percent for a track of {@code kind}. */
  public int defaultVolume(AudioTracks.Kind kind) {
    return switch (kind) {
      case GAME -> volumeGame;
      case MINE -> volumeMine;
      case VOICE -> volumeOthers;
    };
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

  /** An http or https address without a trailing slash; anything else falls back to the public Studio. */
  private static String studioUrl(String value) {
    if (value == null || !(value.startsWith("https://") || value.startsWith("http://"))) {
      return DEFAULT_STUDIO_URL;
    }
    String trimmed = value.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }

  /** A changeable copy of the settings, used only inside {@link #with}. */
  public static final class Builder {
    public int bufferSeconds;
    public int fps;
    public int maxHeight;
    public int bitrateMbps;
    public boolean showHud;
    public int recordingPartMinutes;
    public boolean clipKills;
    public boolean clipDeaths;
    public boolean clipLongFights;
    public boolean sendToStudio;
    public int volumeGame;
    public int volumeMine;
    public int volumeOthers;
    public boolean markMinimal;
    public int beforeSeconds;
    public int afterSeconds;
    public boolean closeKillsOneClip;
    public boolean recordMyVoice;
    public boolean recordOthers;
    public int maxSizeGb;
    public int deleteAfterDays;
    private final ClipsConfig from;

    private Builder(ClipsConfig from) {
      this.from = from;
      bufferSeconds = from.bufferSeconds;
      fps = from.fps;
      maxHeight = from.maxHeight;
      bitrateMbps = from.bitrateMbps;
      showHud = from.showHud;
      recordingPartMinutes = from.recordingPartMinutes;
      clipKills = from.clipKills;
      clipDeaths = from.clipDeaths;
      clipLongFights = from.clipLongFights;
      sendToStudio = from.sendToStudio;
      volumeGame = from.volumeGame;
      volumeMine = from.volumeMine;
      volumeOthers = from.volumeOthers;
      markMinimal = from.markMinimal;
      beforeSeconds = from.beforeSeconds;
      afterSeconds = from.afterSeconds;
      closeKillsOneClip = from.closeKillsOneClip;
      recordMyVoice = from.recordMyVoice;
      recordOthers = from.recordOthers;
      maxSizeGb = from.maxSizeGb;
      deleteAfterDays = from.deleteAfterDays;
    }

    private ClipsConfig build() {
      return new ClipsConfig(bufferSeconds, fps, maxHeight, bitrateMbps, from.encoder, from.ffmpegPath, showHud, from.studioUrl,
          recordingPartMinutes, clipKills, clipDeaths, clipLongFights, sendToStudio, volumeGame, volumeMine, volumeOthers,
          markMinimal, beforeSeconds, afterSeconds, closeKillsOneClip, recordMyVoice, recordOthers, maxSizeGb, deleteAfterDays);
    }
  }

  private static int percent(Integer value) {
    return value == null ? 100 : clamp(value, 0, 200);
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
