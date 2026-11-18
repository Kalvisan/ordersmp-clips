package lv.ordersmp.clips.ui;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
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
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;

/**
 * The Clips screen, opened from the pause menu or its key. It follows the vanilla world list:
 * recording status, search, sort and a type filter in the header, saved clips in the middle,
 * actions below. MP4 files from elsewhere come in with Import or by dropping them on the window.
 */
public final class ClipsScreen extends Screen {

  /** What kind of clip a file is, read from its name. */
  enum Kind { KILL, DEATH, FIGHT, MANUAL, RECORDING }

  /** Which clips the list shows. */
  private enum Show { ALL, KILLS, DEATHS, FIGHTS, MANUAL, RECORDINGS, KEPT, IN_STUDIO }

  /** What the list is ordered by; the arrow button flips the direction. */
  private enum Sort { DATE, LENGTH, SIZE, TYPE }

  private static Show show = Show.ALL;
  private static Sort sort = Sort.DATE;
  private static boolean descending = true;

  private static final Identifier PLAY = sprite("icon/play");
  private static final Identifier TRASH = sprite("icon/trash");
  private static final Identifier CLIP = sprite("pause_menu/clips");
  private static final Identifier REC = sprite("icon/rec");
  private static final Identifier UPLOAD = sprite("icon/upload");
  private static final Identifier GEAR = sprite("icon/gear");
  private static final Identifier STAR = sprite("icon/star");
  private static final Identifier CLOCK = sprite("icon/clock");
  private static final Identifier SORT_DOWN = sprite("icon/sort_down");
  private static final Identifier SORT_UP = sprite("icon/sort_up");
  private static final int GOLD = 0xFFFFAA00;
  private static final int YELLOW = 0xFFFFFF55;
  private static final int SOON_DAYS = 3;
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
  private List<ClipLibrary.Clip> shown = List.of();
  private String search = "";
  private ClipList list;
  private Button keepButton;
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
    layout = new HeaderAndFooterLayout(this, 8 + 9 + 4 + 9 + 4 + 20 + 4, 60);
    LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
    header.defaultCellSetting().alignHorizontallyCenter();
    titleWidget = header.addChild(new StringWidget(title, font));
    header.addChild(new SpacerElement(1, 9));
    LinearLayout tools = header.addChild(LinearLayout.horizontal().spacing(4));
    EditBox searchBox = tools.addChild(new EditBox(font, 140, 20, Component.translatable("ordersmp-clips.screen.search")));
    searchBox.setHint(Component.translatable("ordersmp-clips.screen.search").withStyle(EditBox.SEARCH_HINT_STYLE));
    searchBox.setValue(search);
    searchBox.setResponder(text -> {
      search = text;
      list.fill();
    });
    tools.addChild(CycleButton.builder((Sort value) -> Component.translatable("ordersmp-clips.screen.sort." + value.name().toLowerCase(Locale.ROOT)), sort)
        .withValues(List.of(Sort.values()))
        .withTooltip(value -> Tooltip.create(Component.translatable("ordersmp-clips.screen.sort.tooltip")))
        .create(0, 0, 104, 20, Component.translatable("ordersmp-clips.screen.sort"), (button, value) -> {
          sort = value;
          list.fill();
        }));
    tools.addChild(SpriteIconButton.builder(Component.translatable(descending ? "ordersmp-clips.screen.sort.down" : "ordersmp-clips.screen.sort.up"),
            button -> {
              descending = !descending;
              rebuildWidgets();
            }, true)
        .width(20).sprite(descending ? SORT_DOWN : SORT_UP, 12, 12).withTootip().build());
    tools.addChild(CycleButton.builder((Show value) -> Component.translatable("ordersmp-clips.screen.show." + value.name().toLowerCase(Locale.ROOT)), show)
        .withValues(List.of(Show.values()))
        .withTooltip(value -> Tooltip.create(Component.translatable("ordersmp-clips.screen.show.tooltip")))
        .create(0, 0, 112, 20, Component.translatable("ordersmp-clips.screen.show"), (button, value) -> {
          show = value;
          list.fill();
        }));

