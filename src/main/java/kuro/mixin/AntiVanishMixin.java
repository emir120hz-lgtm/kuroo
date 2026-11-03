package kuro.mixin;

import kuro.AntiVanish;
import kuro.Kuro;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AntiVanish: dunyada olmayan entity'den gelen blok kirma animasyonu = gorunmez biri kaziyor. kuro.extra.mixins.json (require=0). */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class AntiVanishMixin {
   @Inject(method = "onBlockBreakingProgress", at = @At("HEAD"), require = 0)
   private void kuro$avBreak(BlockBreakingProgressS2CPacket packet, CallbackInfo ci) {
      // ag thread'inde dokunma; paket ana thread'e yeniden kuyruklanir ve burasi tekrar cagrilir
      if (MinecraftClient.getInstance().isOnThread()) {
         AntiVanish.onBreak(packet);
      }
   }

   /** Vanish tespiti icin gonderdigimiz komut tamamlama istegine gelen cevabi yakalar (sohbet ekranina karismaz). */
   @Inject(method = "onCommandSuggestions", at = @At("HEAD"), cancellable = true, require = 0)
   private void kuro$avSuggest(CommandSuggestionsS2CPacket packet, CallbackInfo ci) {
      if (MinecraftClient.getInstance().isOnThread() && Kuro.on[Kuro.ANTIVANISH] && AntiVanish.onSuggestions(packet)) {
         ci.cancel();
      }
   }

   // ag thread'inde dokunma; paket ana thread'e yeniden kuyruklanir ve burasi tekrar cagrilir
   @Inject(method = "onBlockEvent", at = @At("HEAD"), require = 0)
   private void kuro$avChest(BlockEventS2CPacket packet, CallbackInfo ci) {
      if (MinecraftClient.getInstance().isOnThread()) {
         AntiVanish.onChest(packet);
      }
   }

   @Inject(method = "onItemPickupAnimation", at = @At("HEAD"), require = 0)
   private void kuro$avPickup(ItemPickupAnimationS2CPacket packet, CallbackInfo ci) {
      if (MinecraftClient.getInstance().isOnThread()) {
         AntiVanish.onPickup(packet);
      }
   }

   @Inject(method = "onPlaySound", at = @At("HEAD"), require = 0)
   private void kuro$avSound(PlaySoundS2CPacket packet, CallbackInfo ci) {
      if (MinecraftClient.getInstance().isOnThread()) {
         AntiVanish.onSound(packet);
      }
   }
}
