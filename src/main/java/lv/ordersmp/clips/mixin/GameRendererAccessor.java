package lv.ordersmp.clips.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives access to the GUI renderer, so the recording mark can be drawn in a pass of its own. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {

  @Accessor("guiRenderer")
  GuiRenderer clips$guiRenderer();
}
