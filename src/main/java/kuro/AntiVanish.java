package kuro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.block.AbstractChestBlock;
import net.minecraft.block.Block;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.network.packet.c2s.play.RequestCommandCompletionsC2SPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.network.packet.s2c.play.ItemPickupAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.network.packet.s2c.play.CommandSuggestionsS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.joml.Matrix4f;

/**
 * AntiVanish: vanish'e girmis (gorunmez) oyunculari tespit eder.
 * Yontemler:
 *  1) Tab listesinde SPECTATOR modunda gorunen oyuncular (spectator-vanish kullanan yetkililer).
 *  2) Yakinda gorunen bir oyuncu birden kaybolur ama tab listesinde hala durur (vanish plugin'i entity'yi gizledi).
 *  3) Dunyada olmayan bir entity ID'sinden blok kirma animasyonu gelir (gorunmez biri yakinda kaziyor).
 *  4) Vanish modlari (Modrinth/CurseForge) oyuncuyu tab listesinden siler ama sunucunun komut tamamlamasi (/msg ...)
 *     hala gercek oyuncu isimlerini doner: tab listesinde olmayan isim = vanish'teki oyuncu.
 *  5) Dunyada entity'si olup tab listesinde olmayan veya gorunmez (invisibility) oyuncular.
 * YETKILI FILTRESI (Config.avStaff): acikken isimli tespitler (spectator, vanish modu, yakinda kaybolan) sadece
 *  admin / yetkili olanlar icin gosterilir. Yetkili sayilmak icin: Config.avStaffNames listesinde olmak, ya da tab listesi /
 *  scoreboard takim on-eki-son-eki / gorunen adinda rutbe yazmak (admin, owner, mod, helper, staff, dev, yetkili...).
 *  Rutbe oyuncu tab'dayken ogrenilip hatirlanir; boylece sonradan vanish'e girince de taninir.
 * Tespit olunca ekranin ortasina "IZLENIYORSUN" yazisi cikar; son bilinen / kazilan konuma kutu cizilir.
 */
public final class AntiVanish {
   private static final MinecraftClient mc = MinecraftClient.getInstance();

   /** Yakindaki oyuncunun son gorulen durumu. */
   private static final class Seen {
      String name;
      Vec3d pos;
      float hp;
   }

   /** Kaybolma sonrasi dogrulama bekleyen oyuncu. */
   private static final class Pending {
      String name;
      Vec3d pos;
      int ticks;
   }

   /** Ekranda / dunyada gosterilen supheli. */
   private static final class Mark {
      String label;
      Vec3d pos;
      long until;
   }

   private static final Map<UUID, Seen> seen = new HashMap<>();
   private static final Map<UUID, Pending> pending = new HashMap<>();
   private static final Set<UUID> specKnown = new HashSet<>();
   private static final Map<Integer, Integer> breakCount = new HashMap<>();
   private static final Map<Integer, Long> breakAlerted = new HashMap<>();
   private static final List<Mark> marks = new ArrayList<>();
   private static long warnUntil;
   private static String warnLine = "";
   private static Object lastWorld;

