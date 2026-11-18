package lv.ordersmp.clips.edit;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import lv.ordersmp.clips.ClipLibrary;

/**
 * The basic edit of one clip: where it starts and ends, where it is cut, and which parts are left
 * out. The video file is never changed; the edit is kept beside it and applied when the clip is
 * exported, by copying the kept parts without re-encoding.
 *
 * <p>Clips have a keyframe every second from their start, and a copied video can only begin on a
 * keyframe. So every point where video starts or resumes (the trim start and the end of a removed
 * part) snaps back to a whole second, and the editor shows that snapped point. Ends need no snap.
 */
public final class ClipEdit {

  /**
   * One stretch of the clip in seconds from its start.
   *
   * @param from where it begins
   * @param to where it ends
   */
  public record Part(double from, double to) {

    /** Seconds in this part. */
    public double length() {
      return to - from;
    }

    /** Whether {@code time} lies inside this part. */
    public boolean contains(double time) {
      return time >= from && time < to;
    }
  }

  private record Stored(double start, double end, List<Double> cuts, List<Part> removed) {}

  private static final Gson GSON = new Gson();
  private static final double MIN_LENGTH = 1;
  private static final double CUT_GAP = 0.25;

  private final double length;
  private double start;
  private double end;
  private final TreeSet<Double> cuts = new TreeSet<>();
  private final List<Part> removed = new ArrayList<>();

  private ClipEdit(double length) {
    this.length = length;
    this.end = length;
  }

  /** An edit that keeps the whole clip. */
  public static ClipEdit whole(double length) {
    return new ClipEdit(length);
  }

  /** The saved edit of {@code clip}, or the whole clip when there is none or it cannot be read. */
  public static ClipEdit load(Path clip, double length) {
    ClipEdit edit = whole(length);
    Path file = ClipLibrary.editFile(clip);
    if (!Files.isRegularFile(file)) {
      return edit;
    }
    try {
      Stored stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
      if (stored == null) {
        return edit;
      }
      edit.setEnd(stored.end());
      edit.setStart(stored.start());
      if (stored.cuts() != null) {
        stored.cuts().forEach(edit::cut);
      }
      if (stored.removed() != null) {
        stored.removed().stream().filter(part -> part.from() < part.to()).forEach(edit.removed::add);
      }
      return edit;
    } catch (IOException | JsonParseException e) {
      return edit;
    }
  }

  /** Keeps this edit beside {@code clip}; an edit that changes nothing removes the file instead. */
  public void save(Path clip) throws IOException {
    Path file = ClipLibrary.editFile(clip);
    if (keepsEverything()) {
      Files.deleteIfExists(file);
      return;
    }
    Files.createDirectories(file.getParent());
    Files.writeString(file, GSON.toJson(new Stored(start, end, List.copyOf(cuts), List.copyOf(removed))), StandardCharsets.UTF_8);
  }

  /** A separate copy, so a screen can tell whether anything changed. */
  public ClipEdit copy() {
    ClipEdit copy = whole(length);
    copy.start = start;
    copy.end = end;
    copy.cuts.addAll(cuts);
    copy.removed.addAll(removed);
    return copy;
  }

  /** Whether this edit equals {@code other}. */
  public boolean sameAs(ClipEdit other) {
    return start == other.start && end == other.end && cuts.equals(other.cuts) && removed.equals(other.removed);
  }

  /** Whether the edit keeps the clip exactly as recorded. */
  public boolean keepsEverything() {
    return start == 0 && end == length && cuts.isEmpty() && removed.isEmpty();
  }

  public double length() {
    return length;
  }

  public double start() {
    return start;
  }

  public double end() {
    return end;
  }

  /** Cut lines, oldest first in time. */
  public List<Double> cuts() {
    return List.copyOf(cuts);
  }

  /** Parts left out of the clip. */
  public List<Part> removed() {
    return List.copyOf(removed);
  }

  /** Moves the start, snapped back to a whole second and kept at least a second before the end. */
  public void setStart(double time) {
    start = Math.max(0, Math.min(Math.floor(time), Math.floor(end - MIN_LENGTH)));
    dropOutside();
  }

  /** Moves the end, kept at least a second after the start. */
  public void setEnd(double time) {
    end = Math.min(length, Math.max(time, start + MIN_LENGTH));
    dropOutside();
  }

  /** Adds a cut line at {@code time}; returns false when it is outside the clip or too close to another. */
  public boolean cut(double time) {
    if (time <= start + CUT_GAP || time >= end - CUT_GAP) {
      return false;
    }
    Double below = cuts.floor(time);
    Double above = cuts.ceiling(time);
    if ((below != null && time - below < CUT_GAP) || (above != null && above - time < CUT_GAP)) {
      return false;
    }
    cuts.add(time);
    return true;
  }

  /** The stretch between the nearest cut lines (or the trim) around {@code time}. */
  public Part partAt(double time) {
    Double below = cuts.floor(time);
    Double above = cuts.higher(time);
    return new Part(below == null ? start : below, above == null ? end : above);
  }

  /** Whether {@code time} lies in a part that is left out. */
  public boolean isRemoved(double time) {
    return removedAt(time) != null;
  }

  /** Whether there is a cut that makes the part around {@code time} smaller than the whole clip. */
  public boolean canRemoveAt(double time) {
    Part part = partAt(time);
    return isRemoved(time) || part.from() > start || part.to() < end;
  }

  /**
   * Leaves out the part around {@code time}, or brings it back when it already is left out. The
   * end of a removed part is where video resumes, so it snaps back to a whole second unless it is
   * the clip's end. Returns false when nothing changed.
   */
  public boolean toggleRemove(double time) {
    Part existing = removedAt(time);
    if (existing != null) {
      removed.remove(existing);
      return true;
    }
    if (!canRemoveAt(time)) {
      return false;
    }
    Part part = partAt(time);
    double to = part.to() >= end ? end : Math.floor(part.to());
    if (to <= part.from()) {
      return false;
    }
    removed.add(new Part(part.from(), to));
    removed.sort(Comparator.comparingDouble(Part::from));
    return true;
  }

  /** Back to the whole clip. */
  public void reset() {
    start = 0;
    end = length;
    cuts.clear();
    removed.clear();
  }

  /** The parts that stay in the clip, in order. */
  public List<Part> kept() {
    List<Part> kept = new ArrayList<>();
    double from = start;
    for (Part part : removed) {
      if (part.from() > from) {
        kept.add(new Part(from, Math.min(part.from(), end)));
      }
      from = Math.max(from, part.to());
    }
    if (end > from) {
      kept.add(new Part(from, end));
    }
    return kept;
  }

  /** Seconds the edited clip lasts. */
  public double keptLength() {
    return kept().stream().mapToDouble(Part::length).sum();
  }

  /** How far into the edited clip {@code time} is. */
  public double keptPosition(double time) {
    double position = 0;
    for (Part part : kept()) {
      if (time >= part.to()) {
        position += part.length();
        continue;
      }
      if (time > part.from()) {
        position += time - part.from();
      }
      break;
    }
    return position;
  }

  /** Where playback continues from {@code time}: past a removed part, or {@code time} itself. */
  public double skipRemoved(double time) {
    Part part = removedAt(time);
    return part == null ? time : part.to();
  }

  private Part removedAt(double time) {
    for (Part part : removed) {
      if (part.contains(time)) {
        return part;
      }
    }
    return null;
  }

  private void dropOutside() {
    cuts.removeIf(cut -> cut <= start || cut >= end);
    removed.removeIf(part -> part.to() <= start || part.from() >= end);
  }
}
