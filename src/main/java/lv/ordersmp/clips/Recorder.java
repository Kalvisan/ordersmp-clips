package lv.ordersmp.clips;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import lv.ordersmp.clips.audio.ClipAudio;
import lv.ordersmp.clips.capture.FrameCapture;
import lv.ordersmp.clips.encode.ClipWriter;
import lv.ordersmp.clips.encode.EncoderProbe;
import lv.ordersmp.clips.encode.PacketRing;
import lv.ordersmp.clips.encode.SessionRecording;
import lv.ordersmp.clips.encode.VideoEncoder;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Keeps the last minutes of play encoded and ready to save. It owns the GPU capture, the ffmpeg
 * encoder and the rolling buffer, and measures what recording costs the render thread so the cost
 * can be checked rather than assumed.
 */
public final class Recorder {

  /** What the recording mark shows. */
  public enum State { STARTING, RECORDING, PAUSED, SAVING, PROBLEM }

  /**
   * Numbers for the stats overlay.
   *
   * @param encoder encoder name
   * @param width recorded width
   * @param height recorded height
   * @param fps recorded frame rate
   * @param heldSeconds seconds of video ready to save
   * @param heldMegabytes size of that video in memory
   * @param renderMicros average render-thread time per frame spent on recording
   * @param renderMaxMicros worst render-thread time in the last second
   * @param sent frames sent to the encoder
   * @param repeated frames repeated because the game ran slower than the recording
   * @param skipped frames the game produced but recording had no room for
   * @param gameFps the game's own frame rate, to compare with recording paused
   */
  public record Stats(String encoder, int width, int height, int fps, int heldSeconds, double heldMegabytes,
      double renderMicros, double renderMaxMicros, long sent, long repeated, long skipped, int gameFps) {}

  private static final long SAVE_COOLDOWN_NANOS = 2_000_000_000L;

