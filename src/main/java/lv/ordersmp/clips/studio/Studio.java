package lv.ordersmp.clips.studio;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import lv.ordersmp.clips.ClipLibrary;
import lv.ordersmp.clips.Recorder;
import lv.ordersmp.clips.edit.ClipEdit;
import lv.ordersmp.clips.edit.ClipExport;
import org.slf4j.Logger;

/**
 * The link to OrderSMP Studio and the uploads that use it. The token is kept in the mod's own
 * folder, not in {@code config/}, so it is never shared when a modpack's settings are exported.
 * Uploads run one at a time on their own thread, keep going when the player leaves the editor,
 * continue from the byte Studio already has after a dropped connection, and retry with growing
 * pauses before giving up with a reason the player can read.
 */
public final class Studio {

  /** Where an upload is. */
  public enum Phase { PREPARING, UPLOADING, DONE, FAILED }

  /** One clip's upload; the editor reads it every tick to show live progress. */
  public static final class Job {
    private final Path clip;
    private volatile Phase phase = Phase.PREPARING;
    private volatile long sent;
    private volatile long total;
    private volatile String url;
    private volatile String problem;

    private Job(Path clip) {
      this.clip = clip;
    }

    public Path clip() {
      return clip;
    }

    public Phase phase() {
      return phase;
    }

    /** Share of the file Studio has, from 0 to 1. */
    public double progress() {
      return total <= 0 ? 0 : Math.min(1, (double) sent / total);
    }

    /** The clip's address in Studio once the upload is done. */
    public String url() {
      return url;
    }

    /** Why the upload failed, in words for the player. */
    public String problem() {
      return problem;
    }
  }

  private record Saved(String studioUrl, String token, String expiresAt, String accountName) {}

  private static final Gson GSON = new Gson();
  private static final long[] RETRY_PAUSES_MILLIS = {2_000, 5_000, 15_000, 30_000, 60_000};

  private final String baseUrl;
  private final StudioClient client;
  private final Path accountFile;
  private final Path uploadFolder;
  private final Logger log;
  private final Map<Path, Job> jobs = new ConcurrentHashMap<>();
  private final ExecutorService uploader = Executors.newSingleThreadExecutor(
      r -> Thread.ofPlatform().daemon().name("Clips upload").unstarted(r));
  private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
  private volatile Saved account;

  public Studio(String baseUrl, Path modFolder, Path clipsFolder, Logger log) {
    this.baseUrl = baseUrl;
    this.client = new StudioClient(baseUrl);
    this.accountFile = modFolder.resolve("studio.json");
    this.uploadFolder = clipsFolder.resolve(".uploads");
    this.log = log;
    this.account = load();
  }

  /** Whether this PC is linked to a Studio account whose token has not expired. */
  public boolean isLinked() {
    Saved current = account;
    return current != null && Instant.now().isBefore(Instant.parse(current.expiresAt()));
  }

  /** The linked Studio account's name, or null. */
  public String accountName() {
    Saved current = account;
    return current == null ? null : current.accountName();
  }

  /** Starts linking in the background; the player approves on the returned link's page. */
  public CompletableFuture<StudioClient.Link> startLink() {
    return CompletableFuture.supplyAsync(() -> call(client::startLink), calls);
  }

  /** Polls once; when the player approved, the token is kept and the account name returned. */
  public StudioClient.Poll poll(String deviceCode) throws StudioClient.StudioException {
    StudioClient.Poll result = client.poll(deviceCode);
    if (result.token() == null) {
      return result;
    }
    Saved linked = new Saved(baseUrl, result.token(), result.expiresAt().toString(), result.accountName());
    save(linked);
    account = linked;
    log.info("Linked to OrderSMP Studio as {}", result.accountName());
    return result;
  }

  /** Forgets the link here and revokes the token on Studio's side when Studio can be reached. */
  public void unlink() {
    Saved current = account;
    account = null;
    try {
      Files.deleteIfExists(accountFile);
    } catch (IOException e) {
      log.warn("Could not delete the Studio link file", e);
    }
    if (current == null) {
      return;
    }
    calls.execute(() -> {
      try {
        client.unlink(current.token());
      } catch (StudioClient.StudioException e) {
        log.info("Studio could not be told about the unlink: {}", e.getMessage());
      }
    });
  }

  /** The upload of {@code clip}, or null when it was never started this session. */
  public Job job(Path clip) {
    return jobs.get(clip);
  }

  /**
   * Uploads {@code clip} with {@code edit} applied. An edited clip is first copied to a temporary
   * file with only the kept parts, by the recorder's ffmpeg thread, so the upload never re-encodes.
   * {@code done} runs on the upload thread when the job ends either way.
   */
  public Job upload(ClipLibrary.Clip clip, ClipEdit edit, String title, Recorder recorder, Consumer<Job> done) {
    Job running = jobs.get(clip.file());
    if (running != null && (running.phase == Phase.PREPARING || running.phase == Phase.UPLOADING)) {
      return running;
    }
    Job job = new Job(clip.file());
    jobs.put(clip.file(), job);
    ClipEdit snapshot = edit.copy();
    uploader.execute(() -> {
      run(job, clip, snapshot, title, recorder);
      done.accept(job);
    });
    return job;
  }

  /** Stops uploading when the game closes; an unfinished upload is simply started again later. */
  public void shutdown() {
    uploader.shutdownNow();
    calls.shutdownNow();
  }

