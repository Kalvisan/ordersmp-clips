package lv.ordersmp.clips;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import java.io.IOException;
import lv.ordersmp.clips.edit.ClipEdit;
import lv.ordersmp.clips.encode.ClipWriter;
import java.util.List;
import java.util.UUID;
import com.mojang.blaze3d.audio.ListenerTransform;
import lv.ordersmp.clips.audio.ClipAudio;
import lv.ordersmp.clips.audio.SoundLog;
import lv.ordersmp.clips.audio.SoundMixer;
import lv.ordersmp.clips.audio.VoiceLog;
import lv.ordersmp.clips.fight.ClipEvents;
import lv.ordersmp.clips.fight.FightTracker;
import lv.ordersmp.clips.mixin.GameRendererAccessor;
import lv.ordersmp.clips.mixin.SoundEngineAccessor;
import lv.ordersmp.clips.mixin.SoundManagerAccessor;
import lv.ordersmp.clips.studio.Studio;
import lv.ordersmp.clips.ui.ClipsScreen;
import lv.ordersmp.clips.ui.EditorScreen;
import lv.ordersmp.clips.ui.StudioLinkScreen;
import lv.ordersmp.clips.ui.RecordingHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: reads the settings, starts the recorder once the game is up, and wires the key
 * binds, the recording mark and the toasts. All recording work lives in {@link Recorder}.
 */
public final class ClipsClient implements ClientModInitializer {

