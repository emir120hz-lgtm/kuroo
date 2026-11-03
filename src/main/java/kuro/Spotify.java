package kuro;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * SpotifyHUD veri kaynagi: yerel Spotify masaustu uygulamasindan calan sarkiyi okur (hesap girisi / API anahtari yok).
 * Windows: Windows medya oturumu (SMTC) -> sanatci, sarki, konum, sure, kapak; olmazsa Spotify pencere basligi.
 * macOS: AppleScript. Linux: playerctl (MPRIS). Sorgu arka plan thread'inde yapilir, oyunu yavaslatmaz.
 */
public final class Spotify {
   public static volatile boolean running;   // Spotify acik mi
   public static volatile boolean playing;   // su an caliyor mu
   public static volatile String title = "";
   public static volatile String artist = "";
   public static volatile double durSec = -1.0; // sarki suresi (sn), bilinmiyorsa -1
   public static volatile String cover = "";    // yerel kapak resmi dosyasi ("" = yok)
   public static volatile int trackId;          // sarki her degistiginde artar

   private static volatile double posBase;      // son bilinen konum (sn)
   private static volatile long posStamp = System.nanoTime();

   private static volatile boolean busy;
   private static boolean smtcDead;
   private static int smtcErr;
   private static long lastPoll;
   private static final String OS = System.getProperty("os.name", "").toLowerCase();

   private static final String SMTC =
      "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
      + "try{"
      + "Add-Type -AssemblyName System.Runtime.WindowsRuntime;"
      + "$null=[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime];"
      + "$null=[Windows.Storage.Streams.IRandomAccessStreamWithContentType,Windows.Storage.Streams,ContentType=WindowsRuntime];"
      + "$m=([System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'})[0];"
      + "function Aw($op,$t){$n=$m.MakeGenericMethod($t).Invoke($null,@($op));$n.Wait(-1)|Out-Null;$n.Result};"
      + "$mgr=Aw ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]);"
      + "$s=$null;foreach($x in $mgr.GetSessions()){if($x.SourceAppUserModelId -like '*potify*'){$s=$x;break}};"
      + "if(-not $s){'NONE';exit};"
      + "$p=Aw ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]);"
      + "$tl=$s.GetTimelineProperties();"
      + "$st=$s.GetPlaybackInfo().PlaybackStatus.ToString();"
      + "$age=([DateTimeOffset]::UtcNow-$tl.LastUpdatedTime).TotalMilliseconds;"
      + "$cov='';"
      + "$f=Join-Path $env:TEMP ('kuro-cover-'+[Math]::Abs(($p.Artist+'|'+$p.Title).GetHashCode())+'.img');"
      + "if((-not (Test-Path $f)) -and $p.Thumbnail){try{"
      + "$rs=Aw ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType]);"
      + "$in=[System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($rs);"
      + "$fs=[IO.File]::Create($f);$in.CopyTo($fs);$fs.Close();$in.Close()}catch{}};"
      + "if((Test-Path $f) -and ((Get-Item $f).Length -gt 0)){$cov=$f};"
      + "'OK|||'+$st+'|||'+$p.Artist+'|||'+$p.Title+'|||'+[int64]$tl.Position.TotalMilliseconds+'|||'+[int64]$tl.EndTime.TotalMilliseconds+'|||'+[int64]$age+'|||'+$cov"
      + "}catch{'ERR'}";

   private Spotify() {
   }

   /** Su anki konum (sn): son sorgudaki konum + gecen sure (caliyorsa). */
   public static double position() {
      double p = posBase + (playing ? (System.nanoTime() - posStamp) / 1.0E9 : 0.0);
      double d = durSec;
      if (d > 0.0 && p > d) {
         p = d;
      }

      return Math.max(0.0, p);
   }

   /** 0..1, sure bilinmiyorsa -1. */
   public static float progressNow() {
      double d = durSec;
      return d > 0.0 ? (float)Math.min(1.0, position() / d) : -1.0F;
   }

   private static void setPos(double sec) {
      double est = position();
      if (!playing || Math.abs(est - sec) > 0.4) {
         posBase = sec;
         posStamp = System.nanoTime();
      }
   }

   private static void newTrack(String a, String t, double startGuess) {
      if (!a.equals(artist) || !t.equals(title)) {
         artist = a;
         title = t;
         posBase = startGuess;
         posStamp = System.nanoTime();
         durSec = -1.0;
         cover = "";
         trackId++;
      }
   }

   /** Modul aciksa her tick cagrilir; araliklarla arka planda sorgu baslatir. */
   public static void tick() {
      long now = System.currentTimeMillis();
      long every = OS.contains("win") ? 1500L : 1000L;
      if (busy || now - lastPoll < every) {
         return;
      }

      lastPoll = now;
      busy = true;
      Thread t = new Thread(() -> {
         try {
            poll();
         } catch (Exception ignored) {
         } finally {
            busy = false;
         }
      }, "kuro-spotify");
      t.setDaemon(true);
      t.start();
   }

   private static void poll() throws Exception {
      if (OS.contains("win")) {
         pollWindows();
      } else if (OS.contains("mac")) {
         pollMac();
      } else {
         pollLinux();
      }
   }