  private void run(Job job, ClipLibrary.Clip clip, ClipEdit edit, String title, Recorder recorder) {
    Saved current = account;
    if (current == null) {
      fail(job, "Link Studio first.");
      return;
    }
    Path file = clip.file();
    Path temporary = null;
    try {
      if (!edit.keepsEverything()) {
        Files.createDirectories(uploadFolder);
        Path target = uploadFolder.resolve(ClipLibrary.stem(clip.file()) + ".mp4");
        Files.deleteIfExists(target);
        temporary = recorder.withFfmpeg(ffmpeg -> ClipExport.export(clip.file(), edit, ffmpeg, target)).join();
        if (temporary == null) {
          fail(job, "FFmpeg could not cut the clip.");
          return;
        }
        file = temporary;
      }
      send(job, current, file, title);
    } catch (IOException e) {
      fail(job, "The clip could not be read.");
    } finally {
      deleteQuietly(temporary);
    }
  }

  private void send(Job job, Saved current, Path file, String title) throws IOException {
    long size = Files.size(file);
    job.total = size;
    job.phase = Phase.UPLOADING;
    StudioClient.Upload upload = retrying(job, () -> client.createUpload(current.token(), title, size));
    if (upload == null) {
      return;
    }
    long offset = upload.receivedBytes();
    while (offset < size) {
      long from = offset;
      long length = Math.min(upload.partBytes(), size - from);
      job.sent = from;
      Long received = retrying(job, () -> {
        try (InputStream in = Files.newInputStream(file)) {
          in.skipNBytes(from);
          job.sent = from;
          return client.sendPart(current.token(), upload.id(), from, new BufferedInputStream(
              new BoundedInput(in, length), 1 << 16), length, bytes -> job.sent += bytes);
        } catch (StudioClient.StudioException e) {
          if (e.receivedBytes() >= 0) {
            return e.receivedBytes();
          }
          throw e;
        } catch (IOException e) {
          throw new StudioClient.StudioException(0, "READ", "The clip could not be read.", -1);
        }
      });
      if (received == null) {
        return;
      }
      offset = received;
    }
    job.sent = size;
    String url = retrying(job, () -> client.finish(current.token(), upload.id()));
    if (url == null) {
      return;
    }
    job.url = url;
    job.phase = Phase.DONE;
    log.info("Uploaded {} to Studio", file.getFileName());
  }

  /** A Studio call that may throw. */
  private interface Call<T> {
    T run() throws StudioClient.StudioException;
  }

  /**
   * Runs {@code call}, retrying when Studio could not be reached or answered with a server error.
   * A refused token clears the link, since retrying cannot fix it. Returns null after failing.
   */
  private <T> T retrying(Job job, Call<T> call) {
    for (int attempt = 0; ; attempt++) {
      try {
        return call.run();
      } catch (StudioClient.StudioException e) {
        if (e.status() == 401) {
          account = null;
          fail(job, "The Studio link expired. Link again.");
          return null;
        }
        boolean worthRetrying = e.status() == 0 || e.status() >= 500 || e.status() == 429;
        if (!worthRetrying || attempt >= RETRY_PAUSES_MILLIS.length || "CANCELLED".equals(e.code())) {
          fail(job, e.getMessage());
          return null;
        }
        try {
          Thread.sleep(RETRY_PAUSES_MILLIS[attempt]);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          fail(job, "The upload was stopped.");
          return null;
        }
      }
    }
  }

  private <T> T call(Call<T> call) {
    try {
      return call.run();
    } catch (StudioClient.StudioException e) {
      throw new CompletionException(e);
    }
  }

  private void fail(Job job, String problem) {
    job.problem = problem;
    job.phase = Phase.FAILED;
    log.info("Upload of {} failed: {}", job.clip.getFileName(), problem);
  }

  private Saved load() {
    if (!Files.isRegularFile(accountFile)) {
      return null;
    }
    try {
      Saved saved = GSON.fromJson(Files.readString(accountFile, StandardCharsets.UTF_8), Saved.class);
      if (saved == null || saved.token() == null || saved.expiresAt() == null || !baseUrl.equals(saved.studioUrl())) {
        return null;
      }
      Instant.parse(saved.expiresAt());
      return saved;
    } catch (IOException | JsonParseException | DateTimeParseException e) {
      log.warn("The Studio link file could not be read; link Studio again");
      return null;
    }
  }

  private void save(Saved saved) {
    try {
      Files.createDirectories(accountFile.getParent());
      Files.writeString(accountFile, GSON.toJson(saved), StandardCharsets.UTF_8);
      try {
        Files.setPosixFilePermissions(accountFile, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        log.debug("This file system has no POSIX permissions; the link file keeps its folder's access rules");
      }
    } catch (IOException e) {
      log.warn("Could not keep the Studio link; it lasts until the game closes", e);
    }
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

  /** Reads at most {@code remaining} bytes, so one part never sends more than it announced. */
  private static final class BoundedInput extends FilterInputStream {

    private long remaining;

    private BoundedInput(InputStream in, long remaining) {
      super(in);
      this.remaining = remaining;
    }

    @Override
    public int read() throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int value = super.read();
      if (value >= 0) {
        remaining--;
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (remaining <= 0) {
        return -1;
      }
      int count = super.read(buffer, offset, (int) Math.min(length, remaining));
      if (count > 0) {
        remaining -= count;
      }
      return count;
    }
  }
}
