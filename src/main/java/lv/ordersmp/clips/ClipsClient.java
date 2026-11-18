package lv.ordersmp.clips;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import lv.ordersmp.clips.encode.ClipWriter;
import lv.ordersmp.clips.fight.FightTracker;
import lv.ordersmp.clips.studio.Studio;
import lv.ordersmp.clips.ui.ClipsScreen;
import lv.ordersmp.clips.ui.RecordingHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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
  private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(4000L);
  private static Recorder recorder;
  private static KeyMapping saveMapping;
  private static KeyMapping recordMapping;
  private static Studio studio;
  private static FightTracker fights;

  /** The running recorder, or null before the game finished starting. */
  public static Recorder recorder() {
    return recorder;
  }

  /** The fight tracker that saves kill and death clips, or null before the game finished starting. */
  public static FightTracker fights() {
    return fights;
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

    HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "recording"), hud);

    FightTracker tracker = new FightTracker(System::nanoTime, moment -> saveMoment(created, moment));
    tracker.configure(!ClipsConfig.AUTO_OFF.equals(config.autoClips()), ClipsConfig.AUTO_KILLS_AND_DEATHS.equals(config.autoClips()));

    ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
      recorder = created;
      fights = tracker;
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
    String type = moment.kind() == FightTracker.Kind.KILL ? "kill" : "death";
    String opponent = moment.opponent().matches("[A-Za-z0-9_]{1,16}") ? moment.opponent() : "player";
    LOG.info("{} on record against {}, {} s", type, opponent, moment.seconds());
    recorder.saveMoment(moment.seconds(), moment.skipSeconds(), "_" + type + "-" + opponent,
        saved -> Minecraft.getInstance().execute(() -> announceMoment(moment, saved)));
  }

  private static void announceMoment(FightTracker.Moment moment, ClipWriter.Saved saved) {
    Minecraft client = Minecraft.getInstance();
    if (saved.file() == null) {
      toast(client, Component.translatable("ordersmp-clips.toast.failed.title"), Component.literal(String.valueOf(saved.problem())));
      return;
    }
    String title = moment.kind() == FightTracker.Kind.DEATH ? "ordersmp-clips.toast.death"
        : moment.kills() > 1 ? "ordersmp-clips.toast.kills" : "ordersmp-clips.toast.kill";
    toast(client, Component.translatable(title, moment.kills()),
        Component.translatable("ordersmp-clips.toast.moment.body", moment.opponent(), ClipsScreen.duration(saved.seconds())));
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
