package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV's own screensaver photos (Google's Ambient Mode, the Chromecast backdrop) among the wallpapers, each shown
 * once and never again (theme key screensaver_photos). Google's public backdrop page lists 50 of them, mostly different on every
 * visit; a few are downloaded ahead into files/backdrop so a change never waits on the network, and the one on
 * screen is the only one kept after it has been shown.
 */
final class BackdropPhotos {

    private static final String TAG = "TvHomeBackdrop";
    private static final String FEED = "https://clients3.google.com/cast/chromecast/home";
    /** The page answers a Chromecast with the photo list. */
    private static final String AGENT = "Mozilla/5.0 (X11; Linux armv7l) CrKey/1.56.500000";
    static final String PREFIX = "bd_";
    private static final int AHEAD = 3, SEEN_KEPT = 3000;

    private static BackdropPhotos instance;

    static synchronized BackdropPhotos get(Context c) {
        if (instance == null) instance = new BackdropPhotos(c.getApplicationContext());
        return instance;
    }

    private final File dir;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Photos listed by the page and not yet downloaded: address, then the photographer. */
    private final List<String[]> listed = new ArrayList<>();
    private boolean fetching;

    private BackdropPhotos(Context c) {
        dir = new File(c.getFilesDir(), "backdrop");
        dir.mkdirs();
        prefs = c.getSharedPreferences("backdrop", Context.MODE_PRIVATE);
    }

    static boolean owns(String name) { return name != null && name.startsWith(PREFIX); }

    File file(String name) {
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    /** The photographer of a downloaded photo, as its caption; null when unknown. */
    String caption(String name) {
        String by = prefs.getString("by:" + name, null);
        return by == null || by.isEmpty() ? null : "Photo: " + by;
    }

    /** A downloaded photo not shown yet, oldest first; null when none is ready. Asks for more either way. */
    synchronized File take() {
        File[] ready = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".jpg") && !prefs.getBoolean("shown:" + n, false));
        fill();
        if (ready == null || ready.length == 0) return null;
        Arrays.sort(ready, Comparator.comparingLong(File::lastModified));
        return ready[0];
    }

    /** [name] is on screen: every other photo already shown goes from the disk. */
    synchronized void shown(String name) {
        SharedPreferences.Editor e = prefs.edit().putBoolean("shown:" + name, true);
        File[] all = dir.listFiles((d, n) -> n.startsWith(PREFIX));
        if (all != null) for (File f : all) {
            if (f.getName().equals(name) || !prefs.getBoolean("shown:" + f.getName(), false)) continue;
            f.delete();
            e.remove("shown:" + f.getName()).remove("by:" + f.getName());
        }
        e.apply();
        fill();
    }

    /** Keeps [AHEAD] photos ready, off the main thread; the page is read again when its list runs out. */
    private synchronized void fill() {
        if (fetching) return;
        fetching = true;
        io.execute(() -> {
            try {
                while (ready() < AHEAD) {
                    String[] next = nextListed();
                    if (next == null) break;
                    download(next[0], next[1]);
                }
            } catch (Exception ex) {
                Log.w(TAG, "fill: " + ex.getClass().getSimpleName());
            } finally {
                synchronized (this) { fetching = false; }
            }
        });
    }

    private int ready() {
        File[] r = dir.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".jpg") && !prefs.getBoolean("shown:" + n, false));
        return r == null ? 0 : r.length;
    }

    private String[] nextListed() throws Exception {
        Set<String> seen = seen();
        for (int round = 0; round < 3; round++) {
            while (!listed.isEmpty()) {
                String[] p = listed.remove(0);
                if (!seen.contains(key(p[0]))) return p;
            }
            readFeed();
        }
        return null;
    }

    /** The photo without its size suffix, so the same photo at another size still counts as seen. */
    private static String key(String url) {
        int eq = url.lastIndexOf('=');
        return eq > 0 ? url.substring(0, eq) : url;
    }

    private Set<String> seen() {
        String raw = prefs.getString("seen", "");
        return new LinkedHashSet<>(raw.isEmpty() ? new ArrayList<>() : Arrays.asList(raw.split("\n")));
    }

    private void markSeen(String url) {
        Set<String> seen = seen();
        seen.add(key(url));
        List<String> list = new ArrayList<>(seen);
        if (list.size() > SEEN_KEPT) list = list.subList(list.size() - SEEN_KEPT, list.size());
        prefs.edit().putString("seen", String.join("\n", list)).apply();
    }

    private static final Pattern DATA = Pattern.compile("JSON\\.parse\\('(.*?)'\\)", Pattern.DOTALL);

    /** The page's photo list: in a JSON.parse('...') call, escaped as a JavaScript string. */
    private void readFeed() throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(FEED).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", AGENT);
        String page;
        try (InputStream in = h.getInputStream()) { page = new String(readAll(in), StandardCharsets.UTF_8); } finally { h.disconnect(); }
        Matcher m = DATA.matcher(page);
        if (!m.find()) { Log.w(TAG, "feed: no list"); return; }
        JSONArray photos = new JSONArray(unescape(m.group(1))).optJSONArray(0);
        int added = 0;
        for (int k = 0; photos != null && k < photos.length(); k++) {
            JSONArray p = photos.optJSONArray(k);
            if (p == null) continue;
            String url = p.optString(0, null), by = p.optString(1, null);
            // Google's own cards ("Add earth and space photos...") carry no photographer.
            if (url == null || !url.startsWith("https://") || by == null || by.isEmpty() || "null".equals(by)) continue;
            listed.add(new String[]{url, by});
            added++;
        }
        // In no order of the page's: random, as the screensaver shows them.
        java.util.Collections.shuffle(listed);
        Log.i(TAG, "feed: " + added + " photos");
    }

    private void download(String url, String by) throws Exception {
        markSeen(url);
        String name = PREFIX + Long.toString(System.currentTimeMillis(), 36) + ".jpg";
        File part = new File(dir, name + ".part");
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(30000);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ex) {
            part.delete();
            Log.w(TAG, "photo: " + ex.getClass().getSimpleName());
            return;
        } finally {
            h.disconnect();
        }
        prefs.edit().putString("by:" + name, by).apply();
        part.renameTo(new File(dir, name));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    /** A JavaScript single-quoted string's escapes: \xNN, \\uNNNN, \/ and the rest. */
    static String unescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'x': b.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                default: b.append(n);
            }
        }
        return b.toString();
    }
}
