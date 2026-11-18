package lv.ordersmp.clips.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import lv.ordersmp.clips.ClipLibrary;
import org.slf4j.Logger;

/**
 * Gives a saved clip its sound. The game sound of the clip's time is mixed from the {@link SoundLog}
 * and kept beside the clip as its own lossless track, so the editor can change its volume later;
 * a copy is added to the MP4 itself, so the clip plays with sound anywhere. The video is copied,
 * never re-encoded. Runs on the saving thread.
 */
public final class ClipAudio {

  /** The name of the game sound track beside a clip. */
  public static final String GAME_TRACK = "game.flac";

  private final SoundLog log;
  private final SoundMixer mixer;
  private final Logger logger;

  public ClipAudio(SoundLog log, SoundMixer mixer, Logger logger) {
    this.log = log;
    this.mixer = mixer;
    this.logger = logger;
  }

  /**
   * Mixes the sound heard between {@code from} and {@code to} and adds it to {@code clip}. A clip
   * keeps its video when anything here fails; the failure is only logged.
   */
  public void addTo(Path clip, long from, long to, String ffmpeg) {
    if (to <= from) {
      return;
    }
    Path folder = ClipLibrary.audioFolder(clip);
    Path wav = folder.resolve(".game.wav");
    try {
      Files.createDirectories(folder);
      SoundLog.Snapshot heard = log.between(from, to);
      SoundMixer.Stereo mix = mixer.mix(heard, from, to);
      logger.debug("Mixed {} sounds and {} listener points for {}", heard.sounds().size(), heard.ears().size(), clip.getFileName());
      SoundMixer.writeWav(mix, wav);
      Path track = ClipLibrary.render(ffmpeg, folder.resolve(GAME_TRACK), List.of("-i", wav.toString(), "-c:a", "flac"));
      if (track == null) {
        logger.warn("The game sound of {} could not be written", clip.getFileName());
        return;
      }
      muxInto(clip, track, ffmpeg);
    } catch (IOException e) {
      logger.warn("The game sound of {} could not be written", clip.getFileName(), e);
    } finally {
      try {
        Files.deleteIfExists(wav);
      } catch (IOException ignored) {
        wav.toFile().deleteOnExit();
      }
    }
  }

  /** Puts {@code track} into the MP4 as AAC beside the copied video, replacing the file in one move. */
  private void muxInto(Path clip, Path track, String ffmpeg) throws IOException {
    Path withSound = clip.resolveSibling(".sound-" + clip.getFileName());
    Files.deleteIfExists(withSound);
    Path made = ClipLibrary.render(ffmpeg, withSound, List.of(
        "-i", clip.toString(), "-i", track.toString(), "-map", "0:v", "-map", "1:a",
        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-f", "mp4"));
    if (made == null) {
      logger.warn("The sound could not be added to {}", clip.getFileName());
      return;
    }
    Files.move(made, clip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
