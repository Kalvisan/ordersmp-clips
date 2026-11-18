package lv.ordersmp.clips.encode;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Recording mode: writes every encoded frame to disk instead of only keeping the last minutes.
 * The stream is cut into parts, each starting on a keyframe, so a crash loses at most the part
 * being written and a long session stays easy to edit and upload. A part also ends whenever the
 * encoder restarts (window resized, recording paused, world left), because one file must not
 * change resolution halfway. Closed parts are handed on as raw H.264 to be copied into MP4 files.
 */
public final class SessionRecording implements PacketRing.Tap {

  private static final long MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024;
  private static final int WRITE_BUFFER = 1 << 20;

  private final Path folder;
  private final String name;
  private final long partFrames;
  private final BiConsumer<Path, Path> partClosed;
  private OutputStream out;
  private Path raw;
  private int part;
  private long framesInPart;
  private volatile long frames;
  private volatile String problem;

  /**
   * @param folder where parts are written
   * @param name session name; parts are named {@code <name>-part<n>.mp4}
   * @param partFrames frames in one part before the next keyframe starts a new one
   * @param partClosed receives each closed part's raw file and the MP4 it should become
   */
  public SessionRecording(Path folder, String name, long partFrames, BiConsumer<Path, Path> partClosed) {
    this.folder = folder;
    this.name = name;
    this.partFrames = partFrames;
    this.partClosed = partClosed;
  }

  @Override
  public synchronized void frame(byte[] data, int length, boolean keyframe) {
    if (problem != null) {
      return;
    }
    if (out != null && keyframe && framesInPart >= partFrames) {
      closePart();
    }
    if (out == null) {
      if (!keyframe || !openPart()) {
        return;
      }
    }
    try {
      out.write(data, 0, length);
      framesInPart++;
      frames++;
    } catch (IOException e) {
      problem = "Recording stopped: the clips folder could not be written.";
      closePart();
    }
  }

  /** Ends the current part at once; the next keyframe starts a new one. */
  public synchronized void split() {
    closePart();
  }

  /** Ends the session and hands on the last part. Returns how many parts were written. */
  public synchronized int stop() {
    closePart();
    problem = problem == null ? "stopped" : problem;
    return part;
  }

  /** Frames written so far; seconds recorded is this divided by the frame rate. */
  public long frames() {
    return frames;
  }

  /** Why recording stopped by itself (disk full, write error), or null while it runs. */
  public String problem() {
    String current = problem;
    return "stopped".equals(current) ? null : current;
  }

  private boolean openPart() {
    try {
      Files.createDirectories(folder);
      if (folder.toFile().getUsableSpace() < MIN_FREE_BYTES) {
        problem = "Recording stopped: less than 2 GB free on the disk.";
        return false;
      }
      part++;
      raw = folder.resolve(".recording-" + name + "-" + part + ".h264");
      out = new BufferedOutputStream(Files.newOutputStream(raw), WRITE_BUFFER);
      framesInPart = 0;
      return true;
    } catch (IOException e) {
      problem = "Recording stopped: the clips folder could not be written.";
      return false;
    }
  }

  private void closePart() {
    if (out == null) {
      return;
    }
    Path closed = raw;
    try {
      out.close();
    } catch (IOException e) {
      problem = "Recording stopped: the clips folder could not be written.";
    }
    out = null;
    raw = null;
    partClosed.accept(closed, folder.resolve(name + "-part" + part + ".mp4"));
  }
}
