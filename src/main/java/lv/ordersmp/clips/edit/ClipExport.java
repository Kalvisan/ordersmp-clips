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
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.ClipsConfig;
import lv.ordersmp.clips.audio.AudioTracks;

/**
 * Writes an edited clip as a new MP4 next to the original. The kept parts are copied, not
 * re-encoded, so exporting takes a moment and loses no quality: each part is copied on its own
 * and the parts are then joined with ffmpeg's concat demuxer. Only the sound is made again, and
 * only when a track's volume was changed.
 */
public final class ClipExport {

  private ClipExport() {}

  /** Exports {@code edit} of {@code clip} next to it; returns the new file, or null when ffmpeg failed. */
  public static Path export(Path clip, ClipEdit edit, String ffmpeg) {
    return export(clip, edit, ffmpeg, freeName(clip));
  }

  /**
   * Exports {@code edit} of {@code clip} to {@code target}; returns it, or null when ffmpeg failed.
   * When the edit changed a track's volume, the sound is mixed again from the clip's tracks at the
   * new volumes and cut to the same parts; the video is still copied, never re-encoded.
   */
  public static Path export(Path clip, ClipEdit edit, String ffmpeg, Path target) {
    List<ClipEdit.Part> kept = edit.kept();
    if (kept.isEmpty()) {
      return null;
    }
    List<AudioTracks.Track> tracks = AudioTracks.load(clip);
    if (tracks.isEmpty() || edit.keepsSound()) {
      return copyKept(clip, kept, ffmpeg, target, false);
    }
    Path work = null;
    try {
      work = Files.createTempDirectory(clip.getParent(), ".export-");
      Path video = copyKept(clip, kept, ffmpeg, work.resolve("video.mp4"), true);
      if (video == null) {
        return null;
      }
      return ClipLibrary.render(ffmpeg, target, remix(clip, video, tracks, edit, kept));
    } catch (IOException e) {
      return null;
    } finally {
      deleteFolder(work);
    }
  }

  /** Copies the kept parts of {@code clip} into {@code target}, with or without its sound. */
  private static Path copyKept(Path clip, List<ClipEdit.Part> kept, String ffmpeg, Path target, boolean videoOnly) {
    if (kept.size() == 1) {
      return ClipLibrary.render(ffmpeg, target, copyPart(clip, kept.getFirst(), "+faststart", videoOnly));
    }
    Path work = null;
    try {
      work = Files.createTempDirectory(clip.getParent(), ".export-");
      List<String> list = new ArrayList<>();
      for (int i = 0; i < kept.size(); i++) {
        Path part = ClipLibrary.render(ffmpeg, work.resolve("part-" + i + ".mp4"), copyPart(clip, kept.get(i), null, videoOnly));
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

  /**
   * The ffmpeg arguments that put the copied {@code video} together with the tracks mixed at the
   * edit's volumes (muted tracks at 0) and cut to the kept parts.
   */
  private static List<String> remix(Path clip, Path video, List<AudioTracks.Track> tracks, ClipEdit edit, List<ClipEdit.Part> kept) {
    ClipsConfig settings = ClipsClient.settings();
    Path folder = ClipLibrary.audioFolder(clip);
    List<String> arguments = new ArrayList<>(List.of("-i", video.toString()));
    StringBuilder graph = new StringBuilder();
    for (int i = 0; i < tracks.size(); i++) {
      AudioTracks.Track track = tracks.get(i);
      arguments.addAll(List.of("-i", folder.resolve(track.file()).toString()));
      int percent = edit.isMuted(track.file()) ? 0 : edit.volume(track.file(), settings.defaultVolume(track.kind()));
      graph.append(String.format(Locale.ROOT, "[%d:a]volume=%.2f[t%d];", i + 1, percent / 100.0, i));
    }
    for (int i = 0; i < tracks.size(); i++) {
      graph.append("[t").append(i).append(']');
    }
    graph.append("amix=inputs=").append(tracks.size()).append(":normalize=0:duration=first,asplit=").append(kept.size());
    for (int i = 0; i < kept.size(); i++) {
      graph.append("[s").append(i).append(']');
    }
    for (int i = 0; i < kept.size(); i++) {
      ClipEdit.Part part = kept.get(i);
      graph.append(String.format(Locale.ROOT, ";[s%d]atrim=start=%s:end=%s,asetpts=PTS-STARTPTS[p%d]", i,
          seconds(part.from()), seconds(part.to()), i));
    }
    graph.append(';');
    for (int i = 0; i < kept.size(); i++) {
      graph.append("[p").append(i).append(']');
    }
    graph.append("concat=n=").append(kept.size()).append(":v=0:a=1[sound]");
    arguments.addAll(List.of("-filter_complex", graph.toString(), "-map", "0:v", "-map", "[sound]",
        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart"));
    return arguments;
  }

  private static List<String> copyPart(Path clip, ClipEdit.Part part, String movflags, boolean videoOnly) {
    List<String> arguments = new ArrayList<>(List.of(
        "-ss", seconds(part.from()), "-i", clip.toString(), "-t", seconds(part.length()),
        "-c", "copy", "-avoid_negative_ts", "make_zero"));
    if (videoOnly) {
      arguments.add("-an");
    }
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
