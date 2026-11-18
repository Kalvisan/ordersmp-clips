package lv.ordersmp.clips.mixin;

import com.mojang.blaze3d.audio.Listener;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives access to the sound listener, whose position and facing decide how sounds are panned. */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {

  @Accessor("listener")
  Listener clips$listener();
}
