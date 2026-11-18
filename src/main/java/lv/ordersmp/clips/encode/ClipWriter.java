package lv.ordersmp.clips.encode;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Saves the newest part of the {@link PacketRing} as an MP4 file. The video is copied as it is
 * (no re-encoding), so saving costs only a moment of disk writing, never game performance.
 */
public final class ClipWriter {

  private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
  private static final long MUX_TIMEOUT_SECONDS = 60;

  private ClipWriter() {}

  /**
   * Result of a save.
   *
   * @param file the saved clip, or null when saving failed
   * @param seconds length of the clip
   * @param problem a short reason when saving failed, or null
   */
  public record Saved(Path file, int seconds, String problem) {}

  /** Writes up to {@code seconds} of the newest video to a new MP4 in {@code folder}. */
  public static Saved save(PacketRing ring, String ffmpeg, int fps, int seconds, Path folder) {
    Path raw = null;
    try {
      Files.createDirectories(folder);
      raw = Files.createTempFile(folder, ".clip-", ".h264");
      int frames;
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(raw), 1 << 20)) {
        frames = ring.writeLatest(fps * seconds, out);
      }
      if (frames < fps) {
        return new Saved(null, 0, "nothing recorded yet");
      }
      Path target = folder.resolve(NAME.format(LocalDateTime.now()) + ".mp4");
      String problem = remux(ffmpeg, raw, fps, target);
      if (problem != null) {
        return new Saved(null, 0, problem);
      }
      return new Saved(target, Math.round((float) frames / fps), null);
    } catch (IOException e) {
      return new Saved(null, 0, e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Saved(null, 0, "saving was interrupted");
    } finally {
      deleteQuietly(raw);
    }
  }

  /**
   * Copies a raw H.264 stream into an MP4 with the index at the front, without re-encoding.
   * Returns null on success, or a short reason. Blocks while ffmpeg runs.
   */
  public static String remux(String ffmpeg, Path raw, int fps, Path target) throws IOException, InterruptedException {
    Process mux = new ProcessBuilder(List.of(
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-f", "h264", "-framerate", Integer.toString(fps), "-i", raw.toString(),
        "-c", "copy", "-movflags", "+faststart", target.toString()))
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    if (!mux.waitFor(MUX_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      mux.destroyForcibly();
      return "ffmpeg took too long to write the file";
    }
    if (mux.exitValue() != 0) {
      return "ffmpeg could not write the file";
    }
    return null;
  }

  /** The file name a clip saved now gets, without the folder. */
  public static String nameNow() {
    return NAME.format(LocalDateTime.now());
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException ignored) {
      file.toFile().deleteOnExit();
    }
  }
}
