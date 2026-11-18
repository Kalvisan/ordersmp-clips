package lv.ordersmp.clips.audio;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Mixes a stretch of the {@link SoundLog} into stereo, offline and faster than real time, from the
 * same sound files the game played. It follows the game's own model: a linear fade to silence at
 * the sound's distance, panning by where the listener faced, pitch as playback speed, repeating
 * loops, and long streamed sounds such as music without panning. Decoded files are cached, within
 * a memory limit, because fights repeat the same few sounds.
 */
public final class SoundMixer {

  /** The opener of sound files, such as the game's resource manager. */
  public interface Opener {
    InputStream open(Identifier file) throws IOException;
  }

  /**
   * Mixed stereo sound.
   *
   * @param left left channel, from -1 to 1
   * @param right right channel
   */
  public record Stereo(float[] left, float[] right) {}

  public static final int RATE = 48_000;
  private static final int BLOCK = 480;
  private static final long CACHE_SAMPLES = 24_000_000L;
  private static final double NANOS = 1_000_000_000.0;
  private static final double HALF_SQRT = Math.sqrt(0.5);

  private record Pcm(float[] samples, int channels, float rate) {

    private int frames() {
      return samples.length / channels;
    }
  }

  private final Opener opener;
  private final Map<Identifier, Pcm> cache = new LinkedHashMap<>(64, 0.75f, true);
  private long cached;

  public SoundMixer(Opener opener) {
    this.opener = opener;
  }

  /** Mixes what was heard between {@code from} and {@code to}, times from {@link System#nanoTime()}. */
  public synchronized Stereo mix(SoundLog.Snapshot snapshot, long from, long to) {
    int frames = (int) Math.max(0, Math.round((to - from) * RATE / NANOS));
    float[] left = new float[frames];
    float[] right = new float[frames];
    for (SoundLog.Played sound : snapshot.sounds()) {
      Pcm pcm = decode(sound.file());
      if (pcm == null || pcm.frames() == 0) {
        continue;
      }
      add(sound, pcm, snapshot.ears(), from, left, right);
    }
    return new Stereo(left, right);
  }

