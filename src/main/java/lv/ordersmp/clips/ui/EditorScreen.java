package lv.ordersmp.clips.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import lv.ordersmp.clips.ClipLibrary;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.Recorder;
import lv.ordersmp.clips.edit.ClipEdit;
import lv.ordersmp.clips.edit.ClipExport;
import lv.ordersmp.clips.edit.VideoPreview;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.MenuTabBar;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * The clip editor: basics only. A preview with play controls on the left, tabs for trimming,
 * sound and sending on the right, and the timeline across the bottom. Edits never change the
 * video; Done keeps them beside the clip and Save as New Clip writes a copy of the kept parts.
 */
public final class EditorScreen extends Screen {

  private static final Identifier PREVIEW = texture("editor/preview");
  private static final Identifier FILMSTRIP = texture("editor/filmstrip");
  private static final Identifier PLAY = icon("play");
  private static final Identifier PAUSE = icon("pause");
  private static final Identifier START = icon("start");
  private static final Identifier END = icon("end");
  private static final Identifier CUT = icon("cut");
  private static final Identifier TRASH = icon("trash");
  private static final Identifier CROSS = icon("cross");
  private static final Identifier SEND = icon("send");
  private static final int MARGIN = 8;
  private static final int TOP = 20;
  private static final int RIGHT_MIN = 208;
  private static final int BOTTOM_SPACE = 101;
  private static final int TIMELINE_HEIGHT = 41;
  private static final int WHITE = 0xFFFFFFFF;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int BLACK = 0xFF000000;
  private static final long SEEK_INTERVAL_NANOS = 80_000_000L;
  private static final double STEP_SECONDS = 1;

  private final Screen parent;
  private final Recorder recorder;
  private final ClipLibrary.Clip clip;
  private final ClipEdit edit;
  private final ClipEdit saved;
  private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
  private int tabIndex;
  private VideoPreview preview;
  private DynamicTexture previewTexture;
  private boolean filmstripLoaded;
  private int generation;
  private double playhead;
  private boolean playing;
  private boolean buffering;
  private long lastNanos;
  private Double pendingSeek;
  private long lastSeekNanos;
  private boolean exporting;
  private int previewX;
  private int previewY;
  private int previewWidth;
  private int previewHeight;
  private SpriteIconButton playButton;
  private boolean playButtonShowsPause;
  private TimeSlider startSlider;
  private TimeSlider endSlider;
  private Button removeButton;
  private Button exportButton;
  private TimelineWidget timeline;
  private TabNavigationBar tabs;

  public EditorScreen(Screen parent, Recorder recorder, ClipLibrary.Clip clip) {
    super(Component.translatable("ordersmp-clips.editor.title", ClipsScreen.name(clip)));
    this.parent = parent;
    this.recorder = recorder;
    this.clip = clip;
    this.edit = ClipEdit.load(clip.file(), clip.seconds());
    this.saved = edit.copy();
    this.playhead = edit.start();
  }