    list = layout.addToContents(new ClipList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));

    LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
    footer.defaultCellSetting().alignHorizontallyCenter();
    LinearLayout top = footer.addChild(LinearLayout.horizontal().spacing(8));
    watchButton = top.addChild(iconButton("ordersmp-clips.screen.watch", PLAY, 194, button -> editSelected()));
    uploadButton = top.addChild(iconButton("ordersmp-clips.editor.studio", UPLOAD, 194, button -> uploadSelected()));
    LinearLayout bottom = footer.addChild(LinearLayout.horizontal().spacing(8));
    keepButton = bottom.addChild(iconButton("ordersmp-clips.screen.keep", STAR, 72, button -> toggleKept()));
    deleteButton = bottom.addChild(iconButton("ordersmp-clips.screen.delete", TRASH, 72, button -> confirmDelete()));
    bottom.addChild(Button.builder(Component.translatable("ordersmp-clips.screen.import"), button -> chooseFiles())
        .width(72)
        .tooltip(Tooltip.create(Component.translatable("ordersmp-clips.screen.import.tooltip")))
        .build());
    bottom.addChild(iconButton("ordersmp-clips.screen.options", GEAR, 72,
        button -> minecraft.gui.setScreen(new ClipsOptionsScreen(this, recorder))));
    bottom.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(72).build());

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

  /** MP4 files dropped on the window are imported like files picked with Import. */
  @Override
  public void onFilesDrop(List<Path> files) {
    importFiles(files);
  }

  /**
   * Opens the system's file picker for MP4 files. The picker answers on its own thread once the
   * player chose, so the import itself is handed back to the game thread.
   */
  private void chooseFiles() {
    ByteBuffer name = MemoryUtil.memUTF8("MP4 video");
    ByteBuffer pattern = MemoryUtil.memUTF8("mp4");
    SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.malloc(1);
    filters.get(0).name(name).pattern(pattern);
    SDL_DialogFileCallback[] holder = new SDL_DialogFileCallback[1];
    holder[0] = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
      List<Path> picked = pickedFiles(fileList);
      minecraft.execute(() -> {
        holder[0].free();
        filters.free();
        MemoryUtil.memFree(name);
        MemoryUtil.memFree(pattern);
        importFiles(picked);
      });
    });
    SDLDialog.SDL_ShowOpenFileDialog(holder[0], 0L, minecraft.getWindow().handle(), filters, (CharSequence) null, true);
  }

  /** The paths in the picker's null-terminated list; empty when the player cancelled or it failed. */
  private static List<Path> pickedFiles(long fileList) {
    List<Path> picked = new ArrayList<>();
    if (fileList == 0) {
      return picked;
    }
    for (int i = 0; ; i++) {
      long entry = MemoryUtil.memGetAddress(fileList + (long) i * Pointer.POINTER_SIZE);
      if (entry == 0) {
        return picked;
      }
      picked.add(Path.of(MemoryUtil.memUTF8(entry)));
    }
  }

  /**
   * Copies the MP4 files among {@code files} into the clips folder in the background and tells the
   * player how many came in. Files that are not MP4 are skipped and named in the toast.
   */
  private void importFiles(List<Path> files) {
    List<Path> videos = files.stream().filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp4")).toList();
    if (videos.isEmpty()) {
      if (!files.isEmpty()) {
        ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.toast.import.none"), null);
      }
      return;
    }
    Path folder = recorder.clipsFolder();
    Util.ioPool().execute(() -> {
      int imported = 0;
      for (Path video : videos) {
        try {
          ClipLibrary.importFile(video, folder);
          imported++;
        } catch (IOException e) {
          ClipsClient.log().warn("Could not import {}", video.getFileName(), e);
        }
      }
      int count = imported;
      minecraft.execute(() -> {
        ClipsClient.toast(minecraft, Component.translatable(count == videos.size() ? "ordersmp-clips.toast.import.done" : "ordersmp-clips.toast.import.some",
            count, videos.size()), null);
        if (clips != null) {
          reload();
          list.fill();
        }
      });
    });
  }

  /** Marks the selected clip Kept, or takes the mark away, so the storage limits skip it. */
  private void toggleKept() {
    ClipEntry entry = list.getSelected();
    if (entry == null) {
      return;
    }
    boolean kept = !ClipLibrary.isKept(entry.clip.file());
    try {
      ClipLibrary.setKept(entry.clip.file(), kept);
    } catch (IOException e) {
      ClipsClient.log().warn("Could not change Kept for {}", entry.clip.file().getFileName(), e);
      return;
    }
    entry.kept = kept;
    if (show == Show.KEPT) {
      list.fill();
    }
    refresh();
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
      keepButton.active = selected;
      boolean kept = selected && list.getSelected().kept;
      keepButton.setMessage(Component.translatable(kept ? "ordersmp-clips.screen.kept" : "ordersmp-clips.screen.keep"));
      keepButton.setTooltip(Tooltip.create(Component.translatable(kept ? "ordersmp-clips.screen.kept.tooltip" : "ordersmp-clips.screen.keep.tooltip")));
      refreshUploadButton();
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

  /**
   * The upload button follows the selected clip: Upload to Studio, Uploading with percent and Retry
   * Upload, or In Studio, which needs no button and is shown disabled.
   */
  private void refreshUploadButton() {
    ClipEntry entry = list.getSelected();
    if (entry == null) {
      uploadButton.active = false;
      uploadButton.setMessage(Component.translatable("ordersmp-clips.editor.studio"));
      return;
    }
    Studio studio = ClipsClient.studio();
    Studio.Job job = studio.job(entry.clip.file());
    if (job != null && (job.phase() == Studio.Phase.PREPARING || job.phase() == Studio.Phase.UPLOADING)) {
      uploadButton.active = false;
      uploadButton.setMessage(Component.translatable("ordersmp-clips.screen.uploading", (int) Math.floor(job.progress() * 100)));
      return;
    }
    if (job != null && job.phase() == Studio.Phase.FAILED) {
      uploadButton.active = true;
      uploadButton.setMessage(Component.translatable("ordersmp-clips.screen.retry").withStyle(ChatFormatting.YELLOW));
      return;
    }
    boolean inStudio = studio.uploadedUrl(entry.clip.file()) != null;
    uploadButton.active = !inStudio;
    uploadButton.setMessage(Component.translatable(inStudio ? "ordersmp-clips.screen.in_studio" : "ordersmp-clips.editor.studio"));
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
  public static String generatedTitle(ClipLibrary.Clip clip) {
    String when = TITLE_TIME.format(LocalDateTime.ofInstant(clip.saved(), ZoneId.systemDefault()));
    boolean fight = MOMENT.matcher(ClipLibrary.stem(clip.file())).find();
    return fight ? name(clip).getString() + ", " + when : "Minecraft clip, " + when;
  }

  /** Opens the clips folder in the system's file browser. */
  public static void openFolder(Path folder) {
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
    if (list != null) {
      list.fill();
    }
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

  /** What kind of clip {@code clip} is, from its file name; anything else counts as saved by hand. */
  static Kind kind(ClipLibrary.Clip clip) {
    String stem = ClipLibrary.stem(clip.file());
    Matcher moment = MOMENT.matcher(stem);
    if (moment.find()) {
      return switch (moment.group(1)) {
        case "kill" -> Kind.KILL;
        case "death" -> Kind.DEATH;
        default -> Kind.FIGHT;
      };
    }
    return PART.matcher(stem).find() ? Kind.RECORDING : Kind.MANUAL;
  }

  /** Whether {@code clip} belongs in the list for the chosen filter and search text. */
  private boolean matches(ClipLibrary.Clip clip, Kind kind) {
    boolean wanted = switch (show) {
      case ALL -> true;
      case KILLS -> kind == Kind.KILL;
      case DEATHS -> kind == Kind.DEATH;
      case FIGHTS -> kind == Kind.FIGHT;
      case MANUAL -> kind == Kind.MANUAL;
      case RECORDINGS -> kind == Kind.RECORDING;
      case KEPT -> ClipLibrary.isKept(clip.file());
      case IN_STUDIO -> ClipsClient.studio().uploadedUrl(clip.file()) != null;
    };
    if (!wanted) {
      return false;
    }
    String query = search.trim().toLowerCase(Locale.ROOT);
    if (query.isEmpty()) {
      return true;
    }
    String text = String.join(" ", name(clip).getString(), savedAt(clip).getString(),
        DAY.format(LocalDateTime.ofInstant(clip.saved(), ZoneId.systemDefault())),
        Component.translatable("ordersmp-clips.screen.show." + showFor(kind)).getString(),
        clip.file().getFileName().toString()).toLowerCase(Locale.ROOT);
    for (String word : query.split("\\s+")) {
      if (!text.contains(word)) {
        return false;
      }
    }
    return true;
  }

  private static String showFor(Kind kind) {
    return switch (kind) {
      case KILL -> "kills";
      case DEATH -> "deaths";
      case FIGHT -> "fights";
      case MANUAL -> "manual";
      case RECORDING -> "recordings";
    };
  }

  /** The clips the list shows: filtered by type and search, ordered by the chosen sort. */
  private List<ClipLibrary.Clip> visibleClips() {
    Comparator<ClipLibrary.Clip> order = switch (sort) {
      case DATE -> Comparator.comparing(ClipLibrary.Clip::saved);
      case LENGTH -> Comparator.comparingDouble(ClipLibrary.Clip::seconds);
      case SIZE -> Comparator.comparingLong(ClipLibrary.Clip::bytes);
      case TYPE -> Comparator.<ClipLibrary.Clip, Kind>comparing(ClipsScreen::kind).thenComparing(ClipLibrary.Clip::saved);
    };
    return clips.stream()
        .filter(clip -> matches(clip, kind(clip)))
        .sorted(descending ? order.reversed() : order)
        .toList();
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

  /** The list of saved clips, in the order and filter chosen above it. */
  private final class ClipList extends ObjectSelectionList<ClipEntry> {

    private ClipList(Minecraft minecraft, int width, int height, int y) {
      super(minecraft, width, height, y, 36);
    }

    private void fill() {
      ClipEntry selected = getSelected();
      Path keep = selected == null ? null : selected.clip.file();
      shown = visibleClips();
      replaceEntries(shown.stream().map(ClipEntry::new).toList());
      children().stream().filter(entry -> entry.clip.file().equals(keep)).findFirst().ifPresent(this::setSelected);
      shown.forEach(clip -> requestThumbnail(clip.file()));
      setScrollAmount(scrollAmount());
      refresh();
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
      if (!clips.isEmpty()) {
        graphics.centeredText(font, Component.translatable("ordersmp-clips.screen.no_match"), getX() + getWidth() / 2, getY() + getHeight() / 2 - 4, GRAY);
        return;
      }
      Component empty = Component.translatable("ordersmp-clips.screen.empty", saveKey, duration(recorder.bufferSeconds()));
      Component more = Component.translatable("ordersmp-clips.screen.empty.import");
      graphics.centeredText(font, empty, getX() + getWidth() / 2, getY() + getHeight() / 2 - 10, GRAY);
      graphics.centeredText(font, more, getX() + getWidth() / 2, getY() + getHeight() / 2 + 2, DARK_GRAY);
    }
  }

  /** One clip: when it was saved, how long it is and how big. Double-click watches it. */
  private final class ClipEntry extends ObjectSelectionList.Entry<ClipEntry> {

    private final ClipLibrary.Clip clip;
    private final Component name;
    private final Component details;
    private final Identifier icon;
    private boolean kept;

    private ClipEntry(ClipLibrary.Clip clip) {
      this.clip = clip;
      this.kept = ClipLibrary.isKept(clip.file());
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
      int next = drawKept(graphics, textX, getContentY() + 9 + 9 + 5);
      drawStudioState(graphics, next, getContentY() + 9 + 9 + 5);
    }

    /**
     * Gold star Kept, or a clock with the days until the storage limit deletes the clip (yellow in
     * its last days); nothing when no day limit is set. Returns where the next text may start.
     */
    private int drawKept(GuiGraphicsExtractor graphics, int x, int y) {
      if (kept) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, STAR, x - 1, y - 2, 12, 12);
        Component text = Component.translatable("ordersmp-clips.screen.kept");
        graphics.text(font, text, x + 12, y, GOLD);
        return x + 12 + font.width(text) + 8;
      }
      int left = ClipLibrary.daysLeft(clip, ClipsClient.settings().deleteAfterDays(), Instant.now());
      if (left < 0) {
        return x;
      }
      Component text = left == 0
          ? Component.translatable("ordersmp-clips.screen.days_left.today")
          : Component.translatable("ordersmp-clips.screen.days_left", left);
      graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CLOCK, x - 1, y - 2, 12, 12);
      graphics.text(font, text, x + 12, y, left <= SOON_DAYS ? YELLOW : DARK_GRAY);
      return x + 12 + font.width(text) + 8;
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
