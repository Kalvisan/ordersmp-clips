package lv.ordersmp.clips.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives access to the sound engine behind the sound manager, to read where the listener is. */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {

  @Accessor("soundEngine")
  SoundEngine clips$soundEngine();
}