  @Override
  protected void init() {
    if (preview == null) {
      preview = new VideoPreview(recorder.ffmpeg(), clip.file());
      pendingSeek = playhead;
    }
    if (previewTexture == null) {
      previewTexture = new DynamicTexture(() -> "Clips preview", VideoPreview.WIDTH, VideoPreview.HEIGHT, true);
      minecraft.getTextureManager().register(PREVIEW, previewTexture);
    }
    if (!filmstripLoaded) {
      loadFilmstrip();
    }

    previewWidth = Math.max(160, Math.min(Math.min(width / 2, width - RIGHT_MIN - MARGIN * 3),
        (height - TOP - BOTTOM_SPACE) * 16 / 9));
    previewHeight = previewWidth * 9 / 16;
    previewX = MARGIN;
    previewY = TOP;
    int transportY = previewY + previewHeight + 4;
    int rightX = previewX + previewWidth + MARGIN;
    int rightWidth = width - rightX - MARGIN;

    addRenderableWidget(iconOnly("ordersmp-clips.editor.start", START, button -> seek(edit.start())))
        .setPosition(previewX, transportY);
    playButton = addRenderableWidget(iconOnly(playing ? "ordersmp-clips.editor.pause" : "ordersmp-clips.editor.play",
        playing ? PAUSE : PLAY, button -> togglePlay()));
    playButton.setPosition(previewX + 22, transportY);
    playButtonShowsPause = playing;
    addRenderableWidget(iconOnly("ordersmp-clips.editor.end", END, button -> seek(edit.end() - 0.01)))
        .setPosition(previewX + 44, transportY);

    int timelineY = transportY + 24;
    timeline = addRenderableWidget(new TimelineWidget(MARGIN, timelineY, width - MARGIN * 2, TIMELINE_HEIGHT, edit,
        () -> playhead, this::seek, this::editChanged));
    if (filmstripLoaded) {
      timeline.setFilmstrip(FILMSTRIP);
    }

    int bottomY = Math.max(timelineY + TIMELINE_HEIGHT + 6, height - 26);
    addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> done())
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.editor.done.tooltip")))
        .bounds(width / 2 - 154, bottomY, 150, 20).build());
    SpriteIconButton discard = addRenderableWidget(SpriteIconButton.builder(
            Component.translatable("ordersmp-clips.editor.discard"), button -> onClose(), false)
        .width(150).sprite(CROSS, 12, 12).build());
    discard.setPosition(width / 2 + 4, bottomY);

    int tabWidth = rightWidth / 3;
    GridLayoutTab trim = trimTab(Math.min(rightWidth, 200));
    GridLayoutTab audio = audioTab(rightWidth);
    GridLayoutTab send = sendTab(Math.min(rightWidth, 200));
    tabs = addRenderableWidget(TabNavigationBar.builder(tabManager, rightX, TOP, rightWidth, 24)
        .addTab(new MenuTabBar.MenuTabButton(tabManager, trim, tabWidth, 24), trim)
        .addTab(new MenuTabBar.MenuTabButton(tabManager, audio, tabWidth, 24), audio)
        .addTab(new MenuTabBar.MenuTabButton(tabManager, send, tabWidth, 24), send)
        .build());
    tabs.arrangeElements(rightWidth);
    tabManager.setTabArea(new ScreenRectangle(rightX, TOP + 28, rightWidth, timelineY - TOP - 32));
    tabs.selectTab(tabIndex, false);
    refreshControls();
  }

  @Override
  public void tick() {
    refreshControls();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    advance();
    super.extractRenderState(graphics, mouseX, mouseY, a);
    graphics.centeredText(font, title, width / 2, 6, WHITE);
    graphics.fill(previewX - 1, previewY - 1, previewX + previewWidth + 1, previewY + previewHeight + 1, BLACK);
    graphics.blit(RenderPipelines.GUI_TEXTURED, PREVIEW, previewX, previewY, 0, 0, previewWidth, previewHeight,
        VideoPreview.WIDTH, VideoPreview.HEIGHT, VideoPreview.WIDTH, VideoPreview.HEIGHT);
    String time = ClipsScreen.duration((int) edit.keptPosition(playhead)) + " / " + ClipsScreen.duration((int) Math.round(edit.keptLength()));
    graphics.text(font, time, previewX + 70, previewY + previewHeight + 10, edit.isRemoved(playhead) ? GRAY : WHITE);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (event.key() == InputConstants.KEY_SPACE) {
      togglePlay();
      return true;
    }
    if (event.key() == InputConstants.KEY_LEFT) {
      seek(playhead - STEP_SECONDS);
      return true;
    }
    if (event.key() == InputConstants.KEY_RIGHT) {
      seek(playhead + STEP_SECONDS);
      return true;
    }
    return super.keyPressed(event);
  }

  @Override
  public void removed() {
    generation++;
    if (tabs != null) {
      tabIndex = tabs.getTabs().indexOf(tabManager.getCurrentTab());
    }
    playing = false;
    if (preview != null) {
      preview.close();
      preview = null;
    }
    if (previewTexture != null) {
      minecraft.getTextureManager().release(PREVIEW);
      previewTexture = null;
    }
    if (filmstripLoaded) {
      minecraft.getTextureManager().release(FILMSTRIP);
      filmstripLoaded = false;
    }
    super.removed();
  }

  @Override
  public void onClose() {
    if (edit.sameAs(saved)) {
      minecraft.gui.setScreen(parent);
      return;
    }
    minecraft.gui.setScreen(new ConfirmScreen(discard -> minecraft.gui.setScreen(discard ? parent : this),
        Component.translatable("ordersmp-clips.editor.discard.title"),
        Component.translatable("ordersmp-clips.editor.discard.body"),
        Component.translatable("ordersmp-clips.editor.discard"), CommonComponents.GUI_CANCEL));
  }

  private GridLayoutTab trimTab(int controlWidth) {
    EditorTab tab = new EditorTab(Component.translatable("ordersmp-clips.editor.tab.trim"));
    GridLayout.RowHelper rows = tab.rows(2);
    startSlider = rows.addChild(new TimeSlider(controlWidth, "ordersmp-clips.editor.trim.start", edit::start, value -> {
      edit.setStart(value);
      seek(edit.start());
      editChanged();
    }), 2);
    endSlider = rows.addChild(new TimeSlider(controlWidth, "ordersmp-clips.editor.trim.end", edit::end, value -> {
      edit.setEnd(value);
      seek(Math.max(edit.start(), edit.end() - 0.01));
      editChanged();
    }), 2);
    int half = (controlWidth - 4) / 2;
    rows.addChild(textAndIcon("ordersmp-clips.editor.cut", CUT, half, button -> {
      if (edit.cut(playhead)) {
        editChanged();
      }
    }));
    removeButton = rows.addChild(textAndIcon("ordersmp-clips.editor.remove", TRASH, half, button -> {
      if (edit.toggleRemove(playhead)) {
        editChanged();
      }
    }));
    rows.addChild(textAndIcon("ordersmp-clips.editor.reset", CROSS, controlWidth, button -> {
      edit.reset();
      editChanged();
    }), 2);
    rows.addChild(new MultiLineTextWidget(Component.translatable("ordersmp-clips.editor.trim.note"), font)
        .setMaxWidth(controlWidth), 2);
    return tab;
  }

  private GridLayoutTab audioTab(int controlWidth) {
    EditorTab tab = new EditorTab(Component.translatable("ordersmp-clips.editor.tab.audio"));
    GridLayout.RowHelper rows = tab.rows(1);
    rows.addChild(new MultiLineTextWidget(Component.translatable("ordersmp-clips.editor.audio.none"), font).setMaxWidth(controlWidth));
    rows.addChild(new MultiLineTextWidget(Component.translatable("ordersmp-clips.editor.audio.later"), font).setMaxWidth(controlWidth));
    return tab;
  }

  private GridLayoutTab sendTab(int controlWidth) {
    EditorTab tab = new EditorTab(Component.translatable("ordersmp-clips.editor.tab.send"));
    GridLayout.RowHelper rows = tab.rows(1);
    Button studio = rows.addChild(textAndIcon("ordersmp-clips.editor.studio", SEND, controlWidth, button -> {}));
    studio.active = false;
    studio.setTooltip(Tooltip.create(Component.translatable("ordersmp-clips.editor.studio.later")));
    exportButton = rows.addChild(Button.builder(Component.translatable("ordersmp-clips.editor.export"), button -> export())
        .width(controlWidth)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.editor.export.tooltip")))
        .build());
    rows.addChild(new MultiLineTextWidget(Component.translatable("ordersmp-clips.editor.send.note"), font).setMaxWidth(controlWidth));
    return tab;
  }

  /** Moves the playback clock and shows the frame that is due. Runs once per rendered frame. */
  private void advance() {
    if (preview == null) {
      return;
    }
    long now = System.nanoTime();
    if (playing && !buffering) {
      playhead += (now - lastNanos) / 1e9;
      double next = edit.skipRemoved(playhead);
      if (next != playhead) {
        playhead = next;
        startPlayback();
      }
      if (playhead >= edit.end()) {
        playhead = edit.end();
        playing = false;
        preview.stop();
        refreshControls();
      }
    }
    lastNanos = now;
    if (pendingSeek != null && now - lastSeekNanos > SEEK_INTERVAL_NANOS) {
      preview.start(pendingSeek, !playing);
      pendingSeek = null;
      lastSeekNanos = now;
    }
    VideoPreview.Frame frame = preview.take(playing && !buffering ? playhead : Double.MAX_VALUE);
    if (frame == null) {
      return;
    }
    if (buffering) {
      buffering = false;
      lastNanos = now;
    }
    MemoryUtil.memByteBuffer(previewTexture.getPixels().getPointer(), frame.pixels().length).put(0, frame.pixels());
    previewTexture.upload();
    preview.recycle(frame);
  }

  private void togglePlay() {
    if (playing) {
      playing = false;
      preview.stop();
      refreshControls();
      return;
    }
    if (playhead >= edit.end() - 0.05 || playhead < edit.start()) {
      playhead = edit.start();
    }
    playhead = edit.skipRemoved(playhead);
    playing = true;
    startPlayback();
    refreshControls();
  }

  private void startPlayback() {
    buffering = true;
    pendingSeek = null;
    preview.start(playhead, false);
    lastSeekNanos = System.nanoTime();
  }

  private void seek(double time) {
    playhead = Math.max(0, Math.min(edit.length(), time));
    if (playing) {
      playhead = edit.skipRemoved(playhead);
      startPlayback();
      return;
    }
    pendingSeek = playhead;
  }

  private void editChanged() {
    if (playhead < edit.start() || playhead > edit.end()) {
      seek(Math.max(edit.start(), Math.min(playhead, edit.end() - 0.01)));
    }
    refreshControls();
  }

  private void refreshControls() {
    if (playButton == null) {
      return;
    }
    if (playing != playButtonShowsPause) {
      rebuildWidgets();
      return;
    }
    startSlider.sync();
    endSlider.sync();
    removeButton.active = edit.canRemoveAt(playhead);
    removeButton.setMessage(Component.translatable(edit.isRemoved(playhead) ? "ordersmp-clips.editor.keep" : "ordersmp-clips.editor.remove"));
    exportButton.active = !exporting;
    exportButton.setMessage(Component.translatable(exporting ? "ordersmp-clips.editor.exporting" : "ordersmp-clips.editor.export"));
  }

  private void done() {
    try {
      edit.save(clip.file());
    } catch (IOException e) {
      ClipsClient.log().warn("Could not keep the edit of {}", clip.file().getFileName(), e);
    }
    minecraft.gui.setScreen(parent);
  }

  private void export() {
    if (exporting) {
      return;
    }
    exporting = true;
    refreshControls();
    ClipEdit snapshot = edit.copy();
    recorder.withFfmpeg(ffmpeg -> {
      Path out = ClipExport.export(clip.file(), snapshot, ffmpeg);
      if (out != null) {
        ClipLibrary.thumbnail(out, ffmpeg);
      }
      return out;
    }).thenAccept(out -> minecraft.execute(() -> {
      exporting = false;
      refreshControls();
      if (out == null) {
        ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.toast.failed.title"),
            Component.translatable("ordersmp-clips.editor.export.failed"));
        return;
      }
      ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.toast.saved.title"),
          Component.literal(out.getFileName().toString()));
    }));
  }

  private void loadFilmstrip() {
    int requestedIn = generation;
    int seconds = (int) Math.ceil(clip.seconds());
    recorder.withFfmpeg(ffmpeg -> ClipLibrary.filmstrip(clip.file(), seconds, ffmpeg))
        .thenAccept(file -> minecraft.execute(() -> showFilmstrip(file, requestedIn)));
  }

  private void showFilmstrip(Path file, int requestedIn) {
    if (file == null || requestedIn != generation || filmstripLoaded) {
      return;
    }
    try (InputStream in = Files.newInputStream(file)) {
      minecraft.getTextureManager().register(FILMSTRIP, new DynamicTexture(() -> "Clips filmstrip", NativeImage.read(in)));
      filmstripLoaded = true;
      if (timeline != null) {
        timeline.setFilmstrip(FILMSTRIP);
      }
    } catch (IOException e) {
      ClipsClient.log().warn("Could not load the filmstrip {}", file.getFileName(), e);
    }
  }

  private static SpriteIconButton iconOnly(String key, Identifier sprite, Button.OnPress onPress) {
    return SpriteIconButton.builder(Component.translatable(key), onPress, true).width(20).sprite(sprite, 12, 12).withTootip().build();
  }

  private static SpriteIconButton textAndIcon(String key, Identifier sprite, int width, Button.OnPress onPress) {
    return SpriteIconButton.builder(Component.translatable(key), onPress, false).width(width).sprite(sprite, 12, 12).build();
  }

  private static Identifier icon(String name) {
    return Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "icon/" + name);
  }

  private static Identifier texture(String name) {
    return Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, name);
  }

  /** A tab whose controls sit in a grid with the editor's spacing. */
  private static final class EditorTab extends GridLayoutTab {

    private EditorTab(Component title) {
      super(title);
    }

    private GridLayout.RowHelper rows(int columns) {
      return layout.spacing(4).createRowHelper(columns);
    }
  }

  /** A slider over the clip's length that shows a time, such as {@code Start: 0:05}. */
  private final class TimeSlider extends AbstractSliderButton {

    private final String key;
    private final DoubleSupplier read;
    private final DoubleConsumer write;

    private TimeSlider(int width, String key, DoubleSupplier read, DoubleConsumer write) {
      super(0, 0, width, 20, Component.empty(), read.getAsDouble() / edit.length());
      this.key = key;
      this.read = read;
      this.write = write;
      updateMessage();
    }

    private void sync() {
      value = read.getAsDouble() / edit.length();
      updateMessage();
    }

    @Override
    protected void updateMessage() {
      setMessage(Component.translatable(key, ClipsScreen.duration((int) Math.round(read.getAsDouble()))));
    }

    @Override
    protected void applyValue() {
      write.accept(value * edit.length());
      sync();
    }
  }
}
