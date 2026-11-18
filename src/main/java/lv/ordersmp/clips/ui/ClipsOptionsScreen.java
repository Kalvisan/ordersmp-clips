package lv.ordersmp.clips.ui;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import lv.ordersmp.clips.ClipLibrary;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.ClipsConfig;
import lv.ordersmp.clips.Recorder;
import lv.ordersmp.clips.studio.Studio;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Clips options, quick page: the few settings most players change, laid out like the vanilla
 * options so it fits every GUI scale. Everything else is on the Advanced page. Every change is
 * saved and applied at once.
 */
public class ClipsOptionsScreen extends OptionsSubScreen {

  /** What saves a clip by itself, from nothing to every moment Clips can find. */
  private enum AutoClip { OFF, KILLS, KILLS_AND_DEATHS, EVERYTHING }

  /** How the corner mark is drawn. */
  private enum Mark { ON, MINIMAL, OFF }

  static final int SMALL = 150;
  static final int BIG = 310;
  private static final List<Integer> QUALITIES = List.of(720, 1080, 1440);
  private static final List<Integer> BITRATES = List.of(0, 6, 10, 16, 25, 40);
  private static final List<Integer> SIZES = List.of(0, 2, 5, 10, 20, 50, 100);
  private static final List<Integer> DAYS = List.of(0, 3, 7, 14, 30, 90);

  protected final Recorder recorder;
  private boolean shownRecording;
  private boolean shownPaused;
  private boolean shownLinked;
  private boolean shownProblem;

  public ClipsOptionsScreen(Screen parent, Recorder recorder) {
    this(parent, recorder, Component.translatable("ordersmp-clips.options.title"));
  }

  protected ClipsOptionsScreen(Screen parent, Recorder recorder, Component title) {
    super(parent, Minecraft.getInstance().options, title);
    this.recorder = recorder;
  }

  @Override
  protected void addOptions() {
    remember();
    ClipsConfig c = ClipsClient.settings();
    list.addHeader(Component.translatable("ordersmp-clips.options.quick.subtitle").withStyle(ChatFormatting.GRAY));
    list.addSmall(quality(c), keepLast(c));
    list.addBig(autoClip(c));
    list.addSmall(clipping(), recordingMode());
    list.addHeader(Component.translatable("ordersmp-clips.options.volume"));
    addVolumes(c);
    list.addSmall(studioLink(), sendToStudio(c));
    list.addSmall(status(), keyBinds());
    list.addBig(Button.builder(Component.translatable("ordersmp-clips.options.advanced"),
            button -> minecraft.gui.setScreen(new ClipsAdvancedScreen(this, recorder)))
        .width(BIG)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.advanced.tooltip")))
        .build());
  }

  /** The three default volume sliders for new clips, shared by both pages. */
  protected final void addVolumes(ClipsConfig c) {
    list.addBig(volume("ordersmp-clips.options.volume.game", c.volumeGame(), percent -> change(b -> b.volumeGame = percent)));
    list.addBig(volume("ordersmp-clips.options.volume.mic", c.volumeMine(), percent -> change(b -> b.volumeMine = percent)));
    list.addBig(volume("ordersmp-clips.options.volume.others", c.volumeOthers(), percent -> change(b -> b.volumeOthers = percent)));
  }

  /** Saves and applies one change to the settings. */
  static void change(Consumer<ClipsConfig.Builder> edit) {
    ClipsClient.updateSettings(s -> s.with(edit));
  }

  @Override
  public void tick() {
    boolean linked = ClipsClient.studio().isLinked();
    if (shownRecording != recorder.isRecordingSession() || shownPaused != paused() || shownLinked != linked
        || shownProblem != hasProblem()) {
      rebuildWidgets();
    }
  }

  /** Notes what the switches show, so the page redraws when F6, F9, a link or a problem change it. */
  protected final void remember() {
    shownRecording = recorder.isRecordingSession();
    shownPaused = paused();
    shownLinked = ClipsClient.studio().isLinked();
    shownProblem = hasProblem();
  }

  private boolean paused() {
    return recorder.state() == Recorder.State.PAUSED;
  }

  private boolean hasProblem() {
    return recorder.state() == Recorder.State.PROBLEM || recorder.ffmpeg() == null;
  }

