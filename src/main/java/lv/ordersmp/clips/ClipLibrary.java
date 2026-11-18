package lv.ordersmp.clips;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.util.Util;

/**
 * The saved clips on disk. Clips are plain MP4 files in the clips folder, so the folder itself is
 * the library: nothing else is stored, and files the player moves or deletes by hand simply
 * disappear from the list.
 */
public final class ClipLibrary {

  /**
   * One saved clip.
   *
   * @param file the MP4 file
   * @param saved when it was written
   * @param bytes file size
   * @param seconds video length, or -1 when the file could not be read
   */
  public record Clip(Path file, Instant saved, long bytes, double seconds) {}

  /** Thumbnail size in pixels: twice the 56x32 it is drawn at, so it stays sharp at GUI scale 2. */
  public static final int THUMBNAIL_WIDTH = 112;
  public static final int THUMBNAIL_HEIGHT = 64;

  private static final int BOX_HEADER = 8;
  /** Frames in a filmstrip and the size of each, in pixels. */
  public static final int FILMSTRIP_FRAMES = 10;
  public static final int FILMSTRIP_FRAME_WIDTH = 64;
  public static final int FILMSTRIP_FRAME_HEIGHT = 36;

  private static final int FFMPEG_TIMEOUT_SECONDS = 60;
  private static final String THUMBNAIL_FOLDER = ".thumbnails";
  private static final String EDIT_FOLDER = ".edits";
  private static final String EVENT_FOLDER = ".events";
  private static final String STUDIO_FOLDER = ".studio";
  private static final String AUDIO_FOLDER = ".audio";
  private static final String KEPT_FOLDER = ".kept";
  private static final long GIGABYTE = 1024L * 1024 * 1024;
  private static final Duration NEW_CLIP = Duration.ofMinutes(5);
  private static final Set<Path> DELETED = ConcurrentHashMap.newKeySet();

  private ClipLibrary() {}