  /** Writes {@code sound} as a 16-bit stereo WAV file at {@link #RATE}. */
  public static void writeWav(Stereo sound, Path file) throws IOException {
    int frames = sound.left().length;
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + frames * 4)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2)
        .putInt(RATE).putInt(RATE * 4).putShort((short) 4).putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(frames * 4);
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
      out.write(header.array());
      ByteBuffer chunk = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN);
      for (int i = 0; i < frames; i++) {
        chunk.putShort(toShort(sound.left()[i])).putShort(toShort(sound.right()[i]));
        if (!chunk.hasRemaining()) {
          out.write(chunk.array(), 0, chunk.position());
          chunk.clear();
        }
      }
      out.write(chunk.array(), 0, chunk.position());
    }
  }

  private static short toShort(float value) {
    return (short) Math.round(Math.max(-1f, Math.min(1f, value)) * Short.MAX_VALUE);
  }

  private static void add(SoundLog.Played sound, Pcm pcm, List<SoundLog.Ear> ears, long from, float[] left, float[] right) {
    int frames = left.length;
    double step = sound.pitch() * pcm.rate() / RATE;
    long stop = sound.end() == 0 ? Long.MAX_VALUE : sound.end();
    double outStart = (sound.start() - from) * RATE / NANOS;
    int first = (int) Math.max(0, Math.ceil(outStart));
    int last = stop == Long.MAX_VALUE ? frames : (int) Math.min(frames, Math.ceil((stop - from) * RATE / NANOS));
    boolean spatial = pcm.channels() == 1 && !sound.stream();
    boolean repeat = sound.looping() && !sound.stream();
    int length = pcm.frames();
    float[] samples = pcm.samples();
    for (int block = first; block < last; block += BLOCK) {
      long time = from + (long) (block * NANOS / RATE);
      double gainLeft;
      double gainRight;
      if (spatial) {
        double[] heard = place(sound, ears, time);
        gainLeft = sound.volume() * heard[0];
        gainRight = sound.volume() * heard[1];
      } else {
        double each = pcm.channels() == 1 ? HALF_SQRT : 1;
        gainLeft = sound.volume() * each;
        gainRight = sound.volume() * each;
      }
      if (gainLeft <= 0 && gainRight <= 0) {
        continue;
      }
      int end = Math.min(last, block + BLOCK);
      for (int i = block; i < end; i++) {
        double position = (i - outStart) * step;
        long index = (long) position;
        if (index >= length - 1) {
          if (!repeat) {
            return;
          }
          index %= length;
          position = index + (position - Math.floor(position));
        }
        double frac = position - Math.floor(position);
        int at = (int) index;
        int next = Math.min(at + 1, length - 1);
        if (pcm.channels() == 1) {
          double value = samples[at] + (samples[next] - samples[at]) * frac;
          left[i] += (float) (value * gainLeft);
          right[i] += (float) (value * gainRight);
          continue;
        }
        int channels = pcm.channels();
        double l = samples[at * channels] + (samples[next * channels] - samples[at * channels]) * frac;
        double r = samples[at * channels + 1] + (samples[next * channels + 1] - samples[at * channels + 1]) * frac;
        left[i] += (float) (l * gainLeft);
        right[i] += (float) (r * gainRight);
      }
    }
  }

  /** The left and right gain of a sound at {@code time}: its fade by distance and its pan. */
  private static double[] place(SoundLog.Played sound, List<SoundLog.Ear> ears, long time) {
    Vec3 position = positionAt(sound.path(), time);
    double x;
    double distance;
    if (sound.relative()) {
      distance = position.length();
      x = distance > 1e-4 ? position.x / distance : 0;
    } else {
      SoundLog.Ear ear = earAt(ears, time);
      if (ear == null) {
        distance = 0;
        x = 0;
      } else {
        Vec3 direction = position.subtract(ear.position());
        distance = direction.length();
        x = distance > 1e-4 ? direction.dot(ear.right()) / distance : 0;
      }
    }
    double fade = sound.distance() > 0 ? Math.max(0, 1 - distance / sound.distance()) : 1;
    double angle = (Math.max(-1, Math.min(1, x)) + 1) * Math.PI / 4;
    return new double[] {fade * Math.cos(angle), fade * Math.sin(angle)};
  }

  private static Vec3 positionAt(List<SoundLog.Point> path, long time) {
    SoundLog.Point found = path.getFirst();
    for (SoundLog.Point point : path) {
      if (point.time() > time) {
        break;
      }
      found = point;
    }
    return found.position();
  }

  private static SoundLog.Ear earAt(List<SoundLog.Ear> ears, long time) {
    if (ears.isEmpty()) {
      return null;
    }
    int low = 0;
    int high = ears.size() - 1;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (ears.get(middle).time() <= time) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return ears.get(low);
  }

  private Pcm decode(Identifier file) {
    Pcm known = cache.get(file);
    if (known != null) {
      return known;
    }
    try (InputStream in = opener.open(file); JOrbisAudioStream stream = new JOrbisAudioStream(in)) {
      FloatArrayList samples = new FloatArrayList();
      while (stream.readChunk(samples::add)) {
        if (samples.size() > CACHE_SAMPLES) {
          break;
        }
      }
      Pcm pcm = new Pcm(samples.toFloatArray(), Math.max(1, stream.getFormat().getChannels()), stream.getFormat().getSampleRate());
      remember(file, pcm);
      return pcm;
    } catch (IOException | RuntimeException e) {
      remember(file, new Pcm(new float[0], 1, RATE));
      return null;
    }
  }

  private void remember(Identifier file, Pcm pcm) {
    cache.put(file, pcm);
    cached += pcm.samples().length;
    Iterator<Map.Entry<Identifier, Pcm>> it = cache.entrySet().iterator();
    while (cached > CACHE_SAMPLES && it.hasNext()) {
      Map.Entry<Identifier, Pcm> oldest = it.next();
      if (oldest.getKey().equals(file)) {
        continue;
      }
      cached -= oldest.getValue().samples().length;
      it.remove();
    }
  }
}
