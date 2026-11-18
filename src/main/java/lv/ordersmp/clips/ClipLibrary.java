package lv.ordersmp.clips;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

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

  private ClipLibrary() {}

  /** Every clip in {@code folder}, newest first. A missing folder means no clips yet. */
  public static List<Clip> list(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    List<Clip> clips = new ArrayList<>();
    try (Stream<Path> files = Files.list(folder)) {
      for (Path file : files.filter(ClipLibrary::isClip).toList()) {
        clips.add(new Clip(file, Files.getLastModifiedTime(file).toInstant(), Files.size(file), seconds(file)));
      }
    }
    clips.sort(Comparator.comparing(Clip::saved).reversed());
    return clips;
  }

  /** Deletes one clip and everything kept beside it: thumbnail, filmstrip and edits. */
  public static void delete(Clip clip) throws IOException {
    Files.deleteIfExists(clip.file());
    Files.deleteIfExists(thumbnailFile(clip.file()));
    Files.deleteIfExists(filmstripFile(clip.file()));
    Files.deleteIfExists(editFile(clip.file()));
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
  private static double seconds(Path file) {
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
