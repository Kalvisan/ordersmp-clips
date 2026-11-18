package lv.ordersmp.clips.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.audio.SoundLog;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Logs every sound the game starts, at the moment it is handed to its channel: by then the file,
 * the randomised pitch and the fade distance are all decided, so the clip's sound can be mixed later
 * exactly from these values. Only the sound's own volume is kept, never the player's sound sliders,
 * so the game's volume and a clip's volume stay independent: clip volumes are set in the editor and
 * applied only when the clip's sound is mixed. Nothing here changes how the game plays the sound.
 */
@Mixin(SoundEngine.class)
abstract class SoundEngineMixin {

  @Inject(
      method = "play",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sounds/ChannelAccess$ChannelHandle;execute(Ljava/util/function/Consumer;)V", ordinal = 0))
  private void clips$logSound(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir, @Local Sound sound,
      @Local(ordinal = 1) float attenuationDistance, @Local(ordinal = 3) float pitch,
      @Local Vec3 position, @Local(ordinal = 1) boolean looping, @Local(ordinal = 2) boolean streaming) {
    SoundLog log = ClipsClient.soundLog();
    if (log == null) {
      return;
    }
    float distance = instance.getAttenuation() == SoundInstance.Attenuation.LINEAR ? attenuationDistance : 0;
    log.played(instance, sound.getPath(), Mth.clamp(instance.getVolume(), 0, 1), pitch, distance, position, looping, streaming);
  }
}
