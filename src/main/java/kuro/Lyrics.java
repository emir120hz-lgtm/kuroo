package kuro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Senkron (zaman damgali) sarki sozleri: lrclib.net'ten (ucretsiz, anahtarsiz) arka planda indirilir.
 * SpotifyHUD, calan sarkinin konumuna gore o anki satiri gosterir.
 */
public final class Lyrics {
   public static volatile String state = "";   // "" / "loading" / "ok" / "none"
   public static volatile int forTrack = -1;   // sozlerin ait oldugu Spotify.trackId
   public static volatile long[] times = new long[0]; // ms
   public static volatile String[] lines = new String[0];
   public static volatile boolean turkish;     // sozler Turkce mi (buyuk harf: i -> İ)

   private static volatile int reqTrack = -1;
   private static final Pattern LRC = Pattern.compile("^\\[(\\d+):(\\d+(?:[.,]\\d+)?)\\]\\s*(.*)$");
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

   private Lyrics() {
   }

   /** Her HUD karesinde cagrilir; sarki degistiyse sozleri arka planda ister. */
   public static void tick() {
      int id = Spotify.trackId;
      if (id == reqTrack || !Spotify.running || Spotify.title.isEmpty()) {
         return;
      }

      reqTrack = id;
      turkish = false;
      state = "loading";
      forTrack = id;
      times = new long[0];
      lines = new String[0];
      final String a = Spotify.artist;
      final String t = Spotify.title;
      final double d = Spotify.durSec;
      Thread th = new Thread(() -> load(id, a, t, d), "kuro-lyrics");
      th.setDaemon(true);
      th.start();
   }

   /** pos (sn) anindaki satirin indeksi; henuz ilk satir baslamadiysa -1. */
   public static int indexAt(double pos) {
      long[] ts = times;
      long ms = (long)(pos * 1000.0);
      int lo = 0;
      int hi = ts.length - 1;
      int ans = -1;
      while (lo <= hi) {
         int mid = (lo + hi) >>> 1;
         if (ts[mid] <= ms) {
            ans = mid;
            lo = mid + 1;
         } else {
            hi = mid - 1;
         }
      }

      return ans;
   }

   private static void load(int id, String artist, String title, double dur) {
      try {
         String clean = title.replaceAll("\\s*[-–]\\s*(?i:remaster|live|mono|stereo|version|edit|radio|single|from).*$", "").trim();
         String synced = null;
         String url = "https://lrclib.net/api/get?artist_name=" + enc(artist) + "&track_name=" + enc(clean)
            + (dur > 0.0 ? "&duration=" + Math.round(dur) : "");
         String body = fetch(url);
         if (body != null) {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            synced = str(o, "syncedLyrics");
         }

         if (synced == null || synced.isBlank()) {
            String sb = fetch("https://lrclib.net/api/search?track_name=" + enc(clean) + "&artist_name=" + enc(artist));
            if (sb != null) {
               JsonArray arr = JsonParser.parseString(sb).getAsJsonArray();
               for (JsonElement el : arr) {
                  String s = str(el.getAsJsonObject(), "syncedLyrics");
                  if (s != null && !s.isBlank()) {
                     synced = s;
                     break;
                  }
               }
            }
         }

         if (id != Spotify.trackId) {
            return; // bu arada sarki degisti
         }

         if (synced == null || synced.isBlank()) {
            state = "none";
            return;
         }

         List<Long> tl = new ArrayList<>();
         List<String> ll = new ArrayList<>();
         for (String raw : synced.split("\\r?\\n")) {
            Matcher m = LRC.matcher(raw.trim());
            if (!m.matches()) {
               continue;
            }

            double sec = Integer.parseInt(m.group(1)) * 60.0 + Double.parseDouble(m.group(2).replace(',', '.'));
            tl.add((long)(sec * 1000.0));
            ll.add(m.group(3).trim());
         }

         if (tl.isEmpty()) {
            state = "none";
            return;
         }

         long[] ta = new long[tl.size()];
         for (int i = 0; i < ta.length; i++) {
            ta[i] = tl.get(i);
         }

         int trChars = 0;
         for (String l : ll) {
            for (int k = 0; k < l.length(); k++) {
               char c = l.charAt(k);
               if (c == 'ı' || c == 'İ' || c == 'ğ' || c == 'Ğ' || c == 'ş' || c == 'Ş') {
                  trChars++;
               }
            }
         }

         turkish = trChars >= 3;
         times = ta;
         lines = ll.toArray(new String[0]);
         forTrack = id;
         state = "ok";
      } catch (Exception e) {
         if (id == Spotify.trackId) {
            state = "none";
         }
      }
   }

   private static String str(JsonObject o, String k) {
      JsonElement e = o.get(k);
      return e == null || e.isJsonNull() ? null : e.getAsString();
   }

   private static String enc(String s) {
      return URLEncoder.encode(s, StandardCharsets.UTF_8);
   }

   private static String fetch(String url) throws Exception {
      HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Kuro/1.27").timeout(Duration.ofSeconds(8)).GET().build();
      HttpResponse<String> rs = HTTP.send(rq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return rs.statusCode() == 200 ? rs.body() : null;
   }
}
