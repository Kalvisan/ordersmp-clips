package lv.ordersmp.clips.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import lv.ordersmp.clips.ClipLibrary;
import lv.ordersmp.clips.ClipsConfig;
import org.slf4j.Logger;

/**
 * Gives a saved clip its sound. The game sound of the clip's time is mixed from the {@link SoundLog},
 * each voice from the {@link VoiceLog} gets its own track, and all of them are kept beside the clip
 * as lossless files listed in {@link AudioTracks}, so the editor can change each volume later. A mix
 * at the default volumes goes into the MP4 itself, so the clip plays with sound anywhere. The video
 * is copied, never re-encoded. Runs on the saving thread.
 */
public final class ClipAudio {

  private static final double NANOS = 1_000_000_000.0;
  private static final long FRAME_NANOS = VoiceLog.FRAME * 1_000_000_000L / VoiceLog.RATE;

  private final SoundLog sounds;
  private final VoiceLog voices;
  private final SoundMixer mixer;
  private final Supplier<ClipsConfig> settings;
  private final Logger logger;

  public ClipAudio(SoundLog sounds, VoiceLog voices, SoundMixer mixer, Supplier<ClipsConfig> settings, Logger logger) {
    this.sounds = sounds;
    this.voices = voices;
    this.mixer = mixer;
    this.settings = settings;
    this.logger = logger;
  }

  /**
   * Adds the sound heard between {@code from} and {@code to} to {@code clip}. A clip keeps its video
   * when anything here fails; the failure is only logged.
   */
  public void addTo(Path clip, long from, long to, String ffmpeg) {
    if (to <= from) {
      return;
    }
    Path folder = ClipLibrary.audioFolder(clip);
    Path wav = folder.resolve(".track.wav");
    try {
      Files.createDirectories(folder);
      List<AudioTracks.Track> tracks = new ArrayList<>();
      SoundLog.Snapshot heard = sounds.between(from, to);
      logger.debug("Mixing {} sounds and {} listener points for {}", heard.sounds().size(), heard.ears().size(), clip.getFileName());
      SoundMixer.writeWav(mixer.mix(heard, from, to), wav);
      if (flac(ffmpeg, wav, folder.resolve("game.flac"))) {
        tracks.add(new AudioTracks.Track("game.flac", AudioTracks.Kind.GAME, "Game Sound"));
      }
      int frames = (int) Math.round((to - from) * VoiceLog.RATE / NANOS);
      int number = 0;
      for (VoiceLog.Voice voice : voices.between(from, to)) {
        boolean mine = VoiceLog.MINE.equals(voice.key());
        String file = mine ? "my-voice.flac" : String.format(Locale.ROOT, "voice-%02d.flac", ++number);
        SoundMixer.writeWav(wav, place(voice, from, frames));
        if (flac(ffmpeg, wav, folder.resolve(file))) {
          tracks.add(new AudioTracks.Track(file, mine ? AudioTracks.Kind.MINE : AudioTracks.Kind.VOICE, voice.name()));
        }
      }
      if (tracks.isEmpty()) {
        return;
      }
      AudioTracks.save(clip, tracks);
      muxInto(clip, folder, tracks, ffmpeg);
    } catch (IOException e) {
      logger.warn("The sound of {} could not be written", clip.getFileName(), e);
    } finally {
      try {
        Files.deleteIfExists(wav);
      } catch (IOException ignored) {
        wav.toFile().deleteOnExit();
      }
    }
  }

  /** One voice laid out on the clip's timeline; each frame ends at the moment it was heard. */
  private static float[] place(VoiceLog.Voice voice, long from, int frames) {
    float[] samples = new float[frames];
    for (VoiceLog.Frame frame : voice.frames()) {
      int start = (int) Math.round((frame.time() - FRAME_NANOS - from) * VoiceLog.RATE / NANOS);
      short[] pcm = frame.samples();
      for (int i = 0; i < pcm.length; i++) {
        int at = start + i;
        if (at >= 0 && at < frames) {
          samples[at] = pcm[i] / 32768f;
        }
      }
    }
    return samples;
  }

  private static boolean flac(String ffmpeg, Path wav, Path target) throws IOException {
    Files.deleteIfExists(target);
    return ClipLibrary.render(ffmpeg, target, List.of("-i", wav.toString(), "-c:a", "flac")) != null;
  }

  /** Mixes the tracks at their default volumes into the MP4 as AAC beside the copied video. */
  private void muxInto(Path clip, Path folder, List<AudioTracks.Track> tracks, String ffmpeg) throws IOException {
    ClipsConfig config = settings.get();
    List<String> arguments = new ArrayList<>(List.of("-i", clip.toString()));
    StringBuilder graph = new StringBuilder();
    for (int i = 0; i < tracks.size(); i++) {
      AudioTracks.Track track = tracks.get(i);
      arguments.addAll(List.of("-i", folder.resolve(track.file()).toString()));
      graph.append(String.format(Locale.ROOT, "[%d:a]volume=%.2f[t%d];", i + 1, config.defaultVolume(track.kind()) / 100.0, i));
    }
    for (int i = 0; i < tracks.size(); i++) {
      graph.append("[t").append(i).append(']');
    }
    graph.append("amix=inputs=").append(tracks.size()).append(":normalize=0:duration=first[mix]");
    Path withSound = clip.resolveSibling(".sound-" + clip.getFileName());
    Files.deleteIfExists(withSound);
    arguments.addAll(List.of("-filter_complex", graph.toString(), "-map", "0:v", "-map", "[mix]",
        "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-f", "mp4"));
    Path made = ClipLibrary.render(ffmpeg, withSound, arguments);
    if (made == null) {
      logger.warn("The sound could not be added to {}", clip.getFileName());
      return;
    }
    Files.move(made, clip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
