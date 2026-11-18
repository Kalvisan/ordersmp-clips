package lv.ordersmp.clips.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import lv.ordersmp.clips.ClipLibrary;

/**
 * Writes an edited clip as a new MP4 next to the original. The kept parts are copied, not
 * re-encoded, so exporting takes a moment and loses no quality: each part is copied on its own
 * and the parts are then joined with ffmpeg's concat demuxer.
 */
public final class ClipExport {

  private ClipExport() {}

  /** Exports {@code edit} of {@code clip}; returns the new file, or null when ffmpeg failed. */
  public static Path export(Path clip, ClipEdit edit, String ffmpeg) {
    List<ClipEdit.Part> kept = edit.kept();
    if (kept.isEmpty()) {
      return null;
    }
    Path target = freeName(clip);
    if (kept.size() == 1) {
      return ClipLibrary.render(ffmpeg, target, copyPart(clip, kept.getFirst(), "+faststart"));
    }
    Path work = null;
    try {
      work = Files.createTempDirectory(clip.getParent(), ".export-");
      List<String> list = new ArrayList<>();
      for (int i = 0; i < kept.size(); i++) {
        Path part = ClipLibrary.render(ffmpeg, work.resolve("part-" + i + ".mp4"), copyPart(clip, kept.get(i), null));
        if (part == null) {
          return null;
        }
        list.add("file '" + part.getFileName() + "'");
      }
      Path listFile = work.resolve("parts.txt");
      Files.write(listFile, list, StandardCharsets.UTF_8);
      return ClipLibrary.render(ffmpeg, target, List.of(
          "-f", "concat", "-safe", "0", "-i", listFile.toString(), "-c", "copy", "-movflags", "+faststart"));
    } catch (IOException e) {
      return null;
    } finally {
      deleteFolder(work);
    }
  }

  private static List<String> copyPart(Path clip, ClipEdit.Part part, String movflags) {
    List<String> arguments = new ArrayList<>(List.of(
        "-ss", seconds(part.from()), "-i", clip.toString(), "-t", seconds(part.length()),
        "-c", "copy", "-avoid_negative_ts", "make_zero"));
    if (movflags != null) {
      arguments.addAll(List.of("-movflags", movflags));
    }
    return arguments;
  }

  private static String seconds(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static Path freeName(Path clip) {
    String stem = ClipLibrary.stem(clip);
    Path target = clip.resolveSibling(stem + "-edit.mp4");
    for (int n = 2; Files.exists(target); n++) {
      target = clip.resolveSibling(stem + "-edit-" + n + ".mp4");
    }
    return target;
  }

  private static void deleteFolder(Path folder) {
    if (folder == null) {
      return;
    }
    try (Stream<Path> files = Files.walk(folder)) {
      for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(file);
      }
    } catch (IOException ignored) {
      folder.toFile().deleteOnExit();
    }
  }
}
