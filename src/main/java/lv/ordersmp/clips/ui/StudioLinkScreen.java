package lv.ordersmp.clips.ui;

import com.mojang.blaze3d.Blaze3D;
import java.net.URI;
import java.util.concurrent.CompletionException;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.studio.Studio;
import lv.ordersmp.clips.studio.StudioClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Links this PC to the player's Studio account. Studio gives a short code; the player opens the
 * Studio page (the code is already filled in) and presses Approve, or types the code there from
 * any device. The screen polls in the background and closes by itself once Studio approves.
 */
public final class StudioLinkScreen extends Screen {

  private static final int WHITE = 0xFFFFFFFF;
  private static final int GRAY = 0xFFA0A0A0;
  private static final int YELLOW = 0xFFFFFF55;
  private static final int RED = 0xFFFF5555;

  private final Screen parent;
  private final Studio studio;
  private final Runnable onLinked;
  private volatile StudioClient.Link link;
  private volatile Component problem;
  private volatile boolean closed;
  private boolean started;
  private Button openButton;
  private Button againButton;

  public StudioLinkScreen(Screen parent, Studio studio, Runnable onLinked) {
    super(Component.translatable("ordersmp-clips.link.title"));
    this.parent = parent;
    this.studio = studio;
    this.onLinked = onLinked;
  }

  @Override
  protected void init() {
    closed = false;
    if (!started) {
      start();
    }
    int y = height / 2 + 30;
    openButton = addRenderableWidget(Button.builder(Component.translatable("ordersmp-clips.link.open"), button -> openBrowser())
        .bounds(width / 2 - 154, y, 150, 20).build());
    againButton = addRenderableWidget(Button.builder(Component.translatable("ordersmp-clips.link.again"), button -> start())
        .bounds(width / 2 - 154, y, 150, 20).build());
    addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
        .bounds(width / 2 + 4, y, 150, 20).build());
    tick();
  }

  @Override
  public void tick() {
    if (openButton == null) {
      return;
    }
    openButton.visible = problem == null;
    openButton.active = link != null;
    againButton.visible = problem != null;
  }

  @Override
  public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
    super.extractRenderState(graphics, mouseX, mouseY, a);
    int center = width / 2;
    int y = height / 2 - 50;
    graphics.centeredText(font, title, center, y, WHITE);
    graphics.centeredText(font, Component.translatable("ordersmp-clips.link.body"), center, y + 20, GRAY);
    graphics.centeredText(font, Component.translatable("ordersmp-clips.link.drafts"), center, y + 32, GRAY);
    StudioClient.Link current = link;
    if (problem != null) {
      graphics.centeredText(font, problem, center, y + 56, RED);
      return;
    }
    if (current == null) {
      graphics.centeredText(font, Component.translatable("ordersmp-clips.link.starting"), center, y + 56, GRAY);
      return;
    }
    graphics.centeredText(font, Component.translatable("ordersmp-clips.link.code", current.userCode()), center, y + 52, YELLOW);
    graphics.centeredText(font, Component.translatable("ordersmp-clips.link.waiting"), center, y + 64, GRAY);
  }

  @Override
  public void removed() {
    closed = true;
    super.removed();
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }

  private void start() {
    started = true;
    link = null;
    problem = null;
    studio.startLink().whenComplete((begun, error) -> minecraft.execute(() -> {
      if (error != null) {
        problem = Component.literal(message(error));
        return;
      }
      link = begun;
      openBrowser();
      Thread.ofVirtual().name("Clips link").start(() -> poll(begun));
    }));
  }

  private void openBrowser() {
    StudioClient.Link current = link;
    if (current == null) {
      return;
    }
    Blaze3D.openUri(URI.create(current.approveUrl()));
  }

  /** Asks Studio every few seconds until the player approves, cancels, or the code runs out. */
  private void poll(StudioClient.Link begun) {
    long interval = begun.interval() * 1000L;
    long deadline = System.currentTimeMillis() + begun.expiresIn() * 1000L;
    while (!closed && link == begun) {
      try {
        Thread.sleep(interval);
      } catch (InterruptedException e) {
        return;
      }
      if (closed || link != begun) {
        return;
      }
      if (System.currentTimeMillis() > deadline) {
        fail("ordersmp-clips.link.expired");
        return;
      }
      StudioClient.Poll result;
      try {
        result = studio.poll(begun.deviceCode());
      } catch (StudioClient.StudioException e) {
        if (e.status() == 0) {
          continue;
        }
        problem = Component.literal(e.getMessage());
        return;
      }
      if (result.token() != null) {
        minecraft.execute(this::linked);
        return;
      }
      switch (result.error()) {
        case "authorization_pending" -> {
        }
        case "slow_down" -> interval += 5000;
        case "expired_token" -> {
          fail("ordersmp-clips.link.expired");
          return;
        }
        case "access_denied" -> {
          fail("ordersmp-clips.link.denied");
          return;
        }
        default -> {
          problem = Component.literal(String.valueOf(result.error()));
          return;
        }
      }
    }
  }

  private void linked() {
    ClipsClient.toast(minecraft, Component.translatable("ordersmp-clips.link.done.title"),
        Component.translatable("ordersmp-clips.link.done.body", String.valueOf(studio.accountName())));
    minecraft.gui.setScreen(parent);
    onLinked.run();
  }

  private void fail(String key) {
    problem = Component.translatable(key);
  }

  private static String message(Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    return cause.getMessage() == null ? "Studio could not be reached." : cause.getMessage();
  }
}
