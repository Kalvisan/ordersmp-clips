package lv.ordersmp.clips.encode;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * The rolling buffer of encoded video, one entry per frame, kept in one fixed block of memory
 * outside the Java heap so recording adds no garbage collection work. The oldest frames are
 * dropped as new ones arrive, and the buffer always starts on a keyframe so it can be saved as a
 * playable clip at any moment.
 */
public final class PacketRing {

  private record Entry(int offset, int length, boolean keyframe, long time) {}

  /**
   * What {@link #writeLatest} wrote.
   *
   * @param frames frames written
   * @param first time of the first frame written, from {@link System#nanoTime()}
   * @param last time of the last frame written
   */
  public record Written(int frames, long first, long last) {}

  /** Receives every encoded frame as it arrives, before the ring keeps or drops it. */
  public interface Tap {
    void frame(byte[] data, int length, boolean keyframe, long time);
  }

  private final ByteBuffer memory;
  private final ArrayDeque<Entry> entries = new ArrayDeque<>();
  private final int maxFrames;
  private int writeAt;
  private long dropped;
  private volatile Tap tap;

  /**
   * @param capacityBytes size of the memory block
   * @param maxFrames how many frames to keep (seconds times frame rate)
   */
  public PacketRing(int capacityBytes, int maxFrames) {
    this.memory = ByteBuffer.allocateDirect(capacityBytes);
    this.maxFrames = maxFrames;
  }

  /** Sends every new frame to {@code tap} as well, or stops doing so with null. */
  public void tap(Tap tap) {
    this.tap = tap;
  }

  /**
   * Adds one encoded frame taken at {@code time}; frames larger than a quarter of the buffer are
   * dropped and counted.
   */
  public synchronized void add(byte[] data, int length, boolean keyframe, long time) {
    Tap current = tap;
    if (current != null) {
      current.frame(data, length, keyframe, time);
    }
    if (length > memory.capacity() / 4) {
      dropped++;
      return;
    }
    if (writeAt + length > memory.capacity()) {
      writeAt = 0;
    }
    evictOverlapping(writeAt, length);
    memory.put(writeAt, data, 0, length);
    entries.addLast(new Entry(writeAt, length, keyframe, time));
    writeAt += length;
    while (entries.size() > maxFrames) {
      entries.removeFirst();
    }
    trimToKeyframe();
  }

  /** Number of frames held, so seconds held is this divided by the frame rate. */
  public synchronized int frames() {
    return entries.size();
  }

  /** Bytes of video held right now. */
  public synchronized long bytes() {
    long total = 0;
    for (Entry entry : entries) {
      total += entry.length();
    }
    return total;
  }

  /** Frames dropped because a single frame was too large to keep. */
  public synchronized long dropped() {
    return dropped;
  }

  /** Forgets everything, for example after the encoder restarted with a new picture size. */
  public synchronized void clear() {
    entries.clear();
    writeAt = 0;
  }

  /**
   * Writes up to {@code frames} frames that end {@code skipNewest} frames before the newest one,
   * starting at the first keyframe in that range, so the written stream always decodes. Skipping
   * the newest frames lets a clip end at a moment that is already a few seconds in the past.
   */
  public synchronized Written writeLatest(int frames, int skipNewest, OutputStream out) throws IOException {
    int end = Math.max(0, entries.size() - skipNewest);
    int start = Math.max(0, end - frames);
    Iterator<Entry> it = entries.iterator();
    WritableByteChannel channel = Channels.newChannel(out);
    int written = 0;
    long first = 0;
    long last = 0;
    boolean started = false;
    for (int index = 0; index < end && it.hasNext(); index++) {
      Entry entry = it.next();
      if (index < start || (!started && !entry.keyframe())) {
        continue;
      }
      if (!started) {
        first = entry.time();
      }
      started = true;
      channel.write(memory.slice(entry.offset(), entry.length()));
      last = entry.time();
      written++;
    }
    return new Written(written, first, last);
  }

  private void evictOverlapping(int start, int length) {
    int end = start + length;
    while (!entries.isEmpty()) {
      Entry oldest = entries.peekFirst();
      boolean overlaps = oldest.offset() < end && oldest.offset() + oldest.length() > start;
      if (!overlaps) {
        return;
      }
      entries.removeFirst();
    }
  }

  private void trimToKeyframe() {
    while (!entries.isEmpty() && !entries.peekFirst().keyframe()) {
      entries.removeFirst();
    }
  }
}
