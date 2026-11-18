package lv.ordersmp.clips.edit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Decodes a clip for the editor's preview. ffmpeg turns the video into small RGBA frames on a
 * background thread; the render thread only takes the frame that is due and copies it into a
 * texture. Seeking starts a new ffmpeg at the new time, which is quick because clips have a
 * keyframe every second. A few frames are decoded ahead, so playback never waits on ffmpeg, and
 * frame buffers are reused, so playing creates no garbage.
 */
public final class VideoPreview implements AutoCloseable {

  /**
   * One decoded frame.
   *
   * @param time seconds from the clip's start
   * @param pixels RGBA pixels, {@link #WIDTH} by {@link #HEIGHT}
   */
  public record Frame(double time, byte[] pixels) {}

  public static final int WIDTH = 640;
  public static final int HEIGHT = 360;
  public static final int FPS = 30;
  private static final int FRAME_BYTES = WIDTH * HEIGHT * 4;
  private static final int AHEAD = 4;

  private final String ffmpeg;
  private final Path clip;
  private final BlockingQueue<byte[]> spare = new ArrayBlockingQueue<>(AHEAD * 2);
  private BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(AHEAD);
  private Process process;
  private Thread reader;

  public VideoPreview(String ffmpeg, Path clip) {
    this.ffmpeg = ffmpeg;
    this.clip = clip;
  }

  /**
   * Starts decoding at {@code from} seconds. With {@code single} only that one frame is decoded,
   * which is what a paused preview needs after a seek.
   */
  public void start(double from, boolean single) {
    stop();
    BlockingQueue<Frame> queue = new ArrayBlockingQueue<>(AHEAD);
    frames = queue;
    String size = WIDTH + ":" + HEIGHT;
    List<String> command = new ArrayList<>(List.of(
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-ss", String.format(Locale.ROOT, "%.3f", Math.max(0, from)), "-i", clip.toString(), "-an",
        "-vf", "fps=" + FPS + ",scale=" + size + ":force_original_aspect_ratio=decrease,pad=" + size + ":(ow-iw)/2:(oh-ih)/2",
        "-f", "rawvideo", "-pix_fmt", "rgba"));
    if (single) {
      command.addAll(List.of("-frames:v", "1"));
    }
    command.add("pipe:1");
    try {
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      process = null;
      return;
    }
    InputStream out = process.getInputStream();
    reader = Thread.ofPlatform().daemon().name("Clips preview").start(() -> read(out, queue, spare, from));
  }

  /**
   * The newest frame due at {@code time}, or null when the next frame is still in the future or not
   * decoded yet. Frames that are already late are skipped.
   */
  public Frame take(double time) {
    Frame due = null;
    Frame next = frames.peek();
    while (next != null && next.time() <= time) {
      if (due != null) {
        recycle(due);
      }
      due = frames.poll();
      next = frames.peek();
    }
    return due;
  }

  /** Gives a frame's buffer back once its pixels were copied, so the decoder can reuse it. */
  public void recycle(Frame frame) {
    spare.offer(frame.pixels());
  }

  /** Stops decoding and drops the frames decoded so far. */
  public void stop() {
    if (process != null) {
      process.destroyForcibly();
      process = null;
    }
    if (reader != null) {
      reader.interrupt();
      reader = null;
    }
    Frame dropped;
    while ((dropped = frames.poll()) != null) {
      recycle(dropped);
    }
  }

  @Override
  public void close() {
    stop();
  }

  private static void read(InputStream out, BlockingQueue<Frame> queue, BlockingQueue<byte[]> spare, double from) {
    try (out) {
      for (int index = 0; ; index++) {
        byte[] pixels = spare.poll();
        if (pixels == null) {
          pixels = new byte[FRAME_BYTES];
        }
        if (out.readNBytes(pixels, 0, FRAME_BYTES) < FRAME_BYTES) {
          return;
        }
        queue.put(new Frame(from + (double) index / FPS, pixels));
      }
    } catch (IOException e) {
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
