package lv.ordersmp.clips.mixin;

import lv.ordersmp.clips.ClipsClient;
import lv.ordersmp.clips.fight.FightTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the fight tracker about hits and deaths as the server reports them. Each hook runs right
 * after the packet moved onto the client thread, so it runs once per packet and never on the
 * network thread. Only players count: hits and deaths of mobs never start a fight.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {

  private static final String ON_CLIENT_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread("
      + "Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

  @Shadow
  private ClientLevel level;

  @Inject(method = "handleDamageEvent", at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
  private void clips$damage(ClientboundDamageEventPacket packet, CallbackInfo ci) {
    FightTracker fights = ClipsClient.fights();
    Minecraft minecraft = Minecraft.getInstance();
    if (fights == null || minecraft.player == null || level == null) {
      return;
    }
    int self = minecraft.player.getId();
    if (packet.sourceCauseId() == self && level.getEntity(packet.entityId()) instanceof Player victim && victim != minecraft.player) {
      fights.hit(victim.getId(), victim.getName().getString());
      return;
    }
    if (packet.entityId() == self && level.getEntity(packet.sourceCauseId()) instanceof Player attacker && attacker != minecraft.player) {
      fights.hit(attacker.getId(), attacker.getName().getString());
    }
  }

  @Inject(method = "handleEntityEvent", at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
  private void clips$entityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
    FightTracker fights = ClipsClient.fights();
    if (fights == null || level == null || packet.getEventId() != EntityEvent.DEATH) {
      return;
    }
    Entity entity = packet.getEntity(level);
    if (entity instanceof Player player && player != Minecraft.getInstance().player) {
      fights.died(player.getId());
    }
  }

  @Inject(method = "handlePlayerCombatKill", at = @At(value = "INVOKE", target = ON_CLIENT_THREAD, shift = At.Shift.AFTER))
  private void clips$playerDied(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
    FightTracker fights = ClipsClient.fights();
    Minecraft minecraft = Minecraft.getInstance();
    if (fights == null || minecraft.player == null || packet.playerId() != minecraft.player.getId()) {
      return;
    }
    fights.playerDied(null);
  }
}
