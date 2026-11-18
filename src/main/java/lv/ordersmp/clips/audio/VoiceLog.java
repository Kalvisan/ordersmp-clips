package lv.ordersmp.clips.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Voices from Simple Voice Chat, one stream per speaker, as they arrive: 20 ms frames of 48 kHz
 * mono sound with the time each was heard. Voice chat sends nothing while someone is silent, so this
 * only grows while people talk. Memory is bounded: the oldest frames go first once the limit is
 * reached. Frames arrive on the voice chat's own threads; everything here is synchronized.
 */
public final class VoiceLog {

  /** The key of the player's own voice. */
  public static final String MINE = "me";

  /** Samples per frame and samples per second, as voice chat delivers them. */
  public static final int FRAME = 960;
  public static final int RATE = 48_000;

  /**
   * One frame of one voice.
   *
   * @param time when it was heard, from {@link System#nanoTime()}
   * @param samples 16-bit mono sound
   */
  public record Frame(long time, short[] samples) {}

  /**
   * One speaker's frames inside a stretch of time.
   *
   * @param key {@link #MINE} or the speaker's id
   * @param name the name to show, such as a player name
   * @param frames the frames, oldest first
   */
  public record Voice(String key, String name, List<Frame> frames) {}

  private static final long EXTRA_NANOS = 30_000_000_000L;
  private static final int MAX_FRAMES = 200_000;

  private final Map<String, ArrayDeque<Frame>> speakers = new HashMap<>();
  private final Map<String, String> names = new ConcurrentHashMap<>();
  private int total;
  private volatile long windowNanos = 120_000_000_000L + EXTRA_NANOS;

  /** Keeps at least {@code seconds} of voices. */
  public void keep(int seconds) {
    windowNanos = seconds * 1_000_000_000L + EXTRA_NANOS;
  }

  /** The player's own microphone sent a frame. */
  public void spoke(short[] samples) {
    heard(MINE, samples);
  }

  /** A frame of another speaker's voice arrived. */
  public void heard(UUID speaker, short[] samples) {
    heard(speaker.toString(), samples);
  }

  /** The speakers heard so far that have no name yet, for the client thread to look up. */
  public List<UUID> unnamed() {
    List<UUID> missing = new ArrayList<>();
    synchronized (this) {
      for (String key : speakers.keySet()) {
        if (!key.equals(MINE) && !names.containsKey(key)) {
          missing.add(UUID.fromString(key));
        }
      }
    }
    return missing;
  }

  /** Remembers the name to show for a speaker. */
  public void name(String key, String name) {
    names.put(key, name);
  }

  /** Each voice heard between {@code from} and {@code to}. */
  public synchronized List<Voice> between(long from, long to) {
    List<Voice> voices = new ArrayList<>();
    for (Map.Entry<String, ArrayDeque<Frame>> speaker : speakers.entrySet()) {
      List<Frame> inside = new ArrayList<>();
      for (Frame frame : speaker.getValue()) {
        if (frame.time() >= from && frame.time() <= to) {
          inside.add(frame);
        }
      }
      if (!inside.isEmpty()) {
        voices.add(new Voice(speaker.getKey(), names.getOrDefault(speaker.getKey(), speaker.getKey()), inside));
      }
    }
    return voices;
  }

  private synchronized void heard(String key, short[] samples) {
    if (samples == null || samples.length == 0) {
      return;
    }
    long now = System.nanoTime();
    speakers.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(new Frame(now, samples.clone()));
    total++;
    long oldest = now - windowNanos;
    for (ArrayDeque<Frame> frames : speakers.values()) {
      while (!frames.isEmpty() && (frames.peekFirst().time() < oldest || total > MAX_FRAMES)) {
        frames.removeFirst();
        total--;
      }
    }
  }
}
