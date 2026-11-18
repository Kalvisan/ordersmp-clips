package lv.ordersmp.clips.ui;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import lv.ordersmp.clips.ClipLibrary;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.fight.ClipEvents;
import lv.ordersmp.clips.edit.ClipEdit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The editor's timeline: a ruler, a video lane showing the clip's filmstrip, dark shading outside
 * the trim, red stripes over removed parts, red cut lines, the trim handles and the white
 * playhead. Clicking or dragging on the lane moves the playhead; dragging a handle trims.
 */
public final class TimelineWidget extends AbstractWidget {

  private static final Identifier HANDLE = Identifier.withDefaultNamespace("widget/slider_handle");
  private static final Identifier FIGHT_MARKER = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "hud/fight");
  private static final Identifier KILL_MARKER = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "hud/sword");
  private static final Identifier DEATH_MARKER = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "hud/skull");
  private static final Identifier HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
  private static final int LABEL_WIDTH = 44;
  private static final int RULER_HEIGHT = 11;
  private static final int HANDLE_WIDTH = 6;
  private static final int GRAB_DISTANCE = 4;
  private static final int LANE = 0xFF1E1E1E;
  private static final int OUTSIDE = 0xC0000000;
  private static final int REMOVED = 0xB0000000;
  private static final int STRIPE = 0xA0FF5555;
  private static final int CUT = 0xFFFF5555;
  private static final int PLAYHEAD = 0xFFFFFFFF;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int WHITE = 0xFFFFFFFF;
  private static final int ORANGE = 0xFFFFAA00;
  private static final int MARKER_SIZE = 9;

  private enum Drag { NONE, PLAYHEAD, START, END }

  private final ClipEdit edit;
  private final DoubleSupplier playhead;
  private final DoubleConsumer seek;
  private final Runnable changed;
  private Identifier filmstrip;
  private List<ClipEvents.Event> events = List.of();
  private Drag drag = Drag.NONE;

  public TimelineWidget(int x, int y, int width, int height, ClipEdit edit, DoubleSupplier playhead,
      DoubleConsumer seek, Runnable changed) {
    super(x, y, width, height, Component.translatable("ordersmp-clips.editor.timeline"));
    this.edit = edit;
    this.playhead = playhead;
    this.seek = seek;
    this.changed = changed;
  }

  /** Marks the clip's moments (first hit, kills, death) with an orange line and an icon. */
  public void setEvents(List<ClipEvents.Event> events) {
    this.events = events;
  }

  /** Shows the clip's filmstrip in the video lane once it is loaded. */
  public void setFilmstrip(Identifier filmstrip) {
    this.filmstrip = filmstrip;
  }

  @Override
  protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    Font font = Minecraft.getInstance().font;
    int laneX = laneX();
    int laneRight = getRight();
    int laneY = getY() + RULER_HEIGHT;
    int laneBottom = getBottom();

    graphics.text(font, ClipsScreen.duration(0), laneX, getY(), GRAY);
    String total = ClipsScreen.duration((int) Math.round(edit.length()));
    graphics.text(font, total, laneRight - font.width(total), getY(), GRAY);
    graphics.text(font, Component.translatable("ordersmp-clips.editor.lane.video"), getX(), laneY + (laneBottom - laneY - 8) / 2, WHITE);

    graphics.fill(laneX, laneY, laneRight, laneBottom, LANE);
    if (filmstrip != null) {
      drawFilmstrip(graphics, laneX, laneY, laneRight, laneBottom);
    }

    graphics.fill(laneX, laneY, toX(edit.start()), laneBottom, OUTSIDE);
    graphics.fill(toX(edit.end()), laneY, laneRight, laneBottom, OUTSIDE);
    for (ClipEdit.Part part : edit.removed()) {
      int from = toX(part.from());
      int to = toX(part.to());
      graphics.fill(from, laneY, to, laneBottom, REMOVED);
      for (int stripe = from + 2; stripe < to; stripe += 4) {
        graphics.fill(stripe, laneY, stripe + 1, laneBottom, STRIPE);
      }
    }
    for (double cut : edit.cuts()) {
      int x = toX(cut);
      graphics.fill(x, laneY, x + 1, laneBottom, CUT);
    }

    drawEvents(graphics, font, laneY, laneBottom, mouseX, mouseY);

    boolean overStart = drag == Drag.START || near(mouseX, mouseY, edit.start());
    boolean overEnd = drag == Drag.END || near(mouseX, mouseY, edit.end());
    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, overStart ? HANDLE_HIGHLIGHTED : HANDLE,
        toX(edit.start()) - HANDLE_WIDTH / 2, laneY, HANDLE_WIDTH, laneBottom - laneY);
    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, overEnd ? HANDLE_HIGHLIGHTED : HANDLE,
        toX(edit.end()) - HANDLE_WIDTH / 2, laneY, HANDLE_WIDTH, laneBottom - laneY);

    int head = toX(playhead.getAsDouble());
    graphics.fill(head, getY() + RULER_HEIGHT - 2, head + 1, laneBottom, PLAYHEAD);
  }

  /** An orange line through the lanes at each moment, its icon on the ruler, and a tooltip naming it. */
  private void drawEvents(GuiGraphicsExtractor graphics, Font font, int laneY, int laneBottom, int mouseX, int mouseY) {
    for (ClipEvents.Event event : events) {
      int x = toX(event.time());
      graphics.fill(x, laneY, x + 1, laneBottom, ORANGE);
      int iconX = x - MARKER_SIZE / 2;
      int iconY = getY() + 1;
      graphics.blitSprite(RenderPipelines.GUI_TEXTURED, markerIcon(event.type()), iconX, iconY, MARKER_SIZE, MARKER_SIZE);
      boolean over = mouseX >= iconX && mouseX < iconX + MARKER_SIZE && mouseY >= iconY && mouseY < iconY + MARKER_SIZE;
      if (over) {
        graphics.setTooltipForNextFrame(Component.translatable("ordersmp-clips.editor.marker." + event.type().name().toLowerCase(Locale.ROOT),
            String.valueOf(event.name()), ClipsScreen.duration((int) event.time())), mouseX, mouseY);
      }
    }
  }

  private static Identifier markerIcon(ClipEvents.Type type) {
    return switch (type) {
      case FIRST_HIT -> FIGHT_MARKER;
      case KILL -> KILL_MARKER;
      case DEATH -> DEATH_MARKER;
    };
  }

  /**
   * Fills the lane with frames at their own 16:9 shape, each showing the strip frame nearest its
   * place in time, so a wide screen shows more frames instead of stretched ones.
   */
  private void drawFilmstrip(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
    int height = bottom - top;
    int frameWidth = Math.max(8, height * 16 / 9);
    int stripWidth = ClipLibrary.FILMSTRIP_FRAMES * ClipLibrary.FILMSTRIP_FRAME_WIDTH;
    for (int x = left; x < right; x += frameWidth) {
      int width = Math.min(frameWidth, right - x);
      double middle = (x + frameWidth / 2.0 - left) / (right - left);
      int frame = Math.min(ClipLibrary.FILMSTRIP_FRAMES - 1, (int) (middle * ClipLibrary.FILMSTRIP_FRAMES));
      int sourceWidth = ClipLibrary.FILMSTRIP_FRAME_WIDTH * width / frameWidth;
      graphics.blit(RenderPipelines.GUI_TEXTURED, filmstrip, x, top, frame * ClipLibrary.FILMSTRIP_FRAME_WIDTH, 0, width, height,
          sourceWidth, ClipLibrary.FILMSTRIP_FRAME_HEIGHT, stripWidth, ClipLibrary.FILMSTRIP_FRAME_HEIGHT);
    }
  }

  @Override
  public void onClick(MouseButtonEvent event, boolean doubleClick) {
    if (near((int) event.x(), (int) event.y(), edit.start())) {
      drag = Drag.START;
      return;
    }
    if (near((int) event.x(), (int) event.y(), edit.end())) {
      drag = Drag.END;
      return;
    }
    drag = Drag.PLAYHEAD;
    seek.accept(toTime(event.x()));
  }

  @Override
  protected void onDrag(MouseButtonEvent event, double dx, double dy) {
    double time = toTime(event.x());
    switch (drag) {
      case START -> {
        edit.setStart(time);
        seek.accept(edit.start());
        changed.run();
      }
      case END -> {
        edit.setEnd(time);
        seek.accept(Math.max(edit.start(), edit.end() - 0.01));
        changed.run();
      }
      case PLAYHEAD -> seek.accept(time);
      case NONE -> {
      }
    }
  }

  @Override
  public void onRelease(MouseButtonEvent event) {
    drag = Drag.NONE;
  }

  @Override
  protected void updateWidgetNarration(NarrationElementOutput output) {
    output.add(NarratedElementType.TITLE, getMessage());
  }

  private boolean near(int mouseX, int mouseY, double time) {
    return mouseY >= getY() + RULER_HEIGHT && mouseY < getBottom() && Math.abs(mouseX - toX(time)) <= GRAB_DISTANCE;
  }

  private int laneX() {
    return getX() + LABEL_WIDTH;
  }

  private int toX(double time) {
    return laneX() + (int) Math.round(time / edit.length() * (getRight() - laneX()));
  }

  private double toTime(double x) {
    double fraction = (x - laneX()) / (getRight() - laneX());
    return Math.max(0, Math.min(edit.length(), fraction * edit.length()));
  }
}
