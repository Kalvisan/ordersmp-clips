package lv.ordersmp.clips.audio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * The game's sounds as a log instead of a recording: which file played, how loud, at what pitch,
 * where, and where the listener stood and looked. Logging costs almost nothing while playing and
 * never touches the live sound device; the clip's sound is mixed from the log when it is saved.
 * Only the client thread writes; the saving thread reads a copy taken under the lock.
 */
public final class SoundLog {

  /**
   * One played sound.
   *
   * @param file the sound file, such as {@code minecraft:sounds/random/click.ogg}
   * @param volume volume as the player heard it, after the sound settings
   * @param pitch playback speed
   * @param distance distance at which the sound fades out, or 0 when it never fades
   * @param relative whether the position is relative to the listener
   * @param looping whether it repeats until stopped
   * @param stream whether it is long and streamed, such as music, played without panning
   * @param start when it started, from {@link System#nanoTime()}
   * @param end when it stopped, or 0 while it still plays
   * @param path where it was, from its start; a still sound has one point
   */
  public record Played(Identifier file, float volume, float pitch, float distance, boolean relative, boolean looping,
      boolean stream, long start, long end, List<Point> path) {}

  /**
   * A position at a moment.
   *
   * @param time when, from {@link System#nanoTime()}
   * @param position where
   */
  public record Point(long time, Vec3 position) {}

  /**
   * Where the listener was and which way they faced.
   *
   * @param time when, from {@link System#nanoTime()}
   * @param position where
   * @param right the listener's right side, for panning
   */
  public record Ear(long time, Vec3 position, Vec3 right) {}

  /**
   * Everything needed to mix one stretch of time.
   *
   * @param sounds sounds that play during it
   * @param ears where the listener was during it
   */
  public record Snapshot(List<Played> sounds, List<Ear> ears) {}

  private static final long EXTRA_NANOS = 30_000_000_000L;
  private static final long LONGEST_NANOS = 600_000_000_000L;
  private static final int MAX_SOUNDS = 20_000;
  private static final int TRIM_TICKS = 20;

  private static final class Playing {
    private final Identifier file;
    private final float volume;
    private final float pitch;
    private final float distance;
    private final boolean relative;
    private final boolean looping;
    private final boolean stream;
    private final long start;
    private final List<Point> path = new ArrayList<>(1);
    private SoundInstance instance;
    private long end;

    private Playing(Identifier file, float volume, float pitch, float distance, boolean relative, boolean looping,
        boolean stream, long start, Vec3 position, SoundInstance instance) {
      this.file = file;
      this.volume = volume;
      this.pitch = pitch;
      this.distance = distance;
      this.relative = relative;
      this.looping = looping;
      this.stream = stream;
      this.start = start;
      this.instance = instance;
      path.add(new Point(start, position));
    }

    private Played copy() {
      return new Played(file, volume, pitch, distance, relative, looping, stream, start, end, List.copyOf(path));
    }
  }

  private final ArrayDeque<Playing> sounds = new ArrayDeque<>();
  private final List<Playing> active = new ArrayList<>();
  private final ArrayDeque<Ear> ears = new ArrayDeque<>();
  private int ticks;
  private volatile long windowNanos = 120_000_000_000L + EXTRA_NANOS;

  /** Keeps at least {@code seconds} of sound, plus a little more for sounds that started earlier. */
  public void keep(int seconds) {
    windowNanos = seconds * 1_000_000_000L + EXTRA_NANOS;
  }

  /** Called on the client thread when the game starts a sound. */
  public synchronized void played(SoundInstance instance, Identifier file, float volume, float pitch, float distance,
      Vec3 position, boolean looping, boolean stream) {
    if (volume <= 0) {
      return;
    }
    Playing sound = new Playing(file, volume, pitch, distance, instance.isRelative(), looping, stream, System.nanoTime(),
        position, instance);
    sounds.addLast(sound);
    active.add(sound);
    while (sounds.size() > MAX_SOUNDS) {
      Playing dropped = sounds.removeFirst();
      active.remove(dropped);
    }
  }

  /**
   * Called every client tick: notes where the listener is, which sounds stopped, and where moving
   * sounds went. {@code stillPlaying} asks the sound engine whether a sound is still on.
   */
  public synchronized void tick(Vec3 listener, Vec3 right, Predicate<SoundInstance> stillPlaying) {
    long now = System.nanoTime();
    ears.addLast(new Ear(now, listener, right));
    Iterator<Playing> it = active.iterator();
    while (it.hasNext()) {
      Playing sound = it.next();
      if (!stillPlaying.test(sound.instance) || now - sound.start > LONGEST_NANOS) {
        sound.end = now;
        sound.instance = null;
        it.remove();
        continue;
      }
      if (sound.instance instanceof TickableSoundInstance moving) {
        sound.path.add(new Point(now, new Vec3(moving.getX(), moving.getY(), moving.getZ())));
      }
    }
    long oldest = now - windowNanos;
    while (!ears.isEmpty() && ears.peekFirst().time() < oldest) {
      ears.removeFirst();
    }
    if (++ticks % TRIM_TICKS == 0) {
      sounds.removeIf(sound -> sound.end != 0 && sound.end < oldest);
    }
  }

  /** A copy of the sounds that play between {@code from} and {@code to}, and the listener then. */
  public synchronized Snapshot between(long from, long to) {
    List<Played> overlapping = new ArrayList<>();
    for (Playing sound : sounds) {
      if (sound.start < to && (sound.end == 0 || sound.end > from)) {
        overlapping.add(sound.copy());
      }
    }
    List<Ear> heard = new ArrayList<>();
    Ear before = null;
    for (Ear ear : ears) {
      if (ear.time() < from) {
        before = ear;
        continue;
      }
      if (ear.time() > to) {
        heard.add(ear);
        break;
      }
      heard.add(ear);
    }
    if (before != null) {
      heard.add(0, before);
    }
    return new Snapshot(overlapping, heard);
  }
}
