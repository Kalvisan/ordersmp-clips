package lv.ordersmp.clips.mixin;

import lv.ordersmp.clips.ClipsClient;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Puts a Clips icon into the pause menu's row of icon buttons, next to the vanilla ones, so the
 * Clips screen is one click from Esc without moving any of the menu's rows.
 */
@Mixin(PauseScreen.class)
abstract class PauseScreenMixin {

  @ModifyArg(
      method = "createPauseMenu",
      at = @At(
          value = "INVOKE",
          target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;"),
      index = 0)
  private LayoutElement clips$addIcon(LayoutElement element) {
    if (!(element instanceof LinearLayout iconRow)) {
      return element;
    }
    PauseScreen screen = (PauseScreen) (Object) this;
    iconRow.addChild(SpriteIconButton.builder(Component.translatable("ordersmp-clips.screen.title"),
            button -> ClipsClient.openScreen(screen), true)
        .width(20)
        .sprite(Identifier.fromNamespaceAndPath(ClipsClient.MOD_ID, "pause_menu/clips"), 15, 15)
        .withTootip()
        .build());
    return element;
  }
}