  AbstractWidget quality(ClipsConfig c) {
    int current = nearest(QUALITIES, c.maxHeight());
    return CycleButton.builder((Integer height) -> Component.literal(height + "p"), current)
        .withValues(QUALITIES)
        .withTooltip(height -> Tooltip.create(Component.translatable("ordersmp-clips.options.quality.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.quality"), (button, height) -> change(b -> b.maxHeight = height));
  }

  AbstractWidget frameRate(ClipsConfig c) {
    return CycleButton.builder((Integer fps) -> Component.translatable("ordersmp-clips.options.fps.value", fps), c.fps() >= 45 ? 60 : 30)
        .withValues(List.of(30, 60))
        .withTooltip(fps -> Tooltip.create(Component.translatable("ordersmp-clips.options.fps.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.fps"), (button, fps) -> change(b -> b.fps = fps));
  }

  /** Video bitrate: Auto picks one from the quality; a higher number is sharper and bigger. */
  AbstractWidget bitrate(ClipsConfig c) {
    int current = nearest(BITRATES, c.bitrateMbps());
    return CycleButton.builder((Integer mbps) -> mbps == 0
                ? Component.translatable("ordersmp-clips.options.bitrate.auto")
                : Component.translatable("ordersmp-clips.options.bitrate.value", mbps), current)
        .withValues(BITRATES)
        .withTooltip(mbps -> Tooltip.create(Component.translatable("ordersmp-clips.options.bitrate.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.bitrate"), (button, mbps) -> change(b -> b.bitrateMbps = mbps));
  }

  AbstractWidget keepLast(ClipsConfig c) {
    ValueSlider slider = new ValueSlider(SMALL, 30, 600, 30, c.bufferSeconds(),
        seconds -> Component.translatable("ordersmp-clips.options.keep", ClipsScreen.duration(seconds)),
        seconds -> change(b -> b.bufferSeconds = seconds));
    slider.setTooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.keep.tooltip")));
    return slider;
  }

  AbstractWidget partLength(ClipsConfig c) {
    ValueSlider slider = new ValueSlider(SMALL, 1, 30, 1, c.recordingPartMinutes(),
        minutes -> Component.translatable("ordersmp-clips.options.parts", minutes),
        minutes -> change(b -> b.recordingPartMinutes = minutes));
    slider.setTooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.parts.tooltip")));
    return slider;
  }

  private AbstractWidget autoClip(ClipsConfig c) {
    AutoClip current = !c.clipKills() ? AutoClip.OFF
        : c.clipLongFights() ? AutoClip.EVERYTHING
        : c.clipDeaths() ? AutoClip.KILLS_AND_DEATHS : AutoClip.KILLS;
    return CycleButton.builder((AutoClip value) -> Component.translatable("ordersmp-clips.options.auto." + value.name().toLowerCase(Locale.ROOT)), current)
        .withValues(List.of(AutoClip.values()))
        .withTooltip(value -> Tooltip.create(Component.translatable("ordersmp-clips.options.auto.tooltip")))
        .create(0, 0, BIG, 20, Component.translatable("ordersmp-clips.options.auto"), (button, value) -> change(b -> {
          b.clipKills = value != AutoClip.OFF;
          b.clipDeaths = value == AutoClip.KILLS_AND_DEATHS || value == AutoClip.EVERYTHING;
          b.clipLongFights = value == AutoClip.EVERYTHING;
        }));
  }

  AbstractWidget clipping() {
    return CycleButton.onOffBuilder(!paused())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.clipping.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.clipping"), (button, on) -> {
          if (on == paused()) {
            recorder.togglePause();
          }
        });
  }

  AbstractWidget recordingMode() {
    Component label = recorder.isRecordingSession()
        ? Component.translatable("ordersmp-clips.options.session.stop", ClipsScreen.duration(recorder.recordingSeconds()))
        : Component.translatable("ordersmp-clips.options.session.start");
    Button button = Button.builder(label, pressed -> {
          ClipsClient.toggleRecording(minecraft);
          rebuildWidgets();
        })
        .width(SMALL)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.session.tooltip")))
        .build();
    button.active = recorder.isRecordingSession() || recorder.state() == Recorder.State.RECORDING || recorder.state() == Recorder.State.SAVING;
    return button;
  }

  AbstractWidget kills(ClipsConfig c) {
    return toggle("ordersmp-clips.options.kills", c.clipKills(), on -> change(b -> b.clipKills = on));
  }

  AbstractWidget deaths(ClipsConfig c) {
    return toggle("ordersmp-clips.options.deaths", c.clipDeaths(), on -> change(b -> b.clipDeaths = on));
  }

  AbstractWidget longFights(ClipsConfig c) {
    return CycleButton.onOffBuilder(c.clipLongFights())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.long.tooltip")))
        .create(0, 0, BIG, 20, Component.translatable("ordersmp-clips.options.long"), (pressed, on) -> change(b -> b.clipLongFights = on));
  }

  AbstractWidget beforeFirstHit(ClipsConfig c) {
    return seconds("ordersmp-clips.options.before", c.beforeSeconds(), value -> change(b -> b.beforeSeconds = value));
  }

  AbstractWidget afterKill(ClipsConfig c) {
    return seconds("ordersmp-clips.options.after", c.afterSeconds(), value -> change(b -> b.afterSeconds = value));
  }

  /** Kills a few seconds apart as one clip of the whole fight, or one clip for each kill. */
  AbstractWidget closeKills(ClipsConfig c) {
    return CycleButton.builder((Boolean one) -> Component.translatable(one ? "ordersmp-clips.options.close.one" : "ordersmp-clips.options.close.separate"),
            c.closeKillsOneClip())
        .withValues(List.of(true, false))
        .withTooltip(one -> Tooltip.create(Component.translatable("ordersmp-clips.options.close.tooltip")))
        .create(0, 0, BIG, 20, Component.translatable("ordersmp-clips.options.close"), (button, one) -> change(b -> b.closeKillsOneClip = one));
  }

  /** The corner mark: full, icon only, or hidden. It is never part of a clip either way. */
  AbstractWidget mark(ClipsConfig c) {
    Mark current = !c.showHud() ? Mark.OFF : c.markMinimal() ? Mark.MINIMAL : Mark.ON;
    return CycleButton.builder((Mark value) -> Component.translatable("ordersmp-clips.options.mark." + value.name().toLowerCase(Locale.ROOT)), current)
        .withValues(List.of(Mark.values()))
        .withTooltip(value -> Tooltip.create(Component.translatable("ordersmp-clips.options.mark.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.mark"), (button, value) -> change(b -> {
          b.showHud = value != Mark.OFF;
          b.markMinimal = value == Mark.MINIMAL;
        }));
  }

  AbstractWidget myVoice(ClipsConfig c) {
    return toggle("ordersmp-clips.options.my_voice", c.recordMyVoice(), on -> change(b -> b.recordMyVoice = on));
  }

  AbstractWidget playersIHear(ClipsConfig c) {
    return toggle("ordersmp-clips.options.players_heard", c.recordOthers(), on -> change(b -> b.recordOthers = on));
  }

  /** Shown so players know the icon is planned; it needs the server's support, which is not built. */
  AbstractWidget recordingIcon() {
    Button button = Button.builder(Component.translatable("ordersmp-clips.options.icon"), pressed -> {})
        .width(BIG)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.icon.tooltip")))
        .build();
    button.active = false;
    return button;
  }

  AbstractWidget maxSize(ClipsConfig c) {
    return CycleButton.builder((Integer gb) -> gb == 0
                ? Component.translatable("ordersmp-clips.options.size.none")
                : Component.translatable("ordersmp-clips.options.size.value", gb), nearest(SIZES, c.maxSizeGb()))
        .withValues(SIZES)
        .withTooltip(gb -> Tooltip.create(Component.translatable("ordersmp-clips.options.size.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.size"), (button, gb) -> change(b -> b.maxSizeGb = gb));
  }

  AbstractWidget deleteAfter(ClipsConfig c) {
    return CycleButton.builder((Integer days) -> days == 0
                ? Component.translatable("ordersmp-clips.options.days.never")
                : Component.translatable("ordersmp-clips.options.days.value", days), nearest(DAYS, c.deleteAfterDays()))
        .withValues(DAYS)
        .withTooltip(days -> Tooltip.create(Component.translatable("ordersmp-clips.options.days.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.days"), (button, days) -> change(b -> b.deleteAfterDays = days));
  }

  AbstractWidget studioLink() {
    Studio studio = ClipsClient.studio();
    if (!studio.isLinked()) {
      return Button.builder(Component.translatable("ordersmp-clips.options.link"),
              button -> minecraft.gui.setScreen(new StudioLinkScreen(this, studio, () -> {})))
          .width(SMALL)
          .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.link.tooltip")))
          .build();
    }
    return Button.builder(Component.translatable("ordersmp-clips.options.linked"), button -> confirmUnlink())
        .width(SMALL)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.editor.linked", String.valueOf(studio.accountName()))))
        .build();
  }

  AbstractWidget sendToStudio(ClipsConfig c) {
    Studio studio = ClipsClient.studio();
    if (!studio.isLinked()) {
      return Button.builder(Component.translatable("ordersmp-clips.options.send.link_first"),
              button -> minecraft.gui.setScreen(new StudioLinkScreen(this, studio, () -> {})))
          .width(SMALL)
          .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.send.link_first.tooltip")))
          .build();
    }
    return CycleButton.onOffBuilder(c.sendToStudio())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.send.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.send"), (button, on) -> change(b -> b.sendToStudio = on));
  }

  AbstractWidget keyBinds() {
    return Button.builder(Component.translatable("ordersmp-clips.options.keys"),
        button -> minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options))).width(SMALL).build();
  }

  AbstractWidget openFolder() {
    return Button.builder(Component.translatable("ordersmp-clips.screen.folder"),
        button -> ClipsScreen.openFolder(recorder.clipsFolder())).width(SMALL).build();
  }

  AbstractWidget deleteAll() {
    return Button.builder(Component.translatable("ordersmp-clips.options.delete_all"), button -> confirmDeleteAll())
        .width(SMALL)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.delete_all.tooltip")))
        .build();
  }

  /**
   * Status: whether recording works and what it uses, so a player can see why clips fail. The
   * label gets a {@code (!)} while something is wrong.
   */
  AbstractWidget status() {
    return Button.builder(Component.translatable(hasProblem() ? "ordersmp-clips.options.status.problem" : "ordersmp-clips.options.status"),
            pressed -> showStatus())
        .width(SMALL)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.status.tooltip")))
        .build();
  }

  private void showStatus() {
    Recorder.Stats stats = recorder.stats();
    String problem = recorder.problem();
    Component state = recorder.ffmpeg() == null
        ? Component.translatable("ordersmp-clips.options.status.no_ffmpeg")
        : problem != null ? Component.literal(problem)
        : Component.translatable("ordersmp-clips.options.status.ok");
    Component body = Component.translatable("ordersmp-clips.options.status.body", state,
        stats.encoder() == null ? "-" : stats.encoder(), stats.width(), stats.height(), stats.fps(),
        ClipsScreen.duration(stats.heldSeconds()), String.format(Locale.ROOT, "%.0f", stats.heldMegabytes()));
    minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(this),
        Component.translatable("ordersmp-clips.options.status.title"), body));
  }

