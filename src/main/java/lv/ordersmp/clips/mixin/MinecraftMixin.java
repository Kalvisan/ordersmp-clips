package lv.ordersmp.clips.mixin;

import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hands every finished frame to the recorder. It runs where Minecraft's own frame capture for the
 * Tracy profiler runs: after the world, the HUD and any open screen are drawn, and before the frame
 * is submitted, so a clip shows exactly what the player saw.
 */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {

  @Shadow
  @Final
  public GameRenderer gameRenderer;

  @Shadow
  public ClientLevel level;

  @Inject(
      method = "renderFrame",
      at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;submit()V", ordinal = 0))
  private void clips$captureFrame(boolean advanceGameTime, CallbackInfo ci) {
    Recorder recorder = ClipsClient.recorder();
    if (recorder == null) {
      return;
    }
    recorder.onFrameRendered(gameRenderer.mainRenderTarget(), level != null);
  }
}
