package kuro;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import kuro.ui.Toasts;

/**
 * SpawnerProtect: gorus alanimiza baska bir oyuncu girince (SHIFT basili tutarak) yakinimizdaki butun spawner'lari
 * ipek dokunuslu kazmayla kirar, dusen spawner'lari toplar, sunucudan cikar ve "Spawnerlar kurtarildi" yazar.
 *
 * Akis:
 *  1) Bekleme: her 5 tickte menzilde (Config.spRange) baska oyuncu var mi diye bakar; varsa ve kirma menzilinde
 *     (Config.spReach) spawner varsa tetiklenir. Spawner yoksa hicbir sey yapmaz (sunucudan cikmaz).
 *  2) Kirma: shift basili, hotbar'daki en iyi IPEK DOKUNUSLU kazma secilir (yoksa spawner'a dokunmaz, modul kapanir),
 *     spawner'lar en yakindan baslayarak kirilir.
 *  3) Toplama: dusen spawner esyalarina yurur (en fazla Config.spWait tick).
 *  4) Cikis: "Spawnerlar kurtarildi" mesaji + baglanti kesme, modul kendini kapatir (AutoLog gibi).
 */
public final class SpawnerProtect {
   private static final MinecraftClient mc = MinecraftClient.getInstance();

   private static boolean active;
   private static int idle;
   private static int ticks;
   private static int broken;
   private static int pickTicks;
   private static BlockPos target;
   private static boolean walking;
   private static int targetTicks;
   private static final Set<BlockPos> skipped = new HashSet<>();

   private SpawnerProtect() {
   }

   /** Modul kapaninca / dunya degisince: durumu sifirla, basili tuslari birak. */
   public static void reset() {
      active = false;
      idle = 0;
      ticks = 0;
      broken = 0;
      pickTicks = 0;
      target = null;
      targetTicks = 0;
      skipped.clear();
      releaseKeys();
   }

   private static void releaseKeys() {
      if (mc.options != null) {
         // fiziksel tus durumunu geri yukle (oyuncu kendisi shift/W basiyorsa basili kalir)
         KeyBinding.updatePressedStates();
      }

      walking = false;
   }

   public static void tick() {
      ClientPlayerEntity p = mc.player;
      if (p == null || mc.world == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
         if (active) {
            reset();
         }

         return;
      }

      if (!active) {
         if (--idle > 0) {
            return;
         }

         idle = 5;
         if (!intruder(p)) {
            return;
         }

         if (spawners(p).isEmpty()) {
            idle = 20; // yakinda spawner yok; sik taramaya gerek yok
            return;
         }

         active = true;
         ticks = 0;
         broken = 0;
         pickTicks = 0;
         target = null;
         targetTicks = 0;
         skipped.clear();
      }

      ticks++;
      mc.options.sneakKey.setPressed(true); // SHIFT

      if (ticks > 600) { // 30 sn: takildiysa vazgec
         finish(p);
         return;
      }

      // onceki hedef kirildi mi
      if (target != null && !mc.world.getBlockState(target).isOf(Blocks.SPAWNER)) {
         broken++;
         target = null;
         targetTicks = 0;
      } else if (target != null && ++targetTicks > 120) {
         // 6 sn'de kirilamayan spawner'da takilma: atla, siradakine gec
         skipped.add(target);
         target = null;
         targetTicks = 0;
         mc.interactionManager.cancelBlockBreaking();
      }

      List<BlockPos> list = spawners(p);
      if (!list.isEmpty()) {
         int slot = silkPickaxe(p);
         if (slot < 0) {
            Mods.msg("SpawnerProtect: hotbar'da ipek dokunuşlu kazma yok, spawner'a dokunulmadı");
            Toasts.push("SpawnerProtect", false, "İpek dokunuşlu kazma yok");
            Kuro.setOn(Kuro.SPAWNERPROTECT, false);
            return;
         }

         p.getInventory().selectedSlot = slot;
         BlockPos bp = list.get(0);
         if (!bp.equals(target)) {
            targetTicks = 0;
         }

         target = bp;
         Vec3d c = Vec3d.ofCenter(bp);
         lookAt(p, c);
         Vec3d eye = p.getEyePos();
         Direction dir = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
         mc.interactionManager.updateBlockBreakingProgress(bp, dir);
         p.swingHand(Hand.MAIN_HAND);
         return;
      }

      // kirilacak spawner kalmadi: dusenleri topla
      mc.interactionManager.cancelBlockBreaking();
      pickTicks++;
      List<ItemEntity> drops = mc.world.getEntitiesByClass(ItemEntity.class, p.getBoundingBox().expand(8.0),
         e -> e.getStack().isOf(Items.SPAWNER));
      if (drops.isEmpty()) {
         releaseWalk();
         if (pickTicks >= 8) { // dususlerin gelmesi icin kisa bekleme
            finish(p);
         }

         return;
      }

      if (pickTicks > Math.round(Config.spWait)) {
         finish(p);
         return;
      }

      ItemEntity near = drops.stream().min(Comparator.comparingDouble(e -> e.squaredDistanceTo(p))).get();
      if (p.getPos().distanceTo(near.getPos()) > 0.7) {
         lookAt(p, near.getPos());
         mc.options.forwardKey.setPressed(true);
         walking = true;
      } else {
         releaseWalk();
      }
   }