   // --- vanish modu tespiti (komut tamamlama sizintisi + entity kontrolu)
   private static final String[] CMDS = {"/msg ", "/tell ", "/w ", "/minecraft:msg ", "/minecraft:tell ", "/minecraft:w ", "/whisper ", "/pm ", "/m ", "/essentials:msg ", "/tp ", "/minecraft:tp "};
   private static final Set<String> cycle = new HashSet<>(); // bir tur boyunca (tum komutlardan) toplanan isimler
   private static boolean cycleAny;
   private static final int ID_BASE = -7331; // sohbet ekraninin pozitif id'leriyle ve FakePlayer'in -4242'siyle cakismaz
   private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]{1,16}");
   private static final Map<String, Integer> missing = new HashMap<>();
   private static final Map<String, String> scanFlag = new LinkedHashMap<>();
   private static final Map<String, String> live = new LinkedHashMap<>();
   private static final Set<String> alerted = new HashSet<>();
   private static boolean waiting;
   private static int waitId;
   private static long sentAt;
   private static long nextScan;
   private static int cmdIdx;

   // --- vanish kullananlarin biraktigi izler: sandik acma, esya alma, yurume sesi
   private static final class PendChest {
      Vec3d c;
      int ticks;
   }

   private static final List<PendChest> chests = new ArrayList<>();
   private static final Map<String, Long> cool = new HashMap<>();
   private static long lastStep;
   private static Vec3d lastStepPos;

   // --- yetkili (admin / izin sahibi) filtresi
   private static final Pattern RANK = Pattern.compile(
      "(?i)(?<![a-z0-9])(admin|administrator|owner|co-?owner|mod|moderator|moderat\u00f6r|helper|staff|support|manager|developer|dev|founder|operator|op|yetkili|y\u00f6netici|yonetici|sahip|kurucu|destek)(?![a-z0-9])");
   private static final Set<String> staff = new HashSet<>(); // kucuk harf; tab/takimda rutbesi goruldu
   private static String namesRaw = null;
   private static final Set<String> namesSet = new HashSet<>();
   private static long nextStaffScan;

   private AntiVanish() {
   }

   /** Metinde rutbe kelimesi var mi (oyuncunun kendi adi cikarilir ki "Admin123" adli sade oyuncu yetkili sayilmasin). */
   private static boolean rankText(String text, String name) {
      if (text == null || text.isEmpty()) {
         return false;
      }

      String t = name == null ? text : text.replace(name, " ");
      return RANK.matcher(t).find();
   }

   private static boolean teamStaff(String name) {
      if (mc.world == null) {
         return false;
      }

      Team t = mc.world.getScoreboard().getScoreHolderTeam(name);
      if (t == null) {
         return false;
      }

      return rankText(t.getPrefix().getString(), name) || rankText(t.getSuffix().getString(), name);
   }

   /** Tab listesindeki rutbeleri tarar ve hatirlar (saniyede bir). */
   private static void refreshStaff(ClientPlayNetworkHandler h, long now) {
      if (now < nextStaffScan) {
         return;
      }

      nextStaffScan = now + 1000L;
      for (PlayerListEntry e : h.getPlayerList()) {
         String n = e.getProfile().getName();
         if (n == null || n.isEmpty()) {
            continue;
         }

         Text dn = e.getDisplayName();
         if ((dn != null && rankText(dn.getString(), n)) || teamStaff(n)) {
            staff.add(n.toLowerCase());
         }
      }
   }

   private static boolean listed(String lower) {
      String raw = Config.avStaffNames == null ? "" : Config.avStaffNames;
      if (!raw.equals(namesRaw)) {
         namesRaw = raw;
         namesSet.clear();
         for (String x : raw.split("[,;\\s]+")) {
            if (!x.isEmpty()) {
               namesSet.add(x.toLowerCase());
            }
         }
      }

      return namesSet.contains(lower);
   }

   /** Isimli bir tespit gosterilsin mi? Filtre kapaliysa herkes; aciksa sadece yetkililer. */
   private static boolean isStaff(String name) {
      if (Config.avStaff < 0.5F) {
         return true;
      }

      if (name == null) {
         return false;
      }

      String l = name.toLowerCase();
      return listed(l) || staff.contains(l) || teamStaff(name);
   }

   public static void reset() {
      seen.clear();
      pending.clear();
      specKnown.clear();
      breakCount.clear();
      breakAlerted.clear();
      marks.clear();
      warnUntil = 0L;
      missing.clear();
      scanFlag.clear();
      live.clear();
      alerted.clear();
      staff.clear();
      nextStaffScan = 0L;
      waiting = false;
      cmdIdx = 0;
      cycle.clear();
      cycleAny = false;
      chests.clear();
      cool.clear();
      lastStepPos = null;
      nextScan = System.currentTimeMillis() + 3000L;
   }

   private static long ms() {
      return Math.round(Config.avSeconds * 1000.0F);
   }

   private static void alert(String line, String chat, Vec3d pos) {
      long now = System.currentTimeMillis();
      warnUntil = now + ms();
      warnLine = line;
      if (Config.avChat >= 0.5F) {
         Mods.msg("AntiVanish: " + chat);
      }

      if (pos != null) {
         Mark m = new Mark();
         m.label = line;
         m.pos = pos;
         m.until = now + ms();
         marks.add(m);
      }
   }

   public static void tick() {
      ClientPlayNetworkHandler h = mc.getNetworkHandler();
      if (mc.player == null || mc.world == null || h == null) {
         return;
      }

      if (lastWorld != mc.world) {
         reset();
         lastWorld = mc.world;
      }

      long now = System.currentTimeMillis();
      refreshStaff(h, now);
      marks.removeIf(m -> m.until < now);
      breakAlerted.values().removeIf(t -> now - t > 60000L);

      // sandik acilis olaylari: biz bir kutu actiysak (ekran acildiysa) bizim sayilir, degilse gorunmez biri acmistir
      Iterator<PendChest> cit = chests.iterator();
      while (cit.hasNext()) {
         PendChest pc = cit.next();
         if (++pc.ticks < 6) {
            continue;
         }

         cit.remove();
         if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
            continue;
         }

         boolean visible = false;
         for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && p.getPos().distanceTo(pc.c) < 8.0) {
               visible = true; // gorunen biri acmis olabilir
               break;
            }
         }

         if (!visible && Config.avAnon >= 0.5F) {
            alert("§cGörünmez biri sandık açtı", "Görünmez biri yakındaki bir sandığı/kutuyu açtı (vanish)", pc.c);
         }
      }

      // 1) Spectator modundaki oyuncular (tab listesi)
      if (Config.avSpec >= 0.5F) {
         Set<UUID> cur = new HashSet<>();
         for (PlayerListEntry e : h.getPlayerList()) {
            UUID id = e.getProfile().getId();
            if (id == null || id.equals(mc.player.getUuid()) || e.getGameMode() != GameMode.SPECTATOR) {
               continue;
            }

            cur.add(id);
            specKnown.add(id);
         }

         specKnown.retainAll(cur);
      } else {
         specKnown.clear();
      }

      // 2) Yakinda gorunen oyuncu aniden kayboldu ama tab listesinde duruyor
      Set<UUID> present = new HashSet<>();
      for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
         if (p == mc.player) {
            continue;
         }

         UUID id = p.getUuid();
         present.add(id);
         pending.remove(id);
         Seen s = seen.get(id);
         if (s == null) {
            s = new Seen();
            seen.put(id, s);
         }

         s.name = p.getGameProfile().getName();
         s.pos = p.getPos();
         s.hp = p.getHealth();
      }

      if (Config.avNear >= 0.5F) {
         Iterator<Map.Entry<UUID, Seen>> it = seen.entrySet().iterator();
         while (it.hasNext()) {
            Map.Entry<UUID, Seen> en = it.next();
            if (present.contains(en.getKey())) {
               continue;
            }

            Seen s = en.getValue();
            it.remove();
            if (s.hp <= 0.0F || s.pos == null) {
               continue; // oldu; entity sadece olum animasyonundan sonra silindi
            }

            if (mc.player.getPos().distanceTo(s.pos) > Config.avRange) {
               continue; // uzaktaydi; normal olarak render mesafesinden cikmis olabilir
            }

            Pending pd = new Pending();
            pd.name = s.name;
            pd.pos = s.pos;
            pending.put(en.getKey(), pd);
         }
      } else {
         seen.keySet().retainAll(present);
         pending.clear();
      }

      Iterator<Map.Entry<UUID, Pending>> pit = pending.entrySet().iterator();
      while (pit.hasNext()) {
         Map.Entry<UUID, Pending> en = pit.next();
         Pending pd = en.getValue();
         if (++pd.ticks < 4) {
            continue; // quit paketinin tab listesinden silmesi icin birkac tick bekle
         }

         pit.remove();
         if (h.getPlayerListEntry(en.getKey()) != null && isStaff(pd.name)) {
            alert("§c" + pd.name + " §7vanish'e girdi", pd.name + " yakininda aniden kayboldu ama tab listesinde duruyor (vanish)", pd.pos);
         }
      }

      // 4) Komut tamamlama sizintisi: vanish modu tab listesinden siler, sunucu /msg onerilerinde yine verir
      if (Config.avLeak >= 0.5F) {
         if (waiting && now - sentAt > 5000L) {
            waiting = false; // cevap gelmedi: ayni komutta takilma, siradakine gec
            cmdIdx = (cmdIdx + 1) % CMDS.length;
            nextScan = now + 1000L;
         }

         if (!waiting && now >= nextScan) {
            waitId = ID_BASE - cmdIdx;
            waiting = true;
            sentAt = now;
            nextScan = now + Math.round(Config.avScan * 1000.0F);
            h.sendPacket(new RequestCommandCompletionsC2SPacket(waitId, CMDS[cmdIdx]));
         }
      } else {
         waiting = false;
         missing.clear();
         scanFlag.clear();
      }

      // 5) Gosterilecekler: vanish modu kullananlar (komut tamamlama sizintisi) ve spectator'dakiler; yetkili filtresi aciksa sadece yetkililer. NPC'ler sayilmaz.
      live.clear();
      for (Map.Entry<String, String> en : scanFlag.entrySet()) {
         if (isStaff(en.getKey())) {
            live.put(en.getKey(), en.getValue());
         }
      }
      if (Config.avSpec >= 0.5F) {
         for (PlayerListEntry e : h.getPlayerList()) {
            UUID id = e.getProfile().getId();
            if (id != null && !id.equals(mc.player.getUuid()) && e.getGameMode() == GameMode.SPECTATOR && isStaff(e.getProfile().getName())) {
               live.putIfAbsent(e.getProfile().getName(), "spectator");
            }
         }
      }

      for (Map.Entry<String, String> en : live.entrySet()) {
         if (alerted.add(en.getKey())) {
            Vec3d pos = null;
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
               if (p.getGameProfile().getName().equals(en.getKey())) {
                  pos = p.getPos();
                  break;
               }
            }

            alert("§c" + en.getKey() + " §7" + en.getValue() + " kullanıyor", en.getKey() + " " + en.getValue() + " kullanıyor", pos);
         }
      }

      alerted.retainAll(live.keySet());
   }

   /** Paketten okunan id ve oneri isimleri. */
   private static final class Parsed {
      int id;
      final List<String> names = new ArrayList<>();
   }

   /**
    * CommandSuggestionsS2CPacket'i isimden bagimsiz okur: 1.21.4'te paketin erisim metodlari (getCompletionId / id ...)
    * surumden surume degisiyor. Record ise bilesen sirasina, degilse donus tipine bakar; yarn/intermediary fark etmez.
    */
   private static Parsed parse(Object pk) {
      try {
         Parsed r = new Parsed();
         boolean gotId = false;
         Class<?> c = pk.getClass();
         if (c.isRecord()) {
            for (java.lang.reflect.RecordComponent rc : c.getRecordComponents()) {
               java.lang.reflect.Method m = rc.getAccessor();
               m.setAccessible(true);
               Class<?> t = rc.getType();
               if (!gotId && t == int.class) {
                  r.id = (Integer)m.invoke(pk);
                  gotId = true;
               } else if (t == com.mojang.brigadier.suggestion.Suggestions.class) {
                  collect((com.mojang.brigadier.suggestion.Suggestions)m.invoke(pk), r);
               } else if (List.class.isAssignableFrom(t)) {
                  for (Object o : (List<?>)m.invoke(pk)) {
                     r.names.add(entryText(o));
                  }
               }
            }
         } else {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
               if (m.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                  continue;
               }

               m.setAccessible(true);
               if (!gotId && m.getReturnType() == int.class) {
                  r.id = (Integer)m.invoke(pk);
                  gotId = true;
               } else if (m.getReturnType() == com.mojang.brigadier.suggestion.Suggestions.class) {
                  collect((com.mojang.brigadier.suggestion.Suggestions)m.invoke(pk), r);
               }
            }
         }

         return gotId ? r : null;
      } catch (Throwable t) {
         return null;
      }
   }

   private static void collect(com.mojang.brigadier.suggestion.Suggestions sg, Parsed r) {
      if (sg != null) {
         for (Suggestion x : sg.getList()) {
            r.names.add(x.getText());
         }
      }
   }

   /** Oneri girdisinin metni: brigadier Suggestion ya da record (ilk String bileseni). */
   private static String entryText(Object o) throws Exception {
      if (o instanceof Suggestion sg) {
         return sg.getText();
      }

      if (o != null && o.getClass().isRecord()) {
         for (java.lang.reflect.RecordComponent rc : o.getClass().getRecordComponents()) {
            if (rc.getType() == String.class) {
               java.lang.reflect.Method m = rc.getAccessor();
               m.setAccessible(true);
               return (String)m.invoke(o);
            }
         }
      }

      return null;
   }

   /** ClientPlayNetworkHandlerMixin'den cagrilir (ana thread). Bizim istegimizin cevabiysa true doner. */
   public static boolean onSuggestions(CommandSuggestionsS2CPacket pk) {
      Parsed ps = parse(pk);
      if (ps == null) {
         return false;
      }

      int id = ps.id;
      if (id > ID_BASE || id <= ID_BASE - CMDS.length) {
         return false;
      }

      ClientPlayNetworkHandler h = mc.getNetworkHandler();
      if (!waiting || id != waitId || h == null || mc.player == null) {
         return true;
      }

      waiting = false;
      long now = System.currentTimeMillis();
      for (String n : ps.names) {
         if (n != null && NAME.matcher(n).matches()) {
            cycle.add(n);
            cycleAny = true;
         }
      }

      // her komutu sirayla dene (bazi sunucular vanish'tekileri /msg'den gizler ama /tp'den gizlemez); tur bitince degerlendir
      cmdIdx = (cmdIdx + 1) % CMDS.length;
      if (cmdIdx != 0) {
         nextScan = now + 1000L;
         return true;
      }

      nextScan = now + Math.round(Config.avScan * 1000.0F);
      if (!cycleAny) {
         cycle.clear();
         return true; // hicbir komut isim dondurmedi (sunucuda yok / izin yok)
      }

      Set<String> tab = new HashSet<>();
      for (PlayerListEntry e : h.getPlayerList()) {
         tab.add(e.getProfile().getName().toLowerCase());
      }

      String me = mc.player.getGameProfile().getName();
      Set<String> found = new HashSet<>();
      for (String n : cycle) {
         if (n.equalsIgnoreCase(me) || tab.contains(n.toLowerCase())) {
            continue;
         }

         found.add(n);
      }

      cycle.clear();
      cycleAny = false;

      // proxy (Velocity/Bungee) agindaki diger sunucularin oyunculari da tamamlamada gelir: cok fazla "eksik" isim = ag listesi.
      // Bu durumda sadece elle girilmis / rutbesi bilinen yetkililer sayilir, yoksa yanlis alarm yagar.
      if (found.size() > 8) {
         found.removeIf(n -> !listed(n.toLowerCase()) && !staff.contains(n.toLowerCase()));
      }

      // yeni giren oyuncu tab listesine birkac tick gec dusebilir: iki taramada ust uste gorunmeli
      scanFlag.clear();
      for (String n : found) {
         if (missing.merge(n, 1, Integer::sum) >= 2) {
            scanFlag.put(n, "vanish modu");
         }
      }

      missing.keySet().retainAll(found);
      return true;
   }

   /** ClientPlayNetworkHandlerMixin'den cagrilir (ana thread). */
   public static void onBreak(BlockBreakingProgressS2CPacket pk) {
      if (!Kuro.on[Kuro.ANTIVANISH] || Config.avDig < 0.5F || Config.avAnon < 0.5F || mc.player == null || mc.world == null) {
         return;
      }

      int id = pk.getEntityId();
      if (id == mc.player.getId() || pk.getProgress() < 0 || mc.world.getEntityById(id) != null) {
         return;
      }

      BlockPos bp = pk.getPos();
      Vec3d c = new Vec3d(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
      if (mc.player.getPos().distanceTo(c) > Config.avRange) {
         return;
      }

      int n = breakCount.merge(id, 1, Integer::sum);
      if (n < 2 || breakAlerted.containsKey(id)) {
         return; // tek paket yanlis alarm olabilir (entity henuz spawn olmamis); ikinciyi bekle
      }

      breakAlerted.put(id, System.currentTimeMillis());
      alert("§cGörünmez biri kazıyor", "Görünmez biri " + bp.getX() + " " + bp.getY() + " " + bp.getZ() + " bloğunu kırıyor (vanish)", c);
   }

   private static boolean ready() {
      return Kuro.on[Kuro.ANTIVANISH] && mc.player != null && mc.world != null;
   }

   private static boolean allow(String key, long gap) {
      long now = System.currentTimeMillis();
      if (cool.size() > 200) {
         cool.values().removeIf(t -> now - t > 60000L);
      }

      Long t = cool.get(key);
      if (t != null && now - t < gap) {
         return false;
      }

      cool.put(key, now);
      return true;
   }

   /** Sandik / ender chest / shulker acilis animasyonu (dunyaya yayinlanir, acan gorunmez olsa da gelir). */
   public static void onChest(BlockEventS2CPacket pk) {
      if (!ready() || Config.avChest < 0.5F || Config.avAnon < 0.5F || pk.getType() != 1 || pk.getData() <= 0) {
         return;
      }

      Block b = pk.getBlock();
      if (!(b instanceof AbstractChestBlock) && !(b instanceof ShulkerBoxBlock)) {
         return;
      }

      BlockPos bp = pk.getPos();
      Vec3d c = new Vec3d(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5);
      if (mc.player.getPos().distanceTo(c) > Config.avRange || !allow("chest" + bp.asLong(), 5000L)) {
         return;
      }

      PendChest pc = new PendChest();
      pc.c = c;
      chests.add(pc);
   }

   /** Esya / xp alma animasyonu: alan entity dunyada yoksa gorunmez biridir. */
   public static void onPickup(ItemPickupAnimationS2CPacket pk) {
      if (!ready() || Config.avPick < 0.5F || Config.avAnon < 0.5F) {
         return;
      }

      int id = pk.getCollectorEntityId();
      if (id == mc.player.getId() || mc.world.getEntityById(id) != null) {
         return;
      }

      Entity item = mc.world.getEntityById(pk.getEntityId());
      if (item == null || mc.player.getPos().distanceTo(item.getPos()) > Config.avRange || !allow("pick" + id, 4000L)) {
         return;
      }

      alert("§cGörünmez biri eşya aldı", "Görünmez biri yakında eşya aldı (vanish)", item.getPos());
   }

   /** Oyuncu yurume sesi: yakinda hicbir oyuncu entity'si yokken ust uste iki adim sesi = gorunmez yuruyen biri. */
   public static void onSound(PlaySoundS2CPacket pk) {
      if (!ready() || Config.avStep < 0.5F || Config.avAnon < 0.5F || pk.getCategory() != SoundCategory.PLAYERS || !pk.getSound().getIdAsString().endsWith(".step")) {
         return;
      }

      Vec3d pos = new Vec3d(pk.getX(), pk.getY(), pk.getZ());
      double d = mc.player.getPos().distanceTo(pos);
      if (d > Config.avRange || d < 1.5) {
         return;
      }

      for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
         if (p != mc.player && p.getPos().distanceTo(pos) < 4.0) {
            return; // gorunen bir oyuncu
         }
      }

      long now = System.currentTimeMillis();
      if (lastStepPos != null && now - lastStep < 3000L && lastStepPos.distanceTo(pos) < 12.0 && allow("step", 6000L)) {
         alert("§cGörünmez biri yürüyor", "Görünmez biri yakınında yürüyor (vanish)", pos);
      }

      lastStep = now;
      lastStepPos = pos;
   }

   // ------------------------------------------------------------------ HUD
   public static void hud(DrawContext dc, RenderTickCounter tc) {
      if (!Kuro.on[Kuro.ANTIVANISH] || Config.avText < 0.5F || mc.player == null || mc.options.hudHidden) {
         return;
      }

      long now = System.currentTimeMillis();
      int w = dc.getScaledWindowWidth();
      int h = dc.getScaledWindowHeight();
      if (Config.avList >= 0.5F && !live.isEmpty()) {
         int y = h / 2 - 20;
         dc.drawTextWithShadow(mc.textRenderer, Text.literal(Config.avStaff >= 0.5F ? "Vanish'teki yetkililer:" : "Vanish / spectator:"), 6, y, 0xFFFF5555);
         for (Map.Entry<String, String> en : live.entrySet()) {
            y += 10;
            dc.drawTextWithShadow(mc.textRenderer, Text.literal(en.getKey() + " §7(" + en.getValue() + ")"), 6, y, 0xFFFFFFFF);
         }
      }

      if (now > warnUntil) {
         return;
      }

      float left = (warnUntil - now) / 1000.0F;
      float fade = Math.min(1.0F, left * 2.0F);
      float pulse = 0.75F + 0.25F * (float)Math.sin(now / 160.0);
      int a = Math.max(8, (int)(255.0F * fade * pulse));
      int col = (a << 24) | 0xFF3030;

      Text big = Text.literal("İZLENİYORSUN");
      float sc = 2.5F;
      dc.getMatrices().push();
      dc.getMatrices().scale(sc, sc, 1.0F);
      int bw = mc.textRenderer.getWidth(big);
      dc.drawTextWithShadow(mc.textRenderer, big, Math.round((w / sc - bw) / 2.0F), Math.round((h / 2.0F - 30.0F) / sc), col);
      dc.getMatrices().pop();

      Text sub = Text.literal(warnLine);
      int sw = mc.textRenderer.getWidth(sub);
      int subCol = (Math.max(8, (int)(255.0F * fade)) << 24) | 0xFFFFFF;
      dc.drawTextWithShadow(mc.textRenderer, sub, (w - sw) / 2, h / 2 + 2, subCol);
   }

   // ------------------------------------------------------------------ Dunya: kutu
   public static boolean anyWorld() {
      return Kuro.on[Kuro.ANTIVANISH] && Config.avBox >= 0.5F && (!marks.isEmpty() || !live.isEmpty());
   }

   public static void world(BufferBuilder b, Matrix4f m) {
      long now = System.currentTimeMillis();
      if (mc.world != null) {
         for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && live.containsKey(p.getGameProfile().getName())) {
               // vanish'teki oyuncunun gercek konumu (turuncu kutu)
               Render.box(b, m, p.getX() - 0.3, p.getY(), p.getZ() - 0.3, p.getX() + 0.3, p.getY() + 1.8, p.getZ() + 0.3, 1.0F, 0.6F, 0.1F, 1.0F);
            }
         }
      }

      for (Mark mk : marks) {
         if (mk.until < now) {
            continue;
         }

         float k = Math.min(1.0F, (mk.until - now) / 1000.0F);
         Vec3d p = mk.pos;
         // oyuncu boyunda kirmizi kutu (son gorulen / kazilan konum)
         Render.box(b, m, p.x - 0.3, p.y - 0.5, p.z - 0.3, p.x + 0.3, p.y + 1.3, p.z + 0.3, 1.0F, 0.2F, 0.2F, k);
      }
   }
}