   private static void pollWindows() throws Exception {
      if (!smtcDead) {
         String out = run("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand",
            Base64.getEncoder().encodeToString(SMTC.getBytes(StandardCharsets.UTF_16LE)));
         if (out != null) {
            for (String ln : out.split("\\r?\\n")) {
               if (ln.startsWith("OK|||")) {
                  smtcErr = 0;
                  applySmtc(ln);
                  return;
               }
            }

            if (out.contains("ERR") && ++smtcErr >= 3) {
               smtcDead = true; // bu sistemde medya oturumu okunamiyor: pencere basligina don
            }
         }
      }

      pollWindowsTitle();
   }

   private static void applySmtc(String line) {
      String[] p = line.split("\\|\\|\\|", -1);
      if (p.length < 7) {
         return;
      }

      running = true;
      String a = p[2].trim();
      String t = p[3].trim();
      boolean pl = p[1].trim().equalsIgnoreCase("Playing");
      newTrack(a, t, 0.0);
      long pos = parseLong(p[4]);
      long end = parseLong(p[5]);
      long age = Math.max(0L, Math.min(5000L, parseLong(p[6])));
      if (end > 0L) {
         durSec = end / 1000.0;
      }

      boolean was = playing;
      playing = pl;
      setPos((pos + (pl ? age : 0L)) / 1000.0);
      if (!was && pl) {
         posStamp = System.nanoTime();
      }

      cover = p.length > 7 ? p[7].trim() : "";
   }

   private static long parseLong(String s) {
      try {
         return Long.parseLong(s.trim());
      } catch (NumberFormatException e) {
         return 0L;
      }
   }

   private static void pollWindowsTitle() throws Exception {
      String ps = "[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
         + "$p=Get-Process Spotify -ErrorAction SilentlyContinue;"
         + "if(-not $p){'NONE'}else{$w=$p|Where-Object{$_.MainWindowTitle}|Select-Object -First 1;if($w){'T:'+$w.MainWindowTitle}else{'T:'}}";
      String enc = Base64.getEncoder().encodeToString(ps.getBytes(StandardCharsets.UTF_16LE));
      String out = run("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", enc);
      if (out == null || out.startsWith("NONE") || out.isBlank()) {
         running = false;
         playing = false;
         return;
      }

      running = true;
      String t = out.startsWith("T:") ? out.substring(2).trim() : out.trim();
      if (t.isEmpty() || t.equals("Spotify") || t.startsWith("Spotify ") || t.equals("Advertisement")) {
         playing = false; // duraklatildi / reklam: son sarki bilgisi kalir
         return;
      }

      int i = t.indexOf(" - ");
      String a = i > 0 ? t.substring(0, i).trim() : "";
      String ti = i > 0 ? t.substring(i + 3).trim() : t;
      boolean was = playing;
      newTrack(a, ti, 1.0); // pencere basligindan konum okunamaz: sarki basindan ~1 sn sonra say
      playing = true;
      if (!was) {
         posStamp = System.nanoTime();
      }
   }

   private static void pollMac() throws Exception {
      String script = "if application \"Spotify\" is running then\n"
         + "tell application \"Spotify\"\n"
         + "if player state is stopped then return \"STOP\"\n"
         + "return (artist of current track) & \"|||\" & (name of current track) & \"|||\" & (player state as string) & \"|||\" & (duration of current track) & \"|||\" & (player position)\n"
         + "end tell\nelse\nreturn \"NONE\"\nend if";
      String out = run("osascript", "-e", script);
      parseDelimited(out, 1000.0, 1.0);
   }

   private static void pollLinux() throws Exception {
      String out = run("playerctl", "-p", "spotify", "metadata", "--format",
         "{{artist}}|||{{title}}|||{{status}}|||{{mpris:length}}|||{{position}}");
      parseDelimited(out, 1_000_000.0, 1_000_000.0);
   }

   /** "sanatci|||sarki|||durum|||uzunluk|||konum" bicimini isler (birimler: uzunluk = lenDiv/sn, konum = posDiv/sn). */
   private static void parseDelimited(String out, double lenDiv, double posDiv) {
      if (out == null || out.isBlank() || out.startsWith("NONE") || out.startsWith("STOP") || !out.contains("|||")) {
         running = out != null && out.startsWith("STOP");
         playing = false;
         return;
      }

      String[] p = out.trim().split("\\|\\|\\|", -1);
      if (p.length < 3) {
         running = false;
         playing = false;
         return;
      }

      running = true;
      newTrack(p[0].trim(), p[1].trim(), 0.0);
      boolean pl = p[2].trim().equalsIgnoreCase("playing");
      playing = pl;
      if (p.length >= 5) {
         try {
            double len = Double.parseDouble(p[3].trim().replace(',', '.')) / lenDiv;
            double pos = Double.parseDouble(p[4].trim().replace(',', '.')) / posDiv;
            if (len > 0.0) {
               durSec = len;
            }

            setPos(pos);
         } catch (NumberFormatException ignored) {
         }
      }
   }

   private static String run(String... cmd) {
      try {
         Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
         ByteArrayOutputStream bo = new ByteArrayOutputStream();
         try (InputStream in = pr.getInputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 16384) {
               bo.write(buf, 0, n);
            }
         }

         if (!pr.waitFor(6, TimeUnit.SECONDS)) {
            pr.destroyForcibly();
            return null;
         }

         return bo.toString(StandardCharsets.UTF_8).trim();
      } catch (Exception e) {
         return null; // komut yok (playerctl kurulu degil vb.)
      }
   }
}
