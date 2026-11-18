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

  static final int SMALL = 150;
  static final int BIG = 310;
  private static final List<Integer> QUALITIES = List.of(720, 1080, 1440);

  protected final Recorder recorder;
  private boolean shownRecording;
  private boolean shownPaused;
  private boolean shownLinked;

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
    list.addBig(volume("ordersmp-clips.options.volume.game", c.volumeGame(),
        percent -> ClipsClient.updateSettings(s -> s.withVolumes(percent, s.volumeMine(), s.volumeOthers()))));
    list.addBig(volume("ordersmp-clips.options.volume.mic", c.volumeMine(),
        percent -> ClipsClient.updateSettings(s -> s.withVolumes(s.volumeGame(), percent, s.volumeOthers()))));
    list.addBig(volume("ordersmp-clips.options.volume.others", c.volumeOthers(),
        percent -> ClipsClient.updateSettings(s -> s.withVolumes(s.volumeGame(), s.volumeMine(), percent))));
    list.addSmall(studioLink(), sendToStudio(c));
    list.addSmall(status(), keyBinds());
    list.addBig(Button.builder(Component.translatable("ordersmp-clips.options.advanced"),
            button -> minecraft.gui.setScreen(new ClipsAdvancedScreen(this, recorder)))
        .width(BIG)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.advanced.tooltip")))
        .build());
  }

  @Override
  public void tick() {
    boolean linked = ClipsClient.studio().isLinked();
    if (shownRecording != recorder.isRecordingSession() || shownPaused != paused() || shownLinked != linked) {
      rebuildWidgets();
    }
  }

  /** Notes what the switches show, so the page redraws when F6, F9 or a link change it. */
  protected final void remember() {
    shownRecording = recorder.isRecordingSession();
    shownPaused = paused();
    shownLinked = ClipsClient.studio().isLinked();
  }

  private boolean paused() {
    return recorder.state() == Recorder.State.PAUSED;
  }

  AbstractWidget quality(ClipsConfig c) {
    int current = QUALITIES.stream().min((a, b) -> Math.abs(a - c.maxHeight()) - Math.abs(b - c.maxHeight())).orElse(1080);
    return CycleButton.builder((Integer height) -> Component.literal(height + "p"), current)
        .withValues(QUALITIES)
        .withTooltip(height -> Tooltip.create(Component.translatable("ordersmp-clips.options.quality.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.quality"), (button, height) ->
            ClipsClient.updateSettings(s -> s.withVideo(height, s.fps(), s.bufferSeconds(), s.recordingPartMinutes())));
  }

  AbstractWidget frameRate(ClipsConfig c) {
    return CycleButton.builder((Integer fps) -> Component.translatable("ordersmp-clips.options.fps.value", fps), c.fps() >= 45 ? 60 : 30)
        .withValues(List.of(30, 60))
        .withTooltip(fps -> Tooltip.create(Component.translatable("ordersmp-clips.options.fps.tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.fps"), (button, fps) ->
            ClipsClient.updateSettings(s -> s.withVideo(s.maxHeight(), fps, s.bufferSeconds(), s.recordingPartMinutes())));
  }

  AbstractWidget keepLast(ClipsConfig c) {
    ValueSlider slider = new ValueSlider(SMALL, 30, 180, 15, c.bufferSeconds(),
        seconds -> Component.translatable("ordersmp-clips.options.keep", ClipsScreen.duration(seconds)),
        seconds -> ClipsClient.updateSettings(s -> s.withVideo(s.maxHeight(), s.fps(), seconds, s.recordingPartMinutes())));
    slider.setTooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.keep.tooltip")));
    return slider;
  }

  AbstractWidget partLength(ClipsConfig c) {
    ValueSlider slider = new ValueSlider(SMALL, 1, 30, 1, c.recordingPartMinutes(),
        minutes -> Component.translatable("ordersmp-clips.options.parts", minutes),
        minutes -> ClipsClient.updateSettings(s -> s.withVideo(s.maxHeight(), s.fps(), s.bufferSeconds(), minutes)));
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
        .create(0, 0, BIG, 20, Component.translatable("ordersmp-clips.options.auto"), (button, value) ->
            ClipsClient.updateSettings(s -> s.withClips(value != AutoClip.OFF,
                value == AutoClip.KILLS_AND_DEATHS || value == AutoClip.EVERYTHING, value == AutoClip.EVERYTHING)));
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
    return toggle("ordersmp-clips.options.kills", c.clipKills(), on -> ClipsClient.updateSettings(s -> s.withClips(on, s.clipDeaths(), s.clipLongFights())));
  }

  AbstractWidget deaths(ClipsConfig c) {
    return toggle("ordersmp-clips.options.deaths", c.clipDeaths(), on -> ClipsClient.updateSettings(s -> s.withClips(s.clipKills(), on, s.clipLongFights())));
  }

  AbstractWidget longFights(ClipsConfig c) {
    return CycleButton.onOffBuilder(c.clipLongFights())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.long.tooltip")))
        .create(0, 0, BIG, 20, Component.translatable("ordersmp-clips.options.long"),
            (pressed, on) -> ClipsClient.updateSettings(s -> s.withClips(s.clipKills(), s.clipDeaths(), on)));
  }

  AbstractWidget mark(ClipsConfig c) {
    return toggle("ordersmp-clips.options.mark", c.showHud(), on -> ClipsClient.updateSettings(s -> s.withShowHud(on)));
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
        .create(0, 0, SMALL, 20, Component.translatable("ordersmp-clips.options.send"),
            (button, on) -> ClipsClient.updateSettings(s -> s.withSendToStudio(on)));
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

  private AbstractWidget status() {
    Button button = Button.builder(Component.translatable("ordersmp-clips.options.status"), pressed -> {})
        .width(SMALL)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.later")))
        .build();
    button.active = false;
    return button;
  }

  private AbstractWidget volume(String key, int current, IntConsumer change) {
    ValueSlider slider = new ValueSlider(BIG, 0, 200, 5, current, percent -> Component.translatable(key, percent), change);
    slider.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
    return slider;
  }

  private AbstractWidget toggle(String key, boolean on, Consumer<Boolean> change) {
    return CycleButton.onOffBuilder(on)
        .withTooltip(value -> Tooltip.create(Component.translatable(key + ".tooltip")))
        .create(0, 0, SMALL, 20, Component.translatable(key), (button, value) -> change.accept(value));
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
