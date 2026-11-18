package lv.ordersmp.clips.ui;

import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.ClipsConfig;
import lv.ordersmp.clips.Recorder;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Clips options: whether clipping runs, Recording mode, which moments save themselves, and the
 * recording mark. Every switch says in words what it does, and changes apply and save at once.
 */
public final class ClipsOptionsScreen extends Screen {

  private static final int WIDE = 310;
  private static final int HALF = 150;

  private final Screen parent;
  private final Recorder recorder;
  private HeaderAndFooterLayout layout;
  private boolean shownRecording;
  private boolean shownPaused;

  public ClipsOptionsScreen(Screen parent, Recorder recorder) {
    super(Component.translatable("ordersmp-clips.options.title"));
    this.parent = parent;
    this.recorder = recorder;
  }

  @Override
  protected void init() {
    ClipsConfig settings = ClipsClient.settings();
    shownRecording = recorder.isRecordingSession();
    shownPaused = recorder.state() == Recorder.State.PAUSED;
    layout = new HeaderAndFooterLayout(this, 33, 33);
    layout.addTitleHeader(title, font);

    GridLayout grid = new GridLayout().columnSpacing(10).rowSpacing(4);
    GridLayout.RowHelper rows = grid.createRowHelper(2);

    rows.addChild(heading("ordersmp-clips.options.recording"), 2);
    rows.addChild(CycleButton.onOffBuilder(!shownPaused)
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.clipping.tooltip")))
        .create(0, 0, HALF, 20, Component.translatable("ordersmp-clips.options.clipping"), (button, on) -> {
          if (on == (recorder.state() == Recorder.State.PAUSED)) {
            recorder.togglePause();
          }
        }));
    Button session = rows.addChild(Button.builder(sessionLabel(), button -> {
          ClipsClient.toggleRecording(minecraft);
          rebuildWidgets();
        })
        .width(HALF)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.options.session.tooltip")))
        .build());
    session.active = recorder.state() != Recorder.State.PROBLEM && recorder.state() != Recorder.State.STARTING;

    rows.addChild(heading("ordersmp-clips.options.moments"), 2);
    rows.addChild(CycleButton.onOffBuilder(settings.clipKills())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.kills.tooltip")))
        .create(0, 0, HALF, 20, Component.translatable("ordersmp-clips.options.kills"),
            (button, on) -> ClipsClient.updateSettings(c -> c.withClips(on, c.clipDeaths(), c.clipLongFights()))));
    rows.addChild(CycleButton.onOffBuilder(settings.clipDeaths())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.deaths.tooltip")))
        .create(0, 0, HALF, 20, Component.translatable("ordersmp-clips.options.deaths"),
            (button, on) -> ClipsClient.updateSettings(c -> c.withClips(c.clipKills(), on, c.clipLongFights()))));
    rows.addChild(CycleButton.onOffBuilder(settings.clipLongFights())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.long.tooltip")))
        .create(0, 0, WIDE, 20, Component.translatable("ordersmp-clips.options.long"),
            (button, on) -> ClipsClient.updateSettings(c -> c.withClips(c.clipKills(), c.clipDeaths(), on))), 2);

    rows.addChild(heading("ordersmp-clips.options.storage"), 2);
    rows.addChild(Button.builder(Component.translatable("ordersmp-clips.screen.folder"),
        button -> ClipsScreen.openFolder(recorder.clipsFolder())).width(WIDE).build(), 2);

    rows.addChild(heading("ordersmp-clips.options.screen"), 2);
    rows.addChild(CycleButton.onOffBuilder(settings.showHud())
        .withTooltip(on -> Tooltip.create(Component.translatable("ordersmp-clips.options.mark.tooltip")))
        .create(0, 0, WIDE, 20, Component.translatable("ordersmp-clips.options.mark"),
            (button, on) -> ClipsClient.updateSettings(c -> c.withShowHud(on))), 2);

    layout.addToContents(grid);
    layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
  }

  @Override
  protected void repositionElements() {
    layout.arrangeElements();
  }

  @Override
  public void tick() {
    if (shownRecording != recorder.isRecordingSession() || shownPaused != (recorder.state() == Recorder.State.PAUSED)) {
      rebuildWidgets();
    }
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  private Component sessionLabel() {
    if (!recorder.isRecordingSession()) {
      return Component.translatable("ordersmp-clips.options.session.start");
    }
    return Component.translatable("ordersmp-clips.options.session.stop", ClipsScreen.duration(recorder.recordingSeconds()));
  }

  private StringWidget heading(String key) {
    return new StringWidget(WIDE, 12, Component.translatable(key), font);
  }
}
