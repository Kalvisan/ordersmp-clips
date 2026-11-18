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
 * Hands every finished frame to the recorder right after the game drew it (world, HUD and any open
 * screen) and before the frame goes to the window. Then the recording mark is drawn on top in a
 * pass of its own: the window shows it, but the captured frame was taken before it, so the mark
 * never appears in a clip.
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
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
  private void clips$captureFrame(boolean advanceGameTime, CallbackInfo ci) {
    Recorder recorder = ClipsClient.recorder();
    if (recorder == null) {
      return;
    }
    recorder.onFrameRendered(gameRenderer.mainRenderTarget(), level != null);
    ClipsClient.drawMark(gameRenderer);
  }
}
