package lv.ordersmp.clips.ui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.Util;

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
  private static final Identifier REC = sprite("icon/rec");
  private static final Identifier REC_OFF = sprite("icon/rec_off");
  private static final Identifier FILM_STOP = sprite("icon/stop");
  private static final int WHITE = 0xFFFFFFFF;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int DARK_GRAY = 0xFF808080;
  private static final int RED = 0xFFFF5555;
  private static final int BLACK = 0xFF000000;
  private static final int THUMB_WIDTH = 56;
  private static final int THUMB_HEIGHT = 32;
  private static final int FOLDER_CHECK_TICKS = 20;
  private static final Pattern MOMENT = Pattern.compile("_(kill|death)-([A-Za-z0-9_]{1,16})$");
  private static final Pattern PART = Pattern.compile("-part([0-9]+)$");
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
  private boolean toggleShowsPaused;
  private boolean buttonShowsRecording;
  private Component status = Component.empty();
  private int statusColor = GRAY;
  private Component summary = Component.empty();
  private final Map<Path, Identifier> thumbnails = new HashMap<>();
  private final Set<Path> requested = new HashSet<>();
  private int generation;
  private int ticks;
  private long listedAt;

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
    watchButton = top.addChild(iconButton("ordersmp-clips.screen.watch", PLAY, 150, button -> editSelected()));
    top.addChild(iconButton("ordersmp-clips.screen.folder", FOLDER, 150, button -> openFolder()));
    LinearLayout bottom = footer.addChild(LinearLayout.horizontal().spacing(8));
    deleteButton = bottom.addChild(iconButton("ordersmp-clips.screen.delete", TRASH, 150, button -> confirmDelete()));
    bottom.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(150).build());

    layout.visitWidgets(this::addRenderableWidget);
    addRenderableWidget(recordToggle());
    addRenderableWidget(sessionButton());
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
    if (++ticks % FOLDER_CHECK_TICKS == 0 && folderChanged()) {
      reload();
      list.fill();
    }
    refresh();
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    graphics.centeredText(font, status, width / 2, titleWidget.getY() + 9 + 4, statusColor);
    graphics.text(font, summary, width - font.width(summary) - 8, 8, DARK_GRAY);
  }

  @Override
  public void removed() {
    generation++;
    thumbnails.values().forEach(id -> minecraft.getTextureManager().release(id));
    thumbnails.clear();
    requested.clear();
    clips = null;
    super.removed();
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  /**
   * Whether files were added or removed in the clips folder since the list was read, so a clip or
   * recording part that finishes while the screen is open shows up without reopening it.
   */
  private boolean folderChanged() {
    long modified = folderModified();
    return modified != listedAt;
  }

  private long folderModified() {
    try {
      return Files.getLastModifiedTime(recorder.clipsFolder()).toMillis();
    } catch (IOException e) {
      return 0;
    }
  }

  private void reload() {
    listedAt = folderModified();
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
    }
    if (watchButton != null && (toggleShowsPaused != (recorder.state() == Recorder.State.PAUSED)
        || buttonShowsRecording != recorder.isRecordingSession())) {
      rebuildWidgets();
      return;
    }
    statusColor = GRAY;
    status = switch (recorder.state()) {
      case RECORDING -> recorder.isRecordingSession()
          ? Component.translatable("ordersmp-clips.screen.status.session", duration(recorder.recordingSeconds()))
          : Component.translatable("ordersmp-clips.screen.status.recording", saveKey, duration(recorder.bufferSeconds()));
      case PAUSED -> Component.translatable("ordersmp-clips.screen.status.paused");
      case SAVING -> Component.translatable("ordersmp-clips.screen.status.saving");
      case STARTING -> Component.translatable("ordersmp-clips.screen.status.starting");
      case PROBLEM -> {
        statusColor = RED;
        yield Component.translatable("ordersmp-clips.screen.status.problem", String.valueOf(recorder.problem()));
      }
    };
  }

  /**
   * The Recording mode button beside the record switch: starts writing everything to disk, or
   * stops it. Its tooltip says which, and the header shows how long it has run.
   */
  private SpriteIconButton sessionButton() {
    buttonShowsRecording = recorder.isRecordingSession();
    String key = buttonShowsRecording ? "ordersmp-clips.screen.session.stop" : "ordersmp-clips.screen.session.start";
    SpriteIconButton button = SpriteIconButton.builder(Component.translatable(key), pressed -> {
          ClipsClient.toggleRecording(minecraft);
          rebuildWidgets();
        }, true)
        .width(20)
        .sprite(buttonShowsRecording ? FILM_STOP : CLIP, buttonShowsRecording ? 12 : 15, buttonShowsRecording ? 12 : 15)
        .withTootip()
        .build();
    button.setPosition(30, 8);
    button.active = recorder.state() == Recorder.State.RECORDING || recorder.state() == Recorder.State.SAVING || buttonShowsRecording;
    return button;
  }

  /**
   * The record switch in the top left corner: a red dot while recording, a hollow dot while paused.
   * Its look follows the recorder, so pausing with the key updates it too.
   */
  private SpriteIconButton recordToggle() {
    toggleShowsPaused = recorder.state() == Recorder.State.PAUSED;
    String key = toggleShowsPaused ? "ordersmp-clips.screen.resume" : "ordersmp-clips.screen.pause";
    SpriteIconButton toggle = SpriteIconButton.builder(Component.translatable(key), button -> {
          recorder.togglePause();
          rebuildWidgets();
        }, true)
        .width(20)
        .sprite(toggleShowsPaused ? REC_OFF : REC, 12, 12)
        .withTootip()
        .build();
    toggle.setPosition(8, 8);
    toggle.active = recorder.state() != Recorder.State.PROBLEM && recorder.state() != Recorder.State.STARTING;
    return toggle;
  }

  private void editSelected() {
    ClipEntry entry = list.getSelected();
    if (entry == null) {
      return;
    }
    edit(entry.clip);
  }

  private void edit(ClipLibrary.Clip clip) {
    if (clip.seconds() <= 0 || recorder.ffmpeg() == null) {
      Blaze3D.openPath(clip.file());
      return;
    }
    minecraft.gui.setScreen(new EditorScreen(this, recorder, clip));
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

  /** Asks for the clip's thumbnail once; it is made in the background and shown when ready. */
  private void requestThumbnail(Path clip) {
    if (!requested.add(clip)) {
      return;
    }
    int requestedIn = generation;
    recorder.thumbnail(clip).thenAccept(file -> minecraft.execute(() -> uploadThumbnail(clip, file, requestedIn)));
  }

  private void uploadThumbnail(Path clip, Path file, int requestedIn) {
    if (file == null || requestedIn != generation) {
      return;
    }
    Identifier id = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID,
        "thumbnails/" + Util.sanitizeName(file.getFileName().toString().toLowerCase(Locale.ROOT), Identifier::validPathChar));
    try (InputStream in = Files.newInputStream(file)) {
      NativeImage image = NativeImage.read(in);
      minecraft.getTextureManager().register(id, new DynamicTexture(() -> "Clip thumbnail " + id, image));
      thumbnails.put(clip, id);
    } catch (IOException e) {
      ClipsClient.log().warn("Could not load the thumbnail {}", file.getFileName(), e);
    }
  }

  private static SpriteIconButton iconButton(String key, Identifier icon, int width, Button.OnPress onPress) {
    return SpriteIconButton.builder(Component.translatable(key), onPress, false).width(width).sprite(icon, 12, 12).build();
  }

  private static Identifier sprite(String path) {
    return Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, path);
  }

  /** A clip's name for the player: when it was saved, and which part for Recording mode files. */
  static Component name(ClipLibrary.Clip clip) {
    Matcher moment = MOMENT.matcher(ClipLibrary.stem(clip.file()));
    if (moment.find()) {
      return Component.translatable("ordersmp-clips.screen." + moment.group(1), moment.group(2));
    }
    Component when = savedAt(clip);
    Matcher part = PART.matcher(ClipLibrary.stem(clip.file()));
    if (!part.find()) {
      return when;
    }
    return Component.translatable("ordersmp-clips.screen.part", when, part.group(1));
  }

  private static Component savedAt(ClipLibrary.Clip clip) {
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

  /** Seconds as {@code m:ss}, or {@code h:mm:ss} from an hour, for times shown to the player. */
  public static String duration(int seconds) {
    if (seconds >= 3600) {
      return seconds / 3600 + ":" + String.format(Locale.ROOT, "%02d:%02d", seconds / 60 % 60, seconds % 60);
    }
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
      ClipEntry selected = getSelected();
      Path keep = selected == null ? null : selected.clip.file();
      replaceEntries(clips.stream().map(ClipEntry::new).toList());
      children().stream().filter(entry -> entry.clip.file().equals(keep)).findFirst().ifPresent(this::setSelected);
      clips.forEach(clip -> requestThumbnail(clip.file()));
    }

    @Override
    public int getRowWidth() {
      return 308;
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
    private final ItemStack icon;

    private ClipEntry(ClipLibrary.Clip clip) {
      this.clip = clip;
      this.name = ClipsScreen.name(clip);
      String length = clip.seconds() < 0 ? "?" : duration((int) Math.round(clip.seconds()));
      Matcher moment = MOMENT.matcher(ClipLibrary.stem(clip.file()));
      boolean fight = moment.find();
      this.icon = !fight ? ItemStack.EMPTY : new ItemStack("kill".equals(moment.group(1)) ? Items.DIAMOND_SWORD : Items.SKELETON_SKULL);
      this.details = fight
          ? Component.translatable("ordersmp-clips.screen.details.moment", savedAt(clip), length, size(clip.bytes()))
          : Component.translatable("ordersmp-clips.screen.details", length, size(clip.bytes()));
    }

    @Override
    public Component getNarration() {
      return Component.translatable("narrator.select", name);
    }

    @Override
    public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
      int x = getContentX();
      int y = getContentY();
      Identifier thumbnail = thumbnails.get(clip.file());
      if (thumbnail == null) {
        graphics.fill(x, y, x + THUMB_WIDTH, y + THUMB_HEIGHT, BLACK);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CLIP, x + (THUMB_WIDTH - 15) / 2, y + (THUMB_HEIGHT - 15) / 2, 15, 15);
      } else {
        graphics.blit(RenderPipelines.GUI_TEXTURED, thumbnail, x, y, 0, 0, THUMB_WIDTH, THUMB_HEIGHT,
            ClipLibrary.THUMBNAIL_WIDTH, ClipLibrary.THUMBNAIL_HEIGHT, ClipLibrary.THUMBNAIL_WIDTH, ClipLibrary.THUMBNAIL_HEIGHT);
      }
      if (hovered) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PLAY, x + (THUMB_WIDTH - 12) / 2, y + (THUMB_HEIGHT - 12) / 2, 12, 12);
      }
      int textX = x + THUMB_WIDTH + 4;
      if (!icon.isEmpty()) {
        graphics.item(icon, textX, getContentY() - 3);
        textX += 18;
      }
      graphics.text(font, name, textX, getContentY() + 1, WHITE);
      graphics.text(font, details, textX, getContentY() + 9 + 3, DARK_GRAY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      list.setSelected(this);
      refresh();
      if (doubleClick) {
        edit(clip);
        return true;
      }
      return super.mouseClicked(event, doubleClick);
    }
  }
}
