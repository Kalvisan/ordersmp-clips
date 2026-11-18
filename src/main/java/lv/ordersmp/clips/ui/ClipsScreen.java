package lv.ordersmp.clips.ui;

import com.mojang.blaze3d.Blaze3D;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import lv.ordersmp.clips.ClipLibrary;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The Clips screen, opened from the pause menu or its key. It follows the vanilla world list:
 * recording status in the header, saved clips in the middle, actions below. Clips play in the
 * system's video player, so the mod needs no player of its own yet.
 */
public final class ClipsScreen extends Screen {

  private static final Identifier PLAY = sprite("icon/play");
  private static final Identifier FOLDER = sprite("icon/folder");
  private static final Identifier TRASH = sprite("icon/trash");
  private static final Identifier CLIP = sprite("pause_menu/clips");
  private static final int WHITE = 0xFFFFFFFF;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int DARK_GRAY = 0xFF808080;
  private static final int RED = 0xFFFF5555;
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

  private final Screen parent;
  private final Recorder recorder;
  private final Component saveKey;
  private HeaderAndFooterLayout layout;
  private List<ClipLibrary.Clip> clips;
  private ClipList list;
  private StringWidget titleWidget;
  private Button watchButton;
  private Button deleteButton;
  private Button pauseButton;
  private Component status = Component.empty();
  private int statusColor = GRAY;
  private Component summary = Component.empty();

  public ClipsScreen(Screen parent, Recorder recorder, Component saveKey) {
    super(Component.translatable("ordersmp-clips.screen.title"));
    this.parent = parent;
    this.recorder = recorder;
    this.saveKey = saveKey;
  }

  @Override
  protected void init() {
    if (clips == null) {
      reload();
    }
    layout = new HeaderAndFooterLayout(this, 8 + 9 + 4 + 9 + 8, 60);
    LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
    header.defaultCellSetting().alignHorizontallyCenter();
    titleWidget = header.addChild(new StringWidget(title, font));
    header.addChild(new SpacerElement(1, 9));

    list = layout.addToContents(new ClipList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));

    LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
    footer.defaultCellSetting().alignHorizontallyCenter();
    LinearLayout top = footer.addChild(LinearLayout.horizontal().spacing(8));
    watchButton = top.addChild(iconButton("ordersmp-clips.screen.watch", PLAY, 150, button -> watchSelected()));
    top.addChild(iconButton("ordersmp-clips.screen.folder", FOLDER, 150, button -> openFolder()));
    LinearLayout bottom = footer.addChild(LinearLayout.horizontal().spacing(4));
    deleteButton = bottom.addChild(iconButton("ordersmp-clips.screen.delete", TRASH, 100, button -> confirmDelete()));
    pauseButton = bottom.addChild(Button.builder(pauseLabel(), button -> recorder.togglePause()).width(100).build());
    bottom.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(100).build());

    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
    list.fill();
    refresh();
  }

  @Override
  protected void repositionElements() {
    if (list != null) {
      list.updateSize(width, layout);
    }
    layout.arrangeElements();
  }

  @Override
  public void tick() {
    refresh();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    graphics.centeredText(font, status, width / 2, titleWidget.getY() + 9 + 4, statusColor);
    graphics.text(font, summary, width - font.width(summary) - 8, 8, DARK_GRAY);
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  private void reload() {
    try {
      clips = ClipLibrary.list(recorder.clipsFolder());
    } catch (IOException e) {
      ClipsClient.log().warn("Could not read the clips folder", e);
      clips = List.of();
    }
    long bytes = clips.stream().mapToLong(ClipLibrary.Clip::bytes).sum();
    summary = Component.translatable("ordersmp-clips.screen.summary", clips.size(), size(bytes));
  }

  private void refresh() {
    boolean selected = list != null && list.getSelected() != null;
    if (watchButton != null) {
      watchButton.active = selected;
      deleteButton.active = selected;
      pauseButton.setMessage(pauseLabel());
      pauseButton.active = recorder.state() != Recorder.State.PROBLEM && recorder.state() != Recorder.State.STARTING;
    }
    statusColor = GRAY;
    status = switch (recorder.state()) {
      case RECORDING -> Component.translatable("ordersmp-clips.screen.status.recording", saveKey, duration(recorder.bufferSeconds()));
      case PAUSED -> Component.translatable("ordersmp-clips.screen.status.paused");
      case SAVING -> Component.translatable("ordersmp-clips.screen.status.saving");
      case STARTING -> Component.translatable("ordersmp-clips.screen.status.starting");
      case PROBLEM -> {
        statusColor = RED;
        yield Component.translatable("ordersmp-clips.screen.status.problem", String.valueOf(recorder.problem()));
      }
    };
  }

  private Component pauseLabel() {
    return Component.translatable(recorder.state() == Recorder.State.PAUSED
        ? "ordersmp-clips.screen.resume" : "ordersmp-clips.screen.pause");
  }

  private void watchSelected() {
    ClipEntry entry = list.getSelected();
    if (entry == null) {
      return;
    }
    Blaze3D.openPath(entry.clip.file());
  }

  private void openFolder() {
    try {
      Files.createDirectories(recorder.clipsFolder());
    } catch (IOException e) {
      ClipsClient.log().warn("Could not create the clips folder", e);
      return;
    }
    Blaze3D.openPath(recorder.clipsFolder());
  }

  private void confirmDelete() {
    ClipEntry entry = list.getSelected();
    if (entry == null) {
      return;
    }
    ClipLibrary.Clip clip = entry.clip;
    minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
      if (confirmed) {
        delete(clip);
      }
      minecraft.gui.setScreen(this);
    }, Component.translatable("ordersmp-clips.screen.delete.title"),
        Component.translatable("ordersmp-clips.screen.delete.body", name(clip)),
        Component.translatable("ordersmp-clips.screen.delete"), CommonComponents.GUI_CANCEL));
  }

  private void delete(ClipLibrary.Clip clip) {
    try {
      ClipLibrary.delete(clip);
    } catch (IOException e) {
      ClipsClient.log().warn("Could not delete {}", clip.file().getFileName(), e);
    }
    reload();
  }

  private static SpriteIconButton iconButton(String key, Identifier icon, int width, Button.OnPress onPress) {
    return SpriteIconButton.builder(Component.translatable(key), onPress, false).width(width).sprite(icon, 12, 12).build();
  }

  private static Identifier sprite(String path) {
    return Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, path);
  }

  private static Component name(ClipLibrary.Clip clip) {
    LocalDateTime saved = LocalDateTime.ofInstant(clip.saved(), ZoneId.systemDefault());
    LocalDate today = LocalDate.now();
    String time = TIME.format(saved);
    if (saved.toLocalDate().equals(today)) {
      return Component.translatable("ordersmp-clips.screen.today", time);
    }
    if (saved.toLocalDate().equals(today.minusDays(1))) {
      return Component.translatable("ordersmp-clips.screen.yesterday", time);
    }
    return Component.literal(DAY.format(saved) + " " + time);
  }

  private static String duration(int seconds) {
    return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
  }

  private static String size(long bytes) {
    double megabytes = bytes / 1_048_576.0;
    if (megabytes >= 1024) {
      return String.format(Locale.ROOT, "%.1f GB", megabytes / 1024);
    }
    if (megabytes < 10) {
      return String.format(Locale.ROOT, "%.1f MB", megabytes);
    }
    return String.format(Locale.ROOT, "%.0f MB", megabytes);
  }

  /** The list of saved clips, newest first. */
  private final class ClipList extends ObjectSelectionList<ClipEntry> {

    private ClipList(Minecraft minecraft, int width, int height, int y) {
      super(minecraft, width, height, y, 36);
    }

    private void fill() {
      replaceEntries(clips.stream().map(ClipEntry::new).toList());
    }

    @Override
    public int getRowWidth() {
      return 270;
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
      super.extractWidgetRenderState(graphics, mouseX, mouseY, a);
      if (!children().isEmpty()) {
        return;
      }
      Component empty = Component.translatable("ordersmp-clips.screen.empty", saveKey, duration(recorder.bufferSeconds()));
      graphics.centeredText(font, empty, getX() + getWidth() / 2, getY() + getHeight() / 2 - 4, GRAY);
    }
  }

  /** One clip: when it was saved, how long it is and how big. Double-click watches it. */
  private final class ClipEntry extends ObjectSelectionList.Entry<ClipEntry> {

    private final ClipLibrary.Clip clip;
    private final Component name;
    private final Component details;

    private ClipEntry(ClipLibrary.Clip clip) {
      this.clip = clip;
      this.name = ClipsScreen.name(clip);
      String length = clip.seconds() < 0 ? "?" : duration(clip.seconds());
      this.details = Component.translatable("ordersmp-clips.screen.details", length, size(clip.bytes()));
    }

    @Override
    public Component getNarration() {
      return Component.translatable("narrator.select", name);
    }

    @Override
    public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
      int iconX = getContentX() + 8;
      graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CLIP, iconX, getContentYMiddle() - 7, 15, 15);
      int textX = getContentX() + 32 + 3;
      graphics.text(font, name, textX, getContentY() + 1, WHITE);
      graphics.text(font, details, textX, getContentY() + 9 + 3, DARK_GRAY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      list.setSelected(this);
      refresh();
      if (doubleClick) {
        Blaze3D.openPath(clip.file());
        return true;
      }
      return super.mouseClicked(event, doubleClick);
    }
  }
}