  /** Every clip in {@code folder}, newest first. A missing folder means no clips yet. */
  public static List<Clip> list(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    List<Clip> clips = new ArrayList<>();
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : files.filter(ClipLibrary::isClip).filter(file -> !stillDeleting(file)).toList()) {
        clips.add(new Clip(file, Files.getLastModifiedTime(file).toInstant(), Files.size(file), length(file)));
      }
    }
    clips.sort(Comparator.comparing(Clip::saved).reversed());
    return clips;
  }

  /**
   * Whether {@code file} was deleted but still shows in the folder. On Windows a file that a video
   * player or the preview still has open stays listed until it is closed; it must not come back
   * into the list meanwhile.
   */
  private static boolean stillDeleting(Path file) {
    if (!DELETED.contains(file)) {
      return false;
    }
    if (Files.exists(file)) {
      return true;
    }
    DELETED.remove(file);
    return false;
  }

  /** Deletes one clip and everything kept beside it: thumbnail, filmstrip and edits. */
  public static void delete(Clip clip) throws IOException {
    Files.deleteIfExists(clip.file());
    DELETED.add(clip.file());
    Files.deleteIfExists(thumbnailFile(clip.file()));
    Files.deleteIfExists(filmstripFile(clip.file()));
    Files.deleteIfExists(editFile(clip.file()));
    Files.deleteIfExists(eventsFile(clip.file()));
    Files.deleteIfExists(studioFile(clip.file()));
    Files.deleteIfExists(keptFile(clip.file()));
    deleteFolder(audioFolder(clip.file()));
  }

  /** Whether the player marked the clip Kept, so it is never deleted by the storage limits. */
  public static boolean isKept(Path clip) {
    return Files.exists(keptFile(clip));
  }

  /** Marks the clip Kept or takes the mark away. */
  public static void setKept(Path clip, boolean kept) throws IOException {
    Path marker = keptFile(clip);
    if (!kept) {
      Files.deleteIfExists(marker);
      return;
    }
    Files.createDirectories(marker.getParent());
    if (!Files.exists(marker)) {
      Files.createFile(marker);
    }
  }

  /**
   * Whole days until a clip not marked Kept is deleted by the {@code days} limit, from 0 on its last
   * day; -1 when it is kept or there is no limit.
   */
  public static int daysLeft(Clip clip, int days, Instant now) {
    if (days <= 0 || isKept(clip.file())) {
      return -1;
    }
    long age = Duration.between(clip.saved(), now).toDays();
    return (int) Math.max(0, days - age - 1);
  }

  /**
   * Deletes clips not marked Kept that are older than {@code days}, then the oldest of them while
   * the folder is bigger than {@code maxSizeGb}. Clips from the last few minutes and those for which
   * {@code busy} is true (being uploaded) are never touched. Returns how many were deleted. Reads
   * and deletes files, so call it off the render thread.
   */
  public static int tidy(Path folder, int maxSizeGb, int days, Predicate<Path> busy) throws IOException {
    if (maxSizeGb <= 0 && days <= 0) {
      return 0;
    }
    List<Clip> clips = new ArrayList<>(list(folder));
    Instant now = Instant.now();
    long total = clips.stream().mapToLong(Clip::bytes).sum();
    int deleted = 0;
    clips.sort(Comparator.comparing(Clip::saved));
    for (Clip clip : clips) {
      if (isKept(clip.file()) || busy.test(clip.file()) || clip.saved().isAfter(now.minus(NEW_CLIP))) {
        continue;
      }
      boolean tooOld = days > 0 && clip.saved().isBefore(now.minus(Duration.ofDays(days)));
      boolean tooBig = maxSizeGb > 0 && total > maxSizeGb * GIGABYTE;
      if (!tooOld && !tooBig) {
        continue;
      }
      delete(clip);
      total -= clip.bytes();
      deleted++;
    }
    return deleted;
  }

  /**
   * Copies an MP4 from elsewhere into the clips folder under a free name, so it can be watched,
   * edited and uploaded like any clip. Its date is the moment it was added, so the storage limits
   * count from then. Returns the new file.
   */
  public static Path importFile(Path source, Path folder) throws IOException {
    Files.createDirectories(folder);
    String name = Util.sanitizeName(stem(source), character -> Character.isLetterOrDigit(character) || character == '-' || character == '_');
    Path target = folder.resolve(name + ".mp4");
    for (int n = 2; Files.exists(target); n++) {
      target = folder.resolve(name + "-" + n + ".mp4");
    }
    Files.copy(source, target);
    Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
    return target;
  }

  private static Path keptFile(Path clip) {
    return beside(clip, KEPT_FOLDER, ".kept");
  }

  /** Where the clip's sound tracks are kept: game sound and one per voice. */
  public static Path audioFolder(Path clip) {
    return clip.resolveSibling(AUDIO_FOLDER).resolve(stem(clip));
  }

  private static void deleteFolder(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return;
    }
    try (Stream<Path> files = Files.walk(folder)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(file);
      }
    }
  }

  /** Where the address of the clip's Studio copy is kept once it was uploaded. */
  public static Path studioFile(Path clip) {
    return beside(clip, STUDIO_FOLDER, ".txt");
  }

  /** Where the moments inside an automatic clip are kept, for the editor's timeline markers. */
  public static Path eventsFile(Path clip) {
    return beside(clip, EVENT_FOLDER, ".json");
  }

  /** Where the thumbnail of {@code clip} is kept: a hidden folder beside the clips. */
  public static Path thumbnailFile(Path clip) {
    return beside(clip, THUMBNAIL_FOLDER, ".png");
  }

  /** Where the timeline's row of small frames from {@code clip} is kept. */
  public static Path filmstripFile(Path clip) {
    return beside(clip, THUMBNAIL_FOLDER, ".strip.png");
  }

  /** Where the editor keeps the trim and cuts of {@code clip}; the video itself is never changed. */
  public static Path editFile(Path clip) {
    return beside(clip, EDIT_FOLDER, ".json");
  }

  /** The clip's name without {@code .mp4}. */
  public static String stem(Path clip) {
    String name = clip.getFileName().toString();
    return name.endsWith(".mp4") ? name.substring(0, name.length() - ".mp4".length()) : name;
  }

  /**
   * Makes the thumbnail of {@code clip} with ffmpeg if it does not exist yet, from a frame three
   * seconds before the end, where the saved moment usually is. Returns the file, or null when it
   * could not be made. Blocks while ffmpeg runs, so call it off the render thread.
   */
  public static Path thumbnail(Path clip, String ffmpeg) {
    String size = THUMBNAIL_WIDTH + ":" + THUMBNAIL_HEIGHT;
    return render(ffmpeg, thumbnailFile(clip), List.of("-sseof", "-3", "-i", clip.toString(), "-frames:v", "1",
        "-vf", "scale=" + size + ":force_original_aspect_ratio=decrease,pad=" + size + ":(ow-iw)/2:(oh-ih)/2"));
  }

  /**
   * Makes the timeline's filmstrip: {@link #FILMSTRIP_FRAMES} small frames spread over the clip,
   * side by side in one image, so the timeline draws one texture instead of decoding video.
   */
  public static Path filmstrip(Path clip, int seconds, String ffmpeg) {
    if (seconds <= 0) {
      return null;
    }
    String size = FILMSTRIP_FRAME_WIDTH + ":" + FILMSTRIP_FRAME_HEIGHT;
    String rate = FILMSTRIP_FRAMES + "/" + seconds;
    return render(ffmpeg, filmstripFile(clip), List.of("-i", clip.toString(), "-frames:v", "1",
        "-vf", "fps=" + rate + ",scale=" + size + ":force_original_aspect_ratio=decrease,pad=" + size
            + ":(ow-iw)/2:(oh-ih)/2,tile=" + FILMSTRIP_FRAMES + "x1"));
  }

  /**
   * Runs ffmpeg with {@code arguments} to write {@code target}, unless it already exists. Output goes
   * to a temporary file first and is moved into place only when ffmpeg succeeded, so a crash never
   * leaves half a file that would be shown later. Returns the file, or null on failure.
   */
  public static Path render(String ffmpeg, Path target, List<String> arguments) {
    if (Files.isRegularFile(target)) {
      return target;
    }
    String name = target.getFileName().toString();
    Path partial = target.resolveSibling(".part-" + name);
    List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y"));
    command.addAll(arguments);
    command.add(partial.toString());
    try {
      Files.createDirectories(target.getParent());
      Process process = new ProcessBuilder(command)
          .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
      if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return null;
      }
      if (process.exitValue() != 0 || !Files.isRegularFile(partial)) {
        return null;
      }
      Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      return target;
    } catch (IOException e) {
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      try {
        Files.deleteIfExists(partial);
      } catch (IOException ignored) {
        partial.toFile().deleteOnExit();
      }
    }
  }

  private static Path beside(Path clip, String folder, String suffix) {
    return clip.resolveSibling(folder).resolve(stem(clip) + suffix);
  }

  private static boolean isClip(Path file) {
    String name = file.getFileName().toString();
    return !name.startsWith(".") && name.endsWith(".mp4") && Files.isRegularFile(file);
  }

  /**
   * Reads the length from the MP4 movie header. Clips are written with the header at the front, so
   * this reads a few dozen bytes instead of the whole file; other MP4s are skipped box by box.
   */
  public static double length(Path file) {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
      long moov = findBox(channel, 0, channel.size(), "moov");
      if (moov < 0) {
        return -1;
      }
      long mvhd = findBox(channel, moov + BOX_HEADER, moov + boxSize(channel, moov), "mvhd");
      if (mvhd < 0) {
        return -1;
      }
      ByteBuffer header = read(channel, mvhd + BOX_HEADER, 32);
      boolean wide = header.get(0) == 1;
      long timescale = wide ? header.getInt(20) & 0xFFFFFFFFL : header.getInt(12) & 0xFFFFFFFFL;
      long duration = wide ? header.getLong(24) : header.getInt(16) & 0xFFFFFFFFL;
      if (timescale == 0) {
        return -1;
      }
      return (double) duration / timescale;
    } catch (IOException | RuntimeException e) {
      return -1;
    }
  }

  private static long findBox(FileChannel channel, long start, long end, String type) throws IOException {
    long position = start;
    while (position + BOX_HEADER <= end) {
      ByteBuffer header = read(channel, position, BOX_HEADER);
      if (type.equals(new String(header.array(), 4, 4, StandardCharsets.US_ASCII))) {
        return position;
      }
      long size = boxSize(channel, position);
      if (size < BOX_HEADER) {
        return -1;
      }
      position += size;
    }
    return -1;
  }

  private static long boxSize(FileChannel channel, long position) throws IOException {
    long size = read(channel, position, 4).getInt(0) & 0xFFFFFFFFL;
    if (size == 1) {
      return read(channel, position + BOX_HEADER, 8).getLong(0);
    }
    if (size == 0) {
      return channel.size() - position;
    }
    return size;
  }

  private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer, position + buffer.position()) < 0) {
        throw new IOException("unexpected end of file");
      }
    }
    return buffer;
  }
}
