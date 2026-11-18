package lv.ordersmp.clips.ui;

import java.util.List;
import java.util.Locale;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.Recorder;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The small recording mark in the top-left corner, and the optional stats overlay below it. The
 * mark changes shape as well as colour (film strip while clipping, filled dot while recording
 * everything, hollow dot when paused), so its state does not depend on colour alone.
 */
public final class RecordingHud implements HudElement {

  private static final Identifier CLIP_ICON = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "hud/clip");
  private static final int CLIP_ICON_SIZE = 9;

  private static final int BACKGROUND = 0x80000000;
  private static final int RED = 0xFFFF3B3B;
  private static final int YELLOW = 0xFFFFFF55;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int WHITE = 0xFFFFFFFF;

  private final Recorder recorder;
  private final boolean visible;
  private boolean showStats;

  public RecordingHud(Recorder recorder, boolean visible) {
    this.recorder = recorder;
    this.visible = visible;
  }

  /** Shows or hides the stats overlay. */
  public void toggleStats() {
    showStats = !showStats;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
    Font font = Minecraft.getInstance().font;
    int bottom = 4;
    if (visible) {
      bottom = drawMark(graphics, font);
    }
    if (showStats) {
      drawStats(graphics, font, bottom + 2);
    }
  }

  private int drawMark(GuiGraphicsExtractor graphics, Font font) {
    Recorder.State state = recorder.state();
    Component label = state == Recorder.State.RECORDING && recorder.isRecordingSession()
        ? Component.translatable("ordersmp-clips.hud.recording", ClipsScreen.duration(recorder.recordingSeconds()))
        : Component.translatable(switch (state) {
      case RECORDING -> "ordersmp-clips.hud.clip";
      case PAUSED -> "ordersmp-clips.hud.paused";
      case SAVING -> "ordersmp-clips.hud.saving";
      case STARTING -> "ordersmp-clips.hud.starting";
      case PROBLEM -> "ordersmp-clips.hud.problem";
    });
    int color = switch (state) {
      case RECORDING -> RED;
      case SAVING, PROBLEM -> YELLOW;
      case PAUSED, STARTING -> GRAY;
    };
    boolean clipping = state == Recorder.State.RECORDING && !recorder.isRecordingSession();
    int iconWidth = clipping ? CLIP_ICON_SIZE : 5;
    int width = 4 + iconWidth + 3 + font.width(label) + 4;
    graphics.fill(4, 4, 4 + width, 17, BACKGROUND);
    if (clipping) {
      graphics.blitSprite(RenderPipelines.GUI_TEXTURED, CLIP_ICON, 8, 6, CLIP_ICON_SIZE, CLIP_ICON_SIZE);
    } else {
      drawDot(graphics, 8, 8, state, color);
    }
    graphics.text(font, label, 8 + iconWidth + 3, 6, state == Recorder.State.RECORDING ? WHITE : color);
    return 17;
  }

  private static void drawDot(GuiGraphicsExtractor graphics, int x, int y, Recorder.State state, int color) {
    if (state == Recorder.State.RECORDING) {
      graphics.fill(x, y, x + 5, y + 5, color);
      return;
    }
    if (state == Recorder.State.PROBLEM || state == Recorder.State.SAVING) {
      graphics.fill(x, y, x + 5, y + 5, color);
      graphics.fill(x + 1, y + 1, x + 4, y + 4, BACKGROUND);
      graphics.fill(x + 2, y + 2, x + 3, y + 3, color);
      return;
    }
    graphics.fill(x, y, x + 5, y + 1, color);
    graphics.fill(x, y + 4, x + 5, y + 5, color);
    graphics.fill(x, y, x + 1, y + 5, color);
    graphics.fill(x + 4, y, x + 5, y + 5, color);
  }

  private void drawStats(GuiGraphicsExtractor graphics, Font font, int top) {
    Recorder.Stats s = recorder.stats();
    String problem = recorder.problem();
    List<String> lines = List.of(
        String.format(Locale.ROOT, "Encoder: %s, game %d fps", s.encoder(), s.gameFps()),
        String.format(Locale.ROOT, "Video: %dx%d at %d fps", s.width(), s.height(), s.fps()),
        String.format(Locale.ROOT, "Held: %d s, %.1f MB", s.heldSeconds(), s.heldMegabytes()),
        String.format(Locale.ROOT, "Render cost: %.0f us avg, %.0f us max", s.renderMicros(), s.renderMaxMicros()),
        String.format(Locale.ROOT, "Frames: %d sent, %d repeated, %d skipped", s.sent(), s.repeated(), s.skipped()),
        problem == null ? "" : "Problem: " + problem);
    int y = top;
    for (String line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      graphics.fill(4, y, 8 + font.width(line), y + 10, BACKGROUND);
      graphics.text(font, line, 6, y + 1, WHITE);
      y += 10;
    }
  }
}