  public static final String MOD_ID = "ordersmp-clips";
  private static final Logger LOG = LoggerFactory.getLogger("OrderSMP Clips");
  private static final int HANDLE_SECONDS = 10;
  private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);
  private static Recorder recorder;
  private static KeyMapping saveMapping;
  private static KeyMapping recordMapping;
  private static Studio studio;
  private static FightTracker fights;
  private static SoundLog soundLog;
  private static VoiceLog voiceLog;
  private static RecordingHud mark;
  private static ClipsConfig settings;
  private static Path settingsFile;

  /** The running recorder, or null before the game finished starting. */
  public static Recorder recorder() {
    return recorder;
  }

  /** The settings in use. */
  public static ClipsConfig settings() {
    return settings;
  }

  /** Changes the settings, keeps them in the config file and applies them at once. */
  public static void updateSettings(UnaryOperator<ClipsConfig> change) {
    settings = change.apply(settings);
    settings.save(settingsFile, LOG);
    if (fights != null) {
      fights.configure(settings.clipKills(), settings.clipDeaths(), settings.clipLongFights());
    }
    if (mark != null) {
      mark.setVisible(settings.showHud());
    }
    if (recorder != null) {
      recorder.reconfigure(settings);
    }
    if (soundLog != null) {
      soundLog.keep(soundWindow(settings));
    }
    if (voiceLog != null) {
      voiceLog.keep(settings.bufferSeconds());
    }
  }

  /** The log of game sounds that clips are mixed from, or null before the game finished starting. */
  public static SoundLog soundLog() {
    return soundLog;
  }

  /** The voices heard through Simple Voice Chat, or null before the mod finished loading. */
  public static VoiceLog voiceLog() {
    return voiceLog;
  }

  /** The fight tracker that saves kill and death clips, or null before the game finished starting. */
  public static FightTracker fights() {
    return fights;
  }

  /**
   * Draws the recording mark in its own small GUI pass, after the frame was captured. Hidden with
   * F1, outside a world and on the Clips screens, which show the same state themselves.
   */
  public static void drawMark(GameRenderer gameRenderer) {
    Minecraft client = Minecraft.getInstance();
    if (mark == null || client.level == null || client.gui.hud.isHidden() || isClipsScreen(client.gui.screen())) {
      return;
    }
    mark.draw(new GuiGraphicsExtractor(client, gameRenderer.gameRenderState().guiRenderState, 0, 0));
    ((GameRendererAccessor) gameRenderer).clips$guiRenderer().render();
  }

  private static boolean isClipsScreen(Screen screen) {
    return screen instanceof ClipsScreen || screen instanceof EditorScreen || screen instanceof StudioLinkScreen;
  }

  /** The link to OrderSMP Studio and its uploads. */
  public static Studio studio() {
    return studio;
  }

  /** The mod's logger, shared so every message is tagged the same way. */
  public static Logger log() {
    return LOG;
  }

  /** Opens the Clips screen on top of {@code parent}; does nothing before the game finished starting. */
  public static void openScreen(Screen parent) {
    if (recorder == null || saveMapping == null) {
      return;
    }
    Minecraft.getInstance().gui.setScreen(new ClipsScreen(parent, recorder, saveMapping.getTranslatedKeyMessage()));
  }

  @Override
  public void onInitializeClient() {
    Path gameDir = FabricLoader.getInstance().getGameDir();
    ClipsConfig config = ClipsConfig.load(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json"), LOG);
    Recorder created = new Recorder(config, gameDir.resolve("clips"), LOG);
    RecordingHud hud = new RecordingHud(created, config.showHud());
    mark = hud;
    studio = new Studio(config.studioUrl(), gameDir.resolve(MOD_ID), gameDir.resolve("clips"), LOG);

    KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "clips"));
    KeyMapping save = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key." + MOD_ID + ".save", InputConstants.Type.KEYBOARD, InputConstants.KEY_F8, category));
    KeyMapping toggle = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key." + MOD_ID + ".toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, category));
    KeyMapping record = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key." + MOD_ID + ".record", InputConstants.Type.KEYBOARD, InputConstants.KEY_F6, category));
    KeyMapping open = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key." + MOD_ID + ".open", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, category));
    saveMapping = save;
    recordMapping = record;
    KeyMapping stats = KeyMappingHelper.registerKeyMapping(
        new KeyMapping("key." + MOD_ID + ".stats", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));


    FightTracker tracker = new FightTracker(System::nanoTime, moment -> saveMoment(created, moment));
    tracker.configure(config.clipKills(), config.clipDeaths(), config.clipLongFights());
    settings = config;
    settingsFile = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");

    SoundLog sounds = new SoundLog();
    sounds.keep(soundWindow(config));
    VoiceLog voices = new VoiceLog();
    voices.keep(config.bufferSeconds());
    voiceLog = voices;
    created.setAudio(new ClipAudio(sounds, voices, new SoundMixer(file -> Minecraft.getInstance().getResourceManager().open(file)),
        ClipsClient::settings, LOG));

    ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
      recorder = created;
      fights = tracker;
      soundLog = sounds;
      created.probe(gameDir.resolve(MOD_ID)).thenAccept(result -> {
        if (result.ready()) {
          return;
        }
        boolean noFfmpeg = result.ffmpeg() == null;
        client.execute(() -> toast(client,
            Component.translatable(noFfmpeg ? "ordersmp-clips.toast.ffmpeg.title" : "ordersmp-clips.toast.encoder.title"),
            noFfmpeg ? Component.translatable("ordersmp-clips.toast.ffmpeg.body") : Component.literal(result.problem())));
      });
    });
    ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
      created.shutdown();
      studio.shutdown();
    });

    PressDetector saveKey = new PressDetector(save);
    PressDetector toggleKey = new PressDetector(toggle);
    PressDetector statsKey = new PressDetector(stats);
    PressDetector openKey = new PressDetector(open);
    PressDetector recordKey = new PressDetector(record);
    ClientTickEvents.END_CLIENT_TICK.register(client -> {
      tracker.tick();
      listen(client, sounds);
      nameSpeakers(client, voices);
      if (saveKey.pressed()) {
        created.saveClip(saved -> client.execute(() -> announce(client, saved)));
      }
      if (toggleKey.pressed()) {
        boolean paused = created.togglePause();
        toast(client, Component.translatable(paused ? "ordersmp-clips.toast.paused" : "ordersmp-clips.toast.resumed"), null);
      }
      if (statsKey.pressed()) {
        hud.toggleStats();
      }
      if (openKey.pressed() && client.gui.screen() == null) {
        openScreen(null);
      }
      if (recordKey.pressed()) {
        toggleRecording(client);
      }
      String stopped = created.recordingProblem();
      if (stopped != null) {
        created.stopRecording();
        toast(client, Component.translatable("ordersmp-clips.toast.recording.stopped"), Component.literal(stopped));
      }
    });
  }

  /**
   * Turns a key into single presses. Minecraft counts the operating system's key repeat as new
   * clicks, so holding a key would otherwise save many clips; only a press that starts while the key
   * was up counts, and a tap shorter than a tick is still caught through the click counter.
   */
  private static final class PressDetector {
    private final KeyMapping key;
    private boolean wasDown;

    private PressDetector(KeyMapping key) {
      this.key = key;
    }

    private boolean pressed() {
      boolean clicked = false;
      while (key.consumeClick()) {
        clicked = true;
      }
      boolean fresh = clicked && !wasDown;
      wasDown = key.isDown();
      return fresh;
    }
  }

  private static void announce(Minecraft client, ClipWriter.Saved saved) {
    if (saved.file() == null) {
      boolean empty = "nothing recorded yet".equals(saved.problem());
      toast(client, Component.translatable("ordersmp-clips.toast.failed.title"),
          empty ? Component.translatable("ordersmp-clips.toast.empty") : Component.literal(saved.problem()));
      return;
    }
    LOG.info("Clip saved: {}", saved.file().getFileName());
    toast(client, Component.translatable("ordersmp-clips.toast.saved.title"),
        Component.translatable("ordersmp-clips.toast.saved.body", saved.seconds(), saved.file().getFileName().toString()));
  }

  /** Saves a fight the tracker found and names the clip after it, such as {@code _kill-Raven_77}. */
  private static void saveMoment(Recorder recorder, FightTracker.Moment moment) {
    String type = switch (moment.kind()) {
      case KILL -> "kill";
      case DEATH -> "death";
      case FIGHT -> "fight";
    };
    String opponent = moment.opponent().matches("[A-Za-z0-9_]{1,16}") ? moment.opponent() : "player";
    LOG.info("{} on record against {}, {} s", type, opponent, moment.seconds());
    int after = Math.min(HANDLE_SECONDS, moment.skipSeconds());
    recorder.saveMoment(moment.seconds() + HANDLE_SECONDS + after, moment.skipSeconds() - after, "_" + type + "-" + opponent,
        saved -> {
          if (saved.file() != null) {
            keepFightTrim(saved.file(), moment, after);
            sendIfWanted(recorder, saved.file());
          }
          Minecraft.getInstance().execute(() -> announceMoment(moment, saved));
        });
  }

  /** How many seconds of sound to keep: enough for a saved clip and for a whole Recording part. */
  private static int soundWindow(ClipsConfig config) {
    return Math.max(config.bufferSeconds(), config.recordingPartMinutes() * 60);
  }

  /** Notes where the listener is and which sounds still play, for mixing the clip's sound later. */
  private static void listen(Minecraft client, SoundLog sounds) {
    SoundManager manager = client.getSoundManager();
    ListenerTransform ear = ((SoundEngineAccessor) ((SoundManagerAccessor) manager).clips$soundEngine()).clips$listener().getTransform();
    sounds.tick(ear.position(), ear.right(), manager::isActive);
  }

  /** Gives each new voice the speaker's player name, read on the client thread where it is safe. */
  private static void nameSpeakers(Minecraft client, VoiceLog voices) {
    ClientPacketListener connection = client.getConnection();
    if (connection == null) {
      return;
    }
    for (UUID speaker : voices.unnamed()) {
      PlayerInfo info = connection.getPlayerInfo(speaker);
      if (info != null) {
        voices.name(speaker.toString(), info.getProfile().name());
      }
    }
  }

  /** With Send to Studio on and Studio linked, uploads a new automatic clip with its fight trim. */
  private static void sendIfWanted(Recorder recorder, Path file) {
    if (!settings.sendToStudio() || studio == null || !studio.isLinked()) {
      return;
    }
    try {
      ClipLibrary.Clip clip = new ClipLibrary.Clip(file, Files.getLastModifiedTime(file).toInstant(), Files.size(file), ClipLibrary.length(file));
      ClipEdit edit = ClipEdit.load(file, clip.seconds());
      studio.upload(clip, edit, ClipsScreen.generatedTitle(clip), recorder, ClipsClient::announceUpload);
    } catch (IOException e) {
      LOG.warn("Could not send {} to Studio", file.getFileName(), e);
    }
  }

  /**
   * An automatic clip is saved with spare seconds before and after the fight, and its edit is set
   * to the fight itself. The editor then opens on the fight, and the player can drag the trim
   * outward when the detection cut it too tightly.
   */
  private static void keepFightTrim(Path clip, FightTracker.Moment moment, int after) {
    double length = ClipLibrary.length(clip);
    if (length <= 0) {
      return;
    }
    ClipEdit edit = ClipEdit.whole(length);
    double end = Math.max(1, length - after);
    edit.setEnd(end);
    edit.setStart(end - moment.seconds());
    List<ClipEvents.Event> events = moment.events().stream()
        .map(mark -> new ClipEvents.Event(mark.type(), Math.max(0, end - mark.secondsBeforeEnd()), mark.name()))
        .toList();
    try {
      edit.save(clip);
      ClipEvents.save(clip, events);
    } catch (IOException e) {
      LOG.warn("Could not keep the fight trim of {}", clip.getFileName(), e);
    }
  }

  private static void announceMoment(FightTracker.Moment moment, ClipWriter.Saved saved) {
    Minecraft client = Minecraft.getInstance();
    if (saved.file() == null) {
      toast(client, Component.translatable("ordersmp-clips.toast.failed.title"), Component.literal(String.valueOf(saved.problem())));
      return;
    }
    String title = switch (moment.kind()) {
      case DEATH -> "ordersmp-clips.toast.death";
      case FIGHT -> "ordersmp-clips.toast.fight";
      case KILL -> moment.kills() > 1 ? "ordersmp-clips.toast.kills" : "ordersmp-clips.toast.kill";
    };
    toast(client, Component.translatable(title, moment.kills()),
        Component.translatable("ordersmp-clips.toast.moment.body", moment.opponent(), ClipsScreen.duration(moment.seconds())));
  }

  /** Starts or stops Recording mode and says what happened. */
  public static void toggleRecording(Minecraft client) {
    if (recorder == null) {
      return;
    }
    if (recorder.isRecordingSession()) {
      int parts = recorder.stopRecording();
      toast(client, Component.translatable("ordersmp-clips.toast.recording.saved"),
          Component.translatable("ordersmp-clips.toast.recording.parts", parts));
      return;
    }
    String problem = recorder.startRecording();
    if (problem != null) {
      toast(client, Component.translatable("ordersmp-clips.toast.recording.failed"), Component.literal(problem));
      return;
    }
    toast(client, Component.translatable("ordersmp-clips.toast.recording.started"),
        Component.translatable("ordersmp-clips.toast.recording.started.body", recordMapping.getTranslatedKeyMessage()));
  }

  /** Tells the player how an upload ended, wherever they are in the game. */
  public static void announceUpload(Studio.Job job) {
    Minecraft client = Minecraft.getInstance();
    client.execute(() -> {
      if (job.phase() == Studio.Phase.DONE) {
        toast(client, Component.translatable("ordersmp-clips.upload.done.title"), Component.translatable("ordersmp-clips.upload.done.body"));
        return;
      }
      toast(client, Component.translatable("ordersmp-clips.upload.failed.title"), Component.literal(String.valueOf(job.problem())));
    });
  }

  /** Shows or replaces the mod's one toast, so messages never pile up. */
  public static void toast(Minecraft client, Component title, Component body) {
    SystemToast.addOrUpdate(client.gui.toastManager(), TOAST, title, body);
  }
}