   private static void releaseWalk() {
      if (walking) {
         mc.options.forwardKey.setPressed(false);
         walking = false;
      }
   }

   private static void finish(ClientPlayerEntity p) {
      int left = spawners(p).size() + skipped.size();
      boolean sp = mc.isInSingleplayer();
      boolean threat = intruder(p);
      String text;
      if (broken > 0) {
         text = left > 0
            ? "Spawnerlar kurtarıldı! (" + broken + " adet, " + left + " tanesi kırılamadı)"
            : "Spawnerlar kurtarıldı! (" + broken + " adet)";
      } else {
         text = "Spawner kırılamadı";
      }

      boolean saved = broken > 0;
      boolean leave = !sp && mc.getNetworkHandler() != null && (saved || threat);
      reset();
      Kuro.setOn(Kuro.SPAWNERPROTECT, false);
      Mods.msg("SpawnerProtect: " + text);
      Toasts.push("SpawnerProtect", saved, text);
      if (sp && saved) {
         Mods.msg("SpawnerProtect: tek oyunculu dünyada sunucudan çıkılmaz (sadece çok oyunculuda çalışır)");
      }

      if (leave) {
         Text reason = Text.literal("[Kuro] SpawnerProtect: " + text);
         try {
            // vanilla "Ayril" ile ayni akis: kesin olarak ana menuye atar
            mc.world.disconnect();
            mc.disconnect();
            mc.setScreen(new DisconnectedScreen(new MultiplayerScreen(new TitleScreen()), Text.literal("SpawnerProtect"), reason));
         } catch (Throwable t) {
            if (mc.getNetworkHandler() != null) {
               mc.getNetworkHandler().getConnection().disconnect(reason);
            }
         }
      }
   }

   /** Menzilde (ve istenirse gorus acimizda) baska bir oyuncu var mi. */
   private static boolean intruder(ClientPlayerEntity p) {
      double r = Config.spRange;
      Vec3d look = p.getRotationVec(1.0F);
      for (AbstractClientPlayerEntity o : mc.world.getPlayers()) {
         if (o == p || o.getId() < 0 || o.isSpectator() || o.isRemoved()) { // id < 0: FakePlayer
            continue;
         }

         Vec3d to = o.getEyePos().subtract(p.getEyePos());
         if (to.length() > r) {
            continue;
         }

         if (Config.spView >= 0.5F && to.normalize().dotProduct(look) < 0.5) { // ~120 derecelik gorus acisi
            continue;
         }

         return true;
      }

      return false;
   }

   /** Kirma menzilindeki spawner'lar, en yakindan uzaga. */
   private static List<BlockPos> spawners(ClientPlayerEntity p) {
      List<BlockPos> out = new ArrayList<>();
      double reach = Math.min(Config.spReach, p.getBlockInteractionRange());
      Vec3d eye = p.getEyePos();
      int r = MathHelper.ceil(reach);
      BlockPos base = p.getBlockPos();
      BlockPos.Mutable m = new BlockPos.Mutable();
      for (int dx = -r; dx <= r; dx++) {
         for (int dy = -r - 1; dy <= r + 1; dy++) {
            for (int dz = -r; dz <= r; dz++) {
               m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
               if (!mc.world.getBlockState(m).isOf(Blocks.SPAWNER)) {
                  continue;
               }

               if (Vec3d.ofCenter(m).squaredDistanceTo(eye) <= reach * reach && !skipped.contains(m)) {
                  out.add(m.toImmutable());
               }
            }
         }
      }

      out.sort(Comparator.comparingDouble(b -> Vec3d.ofCenter(b).squaredDistanceTo(eye)));
      return out;
   }

   /** Hotbar'daki en hizli IPEK DOKUNUSLU kazma; yoksa -1. */
   private static int silkPickaxe(ClientPlayerEntity p) {
      int best = -1;
      float bestSpeed = -1.0F;
      for (int i = 0; i < 9; i++) {
         ItemStack s = p.getInventory().getStack(i);
         if (s.isEmpty() || !s.isIn(ItemTags.PICKAXES)) {
            continue;
         }

         boolean silk = s.getEnchantments().getEnchantments().stream().anyMatch(e -> e.matchesKey(Enchantments.SILK_TOUCH));
         if (!silk) {
            continue;
         }

         float sp = s.getMiningSpeedMultiplier(Blocks.SPAWNER.getDefaultState());
         if (sp > bestSpeed) {
            bestSpeed = sp;
            best = i;
         }
      }

      return best;
   }

   private static void lookAt(ClientPlayerEntity p, Vec3d pt) {
      if (SilentRot.enabled()) {
         SilentRot.face(pt);
         return;
      }

      Vec3d eye = p.getEyePos();
      double dx = pt.x - eye.x;
      double dy = pt.y - eye.y;
      double dz = pt.z - eye.z;
      double h = Math.sqrt(dx * dx + dz * dz);
      p.setYaw((float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
      p.setPitch((float)MathHelper.clamp(-Math.toDegrees(Math.atan2(dy, h)), -90.0, 90.0));
   }
}
