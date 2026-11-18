package lv.ordersmp.clips.encode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Finds a working ffmpeg and the best video encoder it can actually use on this PC. Encoders are
 * tried with a tiny test encode, because ffmpeg lists hardware encoders even when the matching
 * graphics card or driver is missing.
 */
public final class EncoderProbe {

  private static final long TEST_TIMEOUT_SECONDS = 15;

  private EncoderProbe() {}

  /**
   * Outcome of a probe.
   *
   * @param ffmpeg the ffmpeg program to run, or null when none was found
   * @param codec the encoder to use, or null when none works
   * @param problem a short reason when recording is not possible, or null
   */
  public record Result(String ffmpeg, Codec codec, String problem) {

    /** True when recording can start. */
    public boolean ready() {
      return problem == null;
    }
  }

  /** Runs the probe; slow (a few seconds), so call it off the render thread. */
  public static Result run(String configuredPath, String preferredEncoder, Path modFolder) {
    Optional<String> ffmpeg = findFfmpeg(configuredPath, modFolder);
    if (ffmpeg.isEmpty()) {
      return new Result(null, null, "FFmpeg was not found");
    }
    for (Codec codec : Codec.order(preferredEncoder)) {
      if (works(ffmpeg.get(), codec)) {
        return new Result(ffmpeg.get(), codec, null);
      }
    }
    return new Result(ffmpeg.get(), null, "FFmpeg has no working H.264 encoder");
  }

  private static Optional<String> findFfmpeg(String configuredPath, Path modFolder) {
    List<String> candidates = new ArrayList<>();
    if (!configuredPath.isEmpty()) {
      candidates.add(configuredPath);
    }
    String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
    Path bundled = modFolder.resolve(exe);
    if (Files.isRegularFile(bundled)) {
      candidates.add(bundled.toString());
    }
    candidates.add("ffmpeg");
    for (String candidate : candidates) {
      if (exitsCleanly(List.of(candidate, "-hide_banner", "-version"))) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  private static boolean works(String ffmpeg, Codec codec) {
    List<String> cmd = new ArrayList<>(List.of(
        ffmpeg, "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", "color=c=black:s=256x144:r=30:d=0.3"));
    cmd.addAll(codec.args(2_000_000, 30));
    cmd.addAll(List.of("-f", "null", "-"));
    return exitsCleanly(cmd);
  }

  private static boolean exitsCleanly(List<String> cmd) {
    try {
      Process process = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
