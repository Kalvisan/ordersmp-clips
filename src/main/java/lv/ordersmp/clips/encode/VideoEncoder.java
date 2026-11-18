package lv.ordersmp.clips.encode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;

/**
 * Runs one ffmpeg process that turns raw frames into H.264 and fills the {@link PacketRing}. The
 * game hands over frames whenever it has one; a writer thread sends them to ffmpeg at a steady
 * frame rate, repeating the last frame when the game is slower, so the video timing is always
 * exact. A reader thread splits ffmpeg's output into frames. Nothing here runs on the render
 * thread except the short copy in {@link #offer}.
 */
public final class VideoEncoder implements AutoCloseable {

  private static final int POOL_SIZE = 3;

  private final Process process;
  private final PacketRing ring;
  private final Logger log;
  private final int width;
  private final int height;
  private final int fps;
  private final int frameBytes;
  private final BlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(POOL_SIZE);
  private final AtomicReference<ByteBuffer> pending = new AtomicReference<>();
  private final AtomicLong framesSent = new AtomicLong();
  private final AtomicLong framesRepeated = new AtomicLong();
  private final AtomicLong framesSkipped = new AtomicLong();
  private final Thread writer;
  private final Thread reader;
  private volatile boolean running = true;
  private volatile String failure;

  private VideoEncoder(Process process, PacketRing ring, Logger log, int width, int height, int fps) {
    this.process = process;
    this.ring = ring;
    this.log = log;
    this.width = width;
    this.height = height;
    this.fps = fps;
    this.frameBytes = width * height * 4;
    for (int i = 0; i < POOL_SIZE; i++) {
      pool.add(ByteBuffer.allocateDirect(frameBytes));
    }
    this.writer = Thread.ofPlatform().daemon().name("Clips encoder writer").start(this::writeLoop);
    this.reader = Thread.ofPlatform().daemon().name("Clips encoder reader").start(this::readLoop);
  }

  /** Starts ffmpeg for frames of the given size; throws when the process cannot start. */
  public static VideoEncoder start(String ffmpeg, Codec codec, int width, int height, int fps, int bitrate,
      PacketRing ring, Logger log) throws IOException {
    List<String> cmd = new ArrayList<>(List.of(
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin",
        "-f", "rawvideo", "-pix_fmt", "rgba", "-s", width + "x" + height, "-framerate", Integer.toString(fps),
        "-i", "pipe:0", "-vf", "vflip"));
    cmd.addAll(codec.args(bitrate, fps));
    cmd.addAll(List.of("-bsf:v", "h264_metadata=aud=insert", "-f", "h264", "pipe:1"));
    Process process = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    return new VideoEncoder(process, ring, log, width, height, fps);
  }

  /** Picture width this encoder was started for. */
  public int width() {
    return width;
  }

  /** Picture height this encoder was started for. */
  public int height() {
    return height;
  }

  /** Why the encoder stopped, or null while it works. */
  public String failure() {
    return failure;
  }

  /** Frames sent to ffmpeg, frames repeated because the game was slower, frames the game skipped. */
  public long[] counters() {
    return new long[] {framesSent.get(), framesRepeated.get(), framesSkipped.get()};
  }

  /**
   * Hands over one rendered frame (rows bottom to top, RGBA). Copies it into a spare buffer and
   * returns at once; when no buffer is free the frame is skipped and counted, never waited for.
   */
  public void offer(ByteBuffer frame) {
    if (!running || frame.remaining() < frameBytes) {
      return;
    }
    ByteBuffer spare = pool.poll();
    if (spare == null) {
      framesSkipped.incrementAndGet();
      return;
    }
    spare.clear();
    spare.put(0, frame, frame.position(), frameBytes);
    spare.limit(frameBytes);
    ByteBuffer replaced = pending.getAndSet(spare);
    if (replaced != null) {
      pool.offer(replaced);
    }
  }

  private void writeLoop() {
    long interval = 1_000_000_000L / fps;
    long next = System.nanoTime();
    ByteBuffer current = null;
    try (WritableByteChannel out = Channels.newChannel(process.getOutputStream())) {
      while (running) {
        long wait = next - System.nanoTime();
        if (wait > 0) {
          LockSupport.parkNanos(wait);
          continue;
        }
        next += interval;
        if (System.nanoTime() - next > interval * fps) {
          next = System.nanoTime() + interval;
        }
        ByteBuffer fresh = pending.getAndSet(null);
        if (fresh != null) {
          if (current != null) {
            pool.offer(current);
          }
          current = fresh;
        } else if (current != null) {
          framesRepeated.incrementAndGet();
        }
        if (current == null) {
          continue;
        }
        ByteBuffer view = current.duplicate();
        view.position(0).limit(frameBytes);
        while (view.hasRemaining()) {
          out.write(view);
        }
        framesSent.incrementAndGet();
      }
    } catch (IOException e) {
      fail("ffmpeg stopped taking frames: " + e.getMessage());
    }
  }

  private void readLoop() {
    AccessUnitSplitter splitter = new AccessUnitSplitter(ring);
    byte[] chunk = new byte[1 << 16];
    try (InputStream in = process.getInputStream()) {
      int read;
      while ((read = in.read(chunk)) >= 0) {
        splitter.feed(chunk, read);
      }
      splitter.finish();
    } catch (IOException e) {
      fail("ffmpeg output could not be read: " + e.getMessage());
      return;
    }
    if (running) {
      fail("ffmpeg exited with code " + process.exitValue());
    }
  }

  private void fail(String reason) {
    if (!running) {
      return;
    }
    running = false;
    failure = reason;
    log.warn("Clips recording stopped: {}", reason);
  }

  /** Stops ffmpeg and both threads; safe to call more than once. */
  @Override
  public void close() {
    running = false;
    LockSupport.unpark(writer);
    try {
      process.getOutputStream().close();
    } catch (IOException e) {
      log.debug("ffmpeg input was already closed", e);
    }
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
      reader.join(TimeUnit.SECONDS.toMillis(2));
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
