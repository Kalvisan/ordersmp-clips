package lv.ordersmp.clips.capture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Copies finished frames off the graphics card without stalling the game. Each capture scales the
 * frame on the GPU to the recording size with Minecraft's own blit pipeline, then asks the GPU to
 * copy it into a buffer; the copy completes a frame or two later and is handed over then. Three
 * buffers take turns, so the render thread never waits for the GPU. It only uses Minecraft's
 * backend-neutral render API, so it works on both OpenGL and Vulkan.
 */
public final class FrameCapture implements AutoCloseable {

  private static final int SLOTS = 3;
  private static final int TEXTURE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC;
  private static final int BUFFER_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;

  private enum State { FREE, COPYING, READY }

  private static final class Slot {
    private GpuTexture texture;
    private GpuTextureView view;
    private GpuBuffer buffer;
    private volatile State state = State.FREE;
  }

  private final Slot[] slots = new Slot[SLOTS];
  private int width;
  private int height;
  private int next;

  /** Picture width frames are captured at, or 0 before the first capture. */
  public int width() {
    return width;
  }

  /** Picture height frames are captured at, or 0 before the first capture. */
  public int height() {
    return height;
  }

  /**
   * Hands every finished copy to {@code sink}, then starts a new capture of {@code target} scaled to
   * fit {@code maxHeight}. Returns false when the picture size changed, so the caller can restart its
   * encoder; frames of the old size are dropped.
   */
  public boolean capture(RenderTarget target, int maxHeight, Consumer<ByteBuffer> sink) {
    drain(sink);
    if (target.getColorTextureView() == null) {
      return true;
    }
    boolean sameSize = resize(target.width, target.height, maxHeight);
    Slot slot = slots[next];
    if (slot.state != State.FREE) {
      return sameSize;
    }
    next = (next + 1) % SLOTS;
    slot.state = State.COPYING;
    GpuDevice device = RenderSystem.getDevice();
    CommandEncoder encoder = device.createCommandEncoder();
    try (RenderPass pass = encoder.createRenderPass(() -> "Clips capture", slot.view, Optional.empty())) {
      RenderSystem.bindDefaultUniforms(pass);
      pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
      pass.setUniform("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
      pass.draw(3, 1, 0, 0);
    }
    encoder.copyTextureToBuffer(slot.texture, slot.buffer, 0L, () -> slot.state = State.READY, 0);
    return sameSize;
  }

  /** Hands over finished copies without starting a new one, for frames that are not captured. */
  public void drain(Consumer<ByteBuffer> sink) {
    for (Slot slot : slots) {
      if (slot == null || slot.state != State.READY) {
        continue;
      }
      try (GpuBufferSlice.MappedView view = slot.buffer.map(true, false)) {
        sink.accept(view.data());
      }
      slot.state = State.FREE;
    }
  }

  private boolean resize(int targetWidth, int targetHeight, int maxHeight) {
    int h = Math.min(targetHeight, maxHeight);
    int w = (int) Math.round((double) targetWidth * h / targetHeight);
    w = w / 2 * 2;
    h = h / 2 * 2;
    if (w == width && h == height && slots[0] != null) {
      return true;
    }
    close();
    width = w;
    height = h;
    GpuDevice device = RenderSystem.getDevice();
    for (int i = 0; i < SLOTS; i++) {
      Slot slot = new Slot();
      slot.texture = device.createTexture("Clips capture " + i, TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
      slot.view = device.createTextureView(slot.texture);
      slot.buffer = device.createBuffer(() -> "Clips capture buffer", BUFFER_USAGE, (long) w * h * 4);
      slots[i] = slot;
    }
    next = 0;
    return false;
  }

  /** Frees the GPU textures and buffers. */
  @Override
  public void close() {
    for (int i = 0; i < SLOTS; i++) {
      Slot slot = slots[i];
      if (slot == null) {
        continue;
      }
      slot.view.close();
      slot.texture.close();
      slot.buffer.close();
      slots[i] = null;
    }
  }
}