  private AbstractWidget volume(String key, int current, IntConsumer change) {
    ValueSlider slider = new ValueSlider(BIG, 0, 200, 5, current, percent -> Component.translatable(key, percent), change);
    slider.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
    return slider;
  }

  private static AbstractWidget seconds(String key, int current, IntConsumer change) {
    ValueSlider slider = new ValueSlider(SMALL, 0, 15, 1, current, value -> Component.translatable(key, value), change);
    slider.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
    return slider;
  }

  private AbstractWidget toggle(String key, boolean on, Consumer<Boolean> change) {
    return CycleButton.onOffBuilder(on)
        .withTooltip(value -> Tooltip.create(Component.translatable(key + ".tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable(key), (button, value) -> change.accept(value));
  }

  private static int nearest(List<Integer> values, int wanted) {
    return values.stream().min((a, b) -> Math.abs(a - wanted) - Math.abs(b - wanted)).orElse(values.getFirst());
  }

  private void confirmUnlink() {
    minecraft.gui.setScreen(new ConfirmScreen(unlink -> {
      if (unlink) {
        ClipsClient.studio().unlink();
      }
      minecraft.gui.setScreen(this);
    }, Component.translatable("ordersmp-clips.editor.unlink.title"),
        Component.translatable("ordersmp-clips.editor.unlink.body"),
        Component.translatable("ordersmp-clips.editor.unlink.confirm"), CommonComponents.GUI_CANCEL));
  }

  private void confirmDeleteAll() {
    List<ClipLibrary.Clip> clips;
    try {
      clips = ClipLibrary.list(recorder.clipsFolder());
    } catch (IOException e) {
      clips = List.of();
    }
    List<ClipLibrary.Clip> all = clips;
    minecraft.gui.setScreen(new ConfirmScreen(delete -> {
      if (delete) {
        int failed = 0;
        for (ClipLibrary.Clip clip : all) {
          try {
            ClipLibrary.delete(clip);
          } catch (IOException e) {
            failed++;
          }
        }
        ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.toast.deleted_all", all.size() - failed), null);
      }
      minecraft.gui.setScreen(this);
    }, Component.translatable("ordersmp-clips.options.delete_all.title"),
        Component.translatable("ordersmp-clips.options.delete_all.body", all.size()),
        Component.translatable("ordersmp-clips.options.delete_all.confirm"), CommonComponents.GUI_CANCEL));
  }

  /** A slider over whole numbers in steps, such as seconds or percent, labelled by {@code label}. */
  static final class ValueSlider extends AbstractSliderButton {

    private final int min;
    private final int max;
    private final int step;
    private final IntFunction<Component> label;
    private final IntConsumer change;

    ValueSlider(int width, int min, int max, int step, int current, IntFunction<Component> label, IntConsumer change) {
      super(0, 0, width, 20, Component.empty(), (double) (Math.max(min, Math.min(max, current)) - min) / (max - min));
      this.min = min;
      this.max = max;
      this.step = step;
      this.label = label;
      this.change = change;
      updateMessage();
    }

    private int current() {
      return min + (int) Math.round(value * (max - min) / step) * step;
    }

    @Override
    protected void updateMessage() {
      setMessage(label.apply(current()));
    }

    @Override
    protected void applyValue() {
      change.accept(current());
    }
  }
}
