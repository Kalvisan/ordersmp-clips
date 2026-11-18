package lv.ordersmp.clips.ui;

import java.util.List;
import java.util.Locale;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.Recorder;
import lv.ordersmp.clips.fight.FightTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The small recording mark in the top-left corner, and the optional stats overlay below it. The
 * mark changes shape as well as colour (a round red dot with REC while clipping, crossed swords
 * in a fight, a sword after a kill, the red dot with LIVE while recording everything, a hollow
 * ring when paused), so its state does not depend on colour alone.
 */
public final class RecordingHud {

  private static final Identifier FIGHT_ICON = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "hud/fight");
  private static final Identifier KILL_ICON = Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "hud/sword");
  private static final int CLIP_ICON_SIZE = 9;
  private static final int DOT = 7;

  private static final int BACKGROUND = 0x80000000;
  private static final int RED = 0xFFFF3B3B;
  private static final int YELLOW = 0xFFFFFF55;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int WHITE = 0xFFFFFFFF;

  private final Recorder recorder;
  private boolean visible;
  private boolean showStats;
  private boolean minimal;

  public RecordingHud(Recorder recorder, boolean visible) {
    this.recorder = recorder;
    this.visible = visible;
  }

  /** Shows or hides the recording mark. */
  public void setVisible(boolean visible) {
    this.visible = visible;
  }

  /** Draws only the mark's icon, without the text beside it. */
  public void setMinimal(boolean minimal) {
    this.minimal = minimal;
  }

  /** Shows or hides the stats overlay. */
  public void toggleStats() {
    showStats = !showStats;
  }

  /** Draws the mark and, when turned on, the stats into {@code graphics}. */
  public void draw(GuiGraphicsExtractor graphics) {
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
    Component label = switch (state) {
      case RECORDING -> recorder.isRecordingSession()
          ? Component.translatable("ordersmp-clips.hud.recording", ClipsScreen.duration(recorder.recordingSeconds()))
          : Component.translatable("ordersmp-clips.hud.clip");
      case PAUSED -> Component.translatable("ordersmp-clips.hud.paused");
      case SAVING -> Component.translatable("ordersmp-clips.hud.saving");
      case STARTING -> Component.translatable("ordersmp-clips.hud.starting");
      case PROBLEM -> Component.translatable("ordersmp-clips.hud.problem");
    };
    int color = switch (state) {
      case RECORDING -> RED;
      case SAVING, PROBLEM -> YELLOW;
      case PAUSED, STARTING -> GRAY;
    };
    boolean clipping = state == Recorder.State.RECORDING && !recorder.isRecordingSession();
    Component fight = clipping ? fightInfo() : null;
    if (fight != null) {
      label = fight;
    }
    boolean iconOnly = minimal;
    int iconWidth = fight != null ? CLIP_ICON_SIZE : DOT;
    int width = iconOnly ? 4 + iconWidth + 4 : 4 + iconWidth + 3 + font.width(label) + 4;
    graphics.fill(4, 4, 4 + width, 17, BACKGROUND);
    if (fight != null) {
      graphics.blitSprite(RenderPipelines.GUI_TEXTURED, fightIcon(), 8, 6, CLIP_ICON_SIZE, CLIP_ICON_SIZE);
    } else {
      drawDot(graphics, 8, 6, state, color);
    }
    if (iconOnly) {
      return 17;
    }
    graphics.text(font, label, 8 + iconWidth + 3, 6, fight != null ? YELLOW : state == Recorder.State.RECORDING ? WHITE : color);
    return 17;
  }

  /** The icon while a fight is followed: crossed swords, then a sword once there is a kill. */
  private static Identifier fightIcon() {
    FightTracker fights = ClipsClient.fights();
    return fights != null && fights.kills() > 0 ? KILL_ICON : FIGHT_ICON;
  }

  /** While a fight is being followed: how long it has run, or that a kill is waiting to be saved. */
  private static Component fightInfo() {
    FightTracker fights = ClipsClient.fights();
    if (fights == null) {
      return null;
    }
    int seconds = fights.fightSeconds();
    if (seconds < 0) {
      return null;
    }
    if (fights.kills() > 0) {
      return Component.translatable("ordersmp-clips.hud.kill", fights.kills());
    }
    return Component.translatable("ordersmp-clips.hud.fight", ClipsScreen.duration(seconds));
  }

  /**
   * A round 7 pixel dot at {@code x, y}: filled while recording, a hollow ring when paused or
   * starting, and a ring with a centre point while saving or when something is wrong.
   */
  private static void drawDot(GuiGraphicsExtractor graphics, int x, int y, Recorder.State state, int color) {
    if (state == Recorder.State.RECORDING) {
      graphics.fill(x + 2, y, x + 5, y + 1, color);
      graphics.fill(x + 1, y + 1, x + 6, y + 2, color);
      graphics.fill(x, y + 2, x + 7, y + 5, color);
      graphics.fill(x + 1, y + 5, x + 6, y + 6, color);
      graphics.fill(x + 2, y + 6, x + 5, y + 7, color);
      return;
    }
    graphics.fill(x + 2, y, x + 5, y + 1, color);
    graphics.fill(x + 2, y + 6, x + 5, y + 7, color);
    graphics.fill(x, y + 2, x + 1, y + 5, color);
    graphics.fill(x + 6, y + 2, x + 7, y + 5, color);
    graphics.fill(x + 1, y + 1, x + 2, y + 2, color);
    graphics.fill(x + 5, y + 1, x + 6, y + 2, color);
    graphics.fill(x + 1, y + 5, x + 2, y + 6, color);
    graphics.fill(x + 5, y + 5, x + 6, y + 6, color);
    if (state == Recorder.State.PROBLEM || state == Recorder.State.SAVING) {
      graphics.fill(x + 3, y + 3, x + 4, y + 4, color);
    }
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
