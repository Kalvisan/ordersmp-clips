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
import lv.ordersmp.clips.edit.ClipEdit;
import lv.ordersmp.clips.studio.Studio;
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
import net.minecraft.util.Util;

/**
 * The Clips screen, opened from the pause menu or its key. It follows the vanilla world list:
 * recording status in the header, saved clips in the middle, actions below. Clips play in the
 * system's video player, so the mod needs no player of its own yet.
 */
public final class ClipsScreen extends Screen {

  private static final Identifier PLAY = sprite("icon/play");
  private static final Identifier TRASH = sprite("icon/trash");
  private static final Identifier CLIP = sprite("pause_menu/clips");
  private static final Identifier REC = sprite("icon/rec");
  private static final Identifier UPLOAD = sprite("icon/upload");
  private static final Identifier GEAR = sprite("icon/gear");
  private static final Identifier SWORD = sprite("hud/sword");
  private static final Identifier SKULL = sprite("hud/skull");
  private static final Identifier FIGHT = sprite("hud/fight");
  private static final Identifier FILM = sprite("hud/clip");
  private static final Identifier CHECK = sprite("hud/check");
  private static final Identifier CROSS = sprite("hud/cross");
  private static final int GREEN = 0xFF55FF55;
  private static final int AQUA = 0xFF55FFFF;
  private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
  private static final int WHITE = 0xFFFFFFFF;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int DARK_GRAY = 0xFF808080;
  private static final int RED = 0xFFFF5555;
  private static final int BLACK = 0xFF000000;
  private static final int THUMB_WIDTH = 56;
  private static final int THUMB_HEIGHT = 32;
  private static final int FOLDER_CHECK_TICKS = 20;
  private static final Pattern MOMENT = Pattern.compile("_(kill|death|fight)-([A-Za-z0-9_]{1,16})$");
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
  private Button uploadButton;
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
    watchButton = top.addChild(iconButton("ordersmp-clips.screen.watch", PLAY, 151, button -> editSelected()));
    uploadButton = top.addChild(iconButton("ordersmp-clips.editor.studio", UPLOAD, 151, button -> uploadSelected()));
    LinearLayout bottom = footer.addChild(LinearLayout.horizontal().spacing(8));
    deleteButton = bottom.addChild(iconButton("ordersmp-clips.screen.delete", TRASH, 98, button -> confirmDelete()));
    bottom.addChild(iconButton("ordersmp-clips.screen.options", GEAR, 98,
        button -> minecraft.gui.setScreen(new ClipsOptionsScreen(this, recorder))));
    bottom.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(98).build());

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
      uploadButton.active = selected;
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

  /** Uploads the selected clip with its kept edit and title, linking Studio first when needed. */
  private void uploadSelected() {
    ClipEntry entry = list.getSelected();
    if (entry == null) {
      return;
    }
    ClipLibrary.Clip clip = entry.clip;
    Studio studio = ClipsClient.studio();
    if (!studio.isLinked()) {
      minecraft.gui.setScreen(new StudioLinkScreen(this, studio, () -> upload(clip)));
      return;
    }
    upload(clip);
  }

  private void upload(ClipLibrary.Clip clip) {
    ClipEdit edit = ClipEdit.load(clip.file(), clip.seconds());
    String title = edit.title() != null ? edit.title() : generatedTitle(clip);
    ClipsClient.studio().upload(clip, edit, title, recorder, ClipsClient::announceUpload);
  }

  /** A title made from the clip, such as {@code Kill - Raven_77, 18 Nov 21:14}. */
  static String generatedTitle(ClipLibrary.Clip clip) {
    String when = TITLE_TIME.format(LocalDateTime.ofInstant(clip.saved(), ZoneId.systemDefault()));
    boolean fight = MOMENT.matcher(ClipLibrary.stem(clip.file())).find();
    return fight ? name(clip).getString() + ", " + when : "Minecraft clip, " + when;
  }

  /** Opens the clips folder in the system's file browser. */
  static void openFolder(Path folder) {
    try {
      Files.createDirectories(folder);
    } catch (IOException e) {
      ClipsClient.log().warn("Could not create the clips folder", e);
      return;
    }
    Blaze3D.openPath(folder);
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

  /**
   * Deletes a clip and says so. On Windows a file can stay locked for a moment after its video
   * player or preview closed, so a failed delete is tried once more before the player is told.
   */
  private void delete(ClipLibrary.Clip clip) {
    Component name = name(clip);
    IOException failure = null;
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        ClipLibrary.delete(clip);
        failure = null;
        break;
      } catch (IOException e) {
        failure = e;
        try {
          Thread.sleep(250);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    reload();
    if (failure != null) {
      ClipsClient.log().warn("Could not delete {}", clip.file().getFileName(), failure);
      ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.toast.delete.failed"), name);
      return;
    }
    ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.toast.deleted"), name);
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
    private final Identifier icon;

    private ClipEntry(ClipLibrary.Clip clip) {
      this.clip = clip;
      this.name = ClipsScreen.name(clip);
      String length = clip.seconds() < 0 ? "?" : duration((int) Math.round(clip.seconds()));
      Matcher moment = MOMENT.matcher(ClipLibrary.stem(clip.file()));
      boolean fight = moment.find();
      this.icon = !fight ? (PART.matcher(ClipLibrary.stem(clip.file())).find() ? REC : FILM) : switch (moment.group(1)) {
        case "kill" -> SWORD;
        case "death" -> SKULL;
        default -> FIGHT;
      };
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
      graphics.blitSprite(RenderPipelines.GUI_TEXTURED, icon, textX, getContentY(), 9, 9);
      graphics.text(font, name, textX + 12, getContentY() + 1, WHITE);
      graphics.text(font, details, textX, getContentY() + 9 + 3, DARK_GRAY);
      drawStudioState(graphics, textX, getContentY() + 9 + 9 + 5);
    }

    /** The clip's place in Studio: uploading with percent, in Studio, or not uploaded with the reason. */
    private void drawStudioState(GuiGraphicsExtractor graphics, int x, int y) {
      Studio studio = ClipsClient.studio();
      Studio.Job job = studio.job(clip.file());
      if (job != null && (job.phase() == Studio.Phase.PREPARING || job.phase() == Studio.Phase.UPLOADING)) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, UPLOAD, x - 1, y - 2, 12, 12);
        Component progress = Component.translatable("ordersmp-clips.editor.upload.progress", (int) Math.floor(job.progress() * 100));
        graphics.text(font, progress, x + 12, y, AQUA);
        return;
      }
      if (job != null && job.phase() == Studio.Phase.FAILED) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CROSS, x, y, 9, 9);
        Component reason = Component.translatable("ordersmp-clips.editor.upload.failed", String.valueOf(job.problem()));
        graphics.text(font, reason, x + 12, y, RED);
        return;
      }
      if (studio.uploadedUrl(clip.file()) != null) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CHECK, x, y, 9, 9);
        graphics.text(font, Component.translatable("ordersmp-clips.screen.in_studio"), x + 12, y, GREEN);
      }
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