  private volatile ClipsConfig config;
  private final Logger log;
  private final Path clipsFolder;
  private final FrameCapture capture = new FrameCapture();
  private volatile PacketRing ring;
  private final ExecutorService saver = Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("Clips saver").unstarted(r));
  private volatile EncoderProbe.Result probe;
  private volatile VideoEncoder encoder;
  private volatile State state = State.STARTING;
  private volatile String problem;
  private boolean paused;
  private long nextCaptureNanos;
  private double renderMicrosAverage;
  private double renderMicrosMax;
  private double renderMicrosMaxShown;
  private long maxWindowStart;
  private volatile long lastSaveNanos;
  private volatile SessionRecording session;
  private volatile ClipAudio audio;

  public Recorder(ClipsConfig config, Path clipsFolder, Logger log) {
    this.config = config;
    this.clipsFolder = clipsFolder;
    this.log = log;
    this.ring = ringFor(config);
  }

  /**
   * Applies changed settings at once. A new quality, frame rate or buffer length needs a new
   * buffer and encoder, so the minutes held so far are dropped; Recording mode keeps going.
   */
  public void reconfigure(ClipsConfig next) {
    ClipsConfig before = config;
    config = next;
    if (before.bufferSeconds() == next.bufferSeconds() && before.fps() == next.fps() && before.maxHeight() == next.maxHeight()) {
      return;
    }
    stopEncoder();
    PacketRing fresh = ringFor(next);
    fresh.tap(session);
    ring = fresh;
    nextCaptureNanos = 0;
  }

  private static PacketRing ringFor(ClipsConfig config) {
    int frames = config.bufferSeconds() * config.fps();
    long bytes = (long) config.bitrateFor(config.maxHeight()) * 3 / 2 / 8 * (config.bufferSeconds() + 5);
    return new PacketRing((int) Math.min(Integer.MAX_VALUE - 8, bytes), frames);
  }

  /** Gives saved clips and Recording parts their sound from now on. */
  public void setAudio(ClipAudio audio) {
    this.audio = audio;
  }

  /** Looks for ffmpeg and a working encoder in the background; recording starts when it is done. */
  public CompletableFuture<EncoderProbe.Result> probe(Path modFolder) {
    return CompletableFuture.supplyAsync(() -> EncoderProbe.run(config.ffmpegPath(), config.encoder(), modFolder), saver)
        .whenComplete((result, error) -> {
          if (error != null) {
            setProblem("the encoder check failed: " + error.getMessage());
            return;
          }
          probe = result;
          if (!result.ready()) {
            setProblem(result.problem());
            return;
          }
          log.info("Clips will record with {} through {}", result.codec().label(), result.ffmpeg());
          state = paused ? State.PAUSED : State.RECORDING;
        });
  }

  /** Current state for the recording mark. */
  public State state() {
    return state;
  }

  /** Where clips are saved. */
  public Path clipsFolder() {
    return clipsFolder;
  }

  /** How many seconds of play a saved clip holds at most. */
  public int bufferSeconds() {
    return config.bufferSeconds();
  }

  /** Why recording is off, or null. */
  public String problem() {
    return problem;
  }

  /**
   * Called once per rendered frame on the render thread, after the whole frame including the HUD
   * was drawn. Captures at most at the recording frame rate and never waits for the GPU. Outside a
   * world (title screen, loading) nothing is recorded and the encoder is stopped to save resources.
   */
  public void onFrameRendered(RenderTarget target, boolean inWorld) {
    if (state == State.PROBLEM || state == State.STARTING || paused) {
      return;
    }
    if (!inWorld) {
      stopEncoder();
      return;
    }
    long start = System.nanoTime();
    VideoEncoder current = encoder;
    if (current != null && current.failure() != null) {
      setProblem(current.failure());
      return;
    }
    if (start < nextCaptureNanos) {
      capture.drain(this::deliver);
      measure(start);
      return;
    }
    nextCaptureNanos = Math.max(nextCaptureNanos + 1_000_000_000L / config.fps(), start);
    boolean sameSize = capture.capture(target, config.maxHeight(), this::deliver);
    if (!sameSize || current == null) {
      restartEncoder();
    }
    measure(start);
  }

  /** Saves the whole buffer as a clip in the background and reports the result to {@code done}. */
  public void saveClip(Consumer<ClipWriter.Saved> done) {
    long now = System.nanoTime();
    if (state == State.SAVING || now - lastSaveNanos < SAVE_COOLDOWN_NANOS) {
      return;
    }
    lastSaveNanos = now;
    save(config.bufferSeconds(), 0, "", done);
  }

  /**
   * Saves an automatic clip: {@code seconds} of play ending {@code skipSeconds} ago, named with
   * {@code suffix}. Unlike the save key it has no cooldown, since the fight rules already space
   * clips apart. Longer requests are cut to what the buffer holds.
   */
  public void saveMoment(int seconds, int skipSeconds, String suffix, Consumer<ClipWriter.Saved> done) {
    int skip = Math.max(0, Math.min(skipSeconds, config.bufferSeconds() - 1));
    save(Math.min(seconds, config.bufferSeconds() - skip), skip, suffix, done);
  }

  private void save(int seconds, int skipSeconds, String suffix, Consumer<ClipWriter.Saved> done) {
    EncoderProbe.Result ready = probe;
    if (ready == null || !ready.ready()) {
      done.accept(ClipWriter.Saved.failed(problem == null ? "recording has not started yet" : problem));
      return;
    }
    State before = state == State.SAVING ? State.RECORDING : state;
    state = State.SAVING;
    saver.execute(() -> {
      ClipWriter.Saved saved = ClipWriter.save(ring, ready.ffmpeg(), config.fps(), seconds, skipSeconds, clipsFolder, suffix);
      ClipAudio sound = audio;
      if (saved.file() != null && sound != null) {
        sound.addTo(saved.file(), saved.startNanos(), saved.endNanos(), ready.ffmpeg());
      }
      if (saved.file() != null) {
        ClipLibrary.thumbnail(saved.file(), ready.ffmpeg());
      }
      if (state == State.SAVING) {
        state = before;
      }
      done.accept(saved);
    });
  }

  /** Makes the thumbnail of {@code clip} in the background if it is missing; null when it cannot. */
  public CompletableFuture<Path> thumbnail(Path clip) {
    return withFfmpeg(ffmpeg -> ClipLibrary.thumbnail(clip, ffmpeg));
  }

  /**
   * Runs {@code work} with the ffmpeg program on the saving thread, so only one ffmpeg job runs at
   * a time and none competes with saving a clip. Completes with null when ffmpeg is not available.
   */
  public <T> CompletableFuture<T> withFfmpeg(Function<String, T> work) {
    EncoderProbe.Result ready = probe;
    if (ready == null || ready.ffmpeg() == null) {
      return CompletableFuture.completedFuture(null);
    }
    return CompletableFuture.supplyAsync(() -> work.apply(ready.ffmpeg()), saver);
  }

  /** The ffmpeg program found at start, or null when there is none yet. */
  public String ffmpeg() {
    EncoderProbe.Result ready = probe;
    return ready == null ? null : ready.ffmpeg();
  }

  /**
   * Starts Recording mode: everything from now on is written to disk in parts, while the last
   * minutes stay ready for the save key as before. Returns a reason when it cannot start, else null.
   */
  public String startRecording() {
    EncoderProbe.Result ready = probe;
    if (ready == null || !ready.ready()) {
      return problem == null ? "recording has not started yet" : problem;
    }
    if (session != null) {
      return null;
    }
    long partFrames = (long) config.recordingPartMinutes() * 60 * config.fps();
    SessionRecording started = new SessionRecording(clipsFolder, ClipWriter.nameNow(), partFrames, this::finishPart);
    session = started;
    ring.tap(started);
    log.info("Recording mode started");
    return null;
  }

  /** Stops Recording mode; returns how many parts it wrote, which are finished in the background. */
  public int stopRecording() {
    SessionRecording current = session;
    if (current == null) {
      return 0;
    }
    session = null;
    ring.tap(null);
    int parts = current.stop();
    log.info("Recording mode stopped after {} parts", parts);
    return parts;
  }

  /** Whether Recording mode is on. */
  public boolean isRecordingSession() {
    return session != null;
  }

  /** Seconds Recording mode has written so far. */
  public int recordingSeconds() {
    SessionRecording current = session;
    return current == null ? 0 : (int) (current.frames() / config.fps());
  }

  /** Why Recording mode stopped by itself (disk full, write error), or null. */
  public String recordingProblem() {
    SessionRecording current = session;
    return current == null ? null : current.problem();
  }

  /** Turns a closed raw part into an MP4 with a thumbnail, on the saving thread, and removes the raw file. */
  private void finishPart(Path raw, Path target, long startNanos, long endNanos) {
    withFfmpeg(ffmpeg -> {
      try {
        String failed = ClipWriter.remux(ffmpeg, raw, config.fps(), target);
        if (failed != null) {
          log.warn("Recording part {} could not be written: {}", target.getFileName(), failed);
          return null;
        }
        Files.deleteIfExists(raw);
        ClipAudio sound = audio;
        if (sound != null) {
          sound.addTo(target, startNanos, endNanos + 1_000_000_000L / config.fps(), ffmpeg);
        }
        ClipLibrary.thumbnail(target, ffmpeg);
      } catch (IOException e) {
        log.warn("Recording part {} could not be written", target.getFileName(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return target;
    });
  }

  /** Pauses or resumes recording; resuming starts a fresh buffer. Returns true when now paused. */
  public boolean togglePause() {
    paused = !paused;
    if (state == State.PROBLEM || state == State.STARTING) {
      return paused;
    }
    if (paused) {
      stopEncoder();
      state = State.PAUSED;
      return true;
    }
    ring.clear();
    state = State.RECORDING;
    return false;
  }

  /** Numbers for the stats overlay. */
  public Stats stats() {
    VideoEncoder current = encoder;
    long[] counters = current == null ? new long[3] : current.counters();
    EncoderProbe.Result ready = probe;
    String name = ready == null || ready.codec() == null ? "none" : ready.codec().label();
    return new Stats(name, capture.width(), capture.height(), config.fps(), ring.frames() / config.fps(),
        ring.bytes() / 1_048_576.0, renderMicrosAverage, renderMicrosMaxShown, counters[0], counters[1], counters[2],
        Minecraft.getInstance().getFps());
  }

  /** Stops recording and frees the encoder and GPU resources; waits briefly for parts to finish. */
  public void shutdown() {
    stopRecording();
    stopEncoder();
    capture.close();
    saver.shutdown();
    try {
      saver.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void deliver(ByteBuffer frame) {
    VideoEncoder current = encoder;
    if (current == null || current.width() != capture.width() || current.height() != capture.height()) {
      return;
    }
    current.offer(frame);
  }

  private void restartEncoder() {
    stopEncoder();
    ring.clear();
    EncoderProbe.Result ready = probe;
    int w = capture.width();
    int h = capture.height();
    if (w == 0 || h == 0) {
      return;
    }
    try {
      encoder = VideoEncoder.start(ready.ffmpeg(), ready.codec(), w, h, config.fps(), config.bitrateFor(h), ring, log);
    } catch (IOException e) {
      setProblem("ffmpeg could not start: " + e.getMessage());
    }
  }

  private void stopEncoder() {
    SessionRecording recording = session;
    if (recording != null) {
      recording.split();
    }
    VideoEncoder current = encoder;
    encoder = null;
    if (current != null) {
      current.close();
    }
  }

  private void setProblem(String reason) {
    problem = reason;
    state = State.PROBLEM;
    stopEncoder();
    log.warn("Clips is not recording: {}", reason);
  }

  private void measure(long start) {
    double micros = (System.nanoTime() - start) / 1_000.0;
    renderMicrosAverage = renderMicrosAverage == 0 ? micros : renderMicrosAverage * 0.98 + micros * 0.02;
    renderMicrosMax = Math.max(renderMicrosMax, micros);
    if (start - maxWindowStart > 1_000_000_000L) {
      renderMicrosMaxShown = renderMicrosMax;
      renderMicrosMax = 0;
      maxWindowStart = start;
    }
  }
}
