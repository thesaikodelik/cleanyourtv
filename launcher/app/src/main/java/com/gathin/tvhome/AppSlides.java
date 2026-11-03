package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Behind the home screen, while a dock app that publishes a preview channel is in focus: its titles one
 * after another, each picture full screen and darkened, with its name and a line about it at the top end.
 * 30nama publishes its home page's slider so (its Slides.kt); YouTube publishes its recommendations. Only
 * searchable rows can be read without ACCESS_ALL_EPG_DATA. Gone the moment focus leaves the app. Theme key app_slides.
 */
final class AppSlides {

    private static final String TAG = "TvHomeSlides";
    private static final long EVERY_MS = 7000;
    private static final String[] COLUMNS = {
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
    };

    static final class Slide { String title, line, picture; int weight; }

    private final Context context;
    private final FrameLayout root;
    private final float density;
    private final ImageView[] pictures = new ImageView[2];
    private final View layer;
    private final TextView title, line;
    private final LinearLayout caption;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<>(10);
    private String pkg;
    /** slides_off_apps: their channel pictures are not worth a full screen; the wallpaper stays behind them. */
    private final java.util.Set<String> off;
    private List<Slide> slides = new ArrayList<>();
    private int index, front;
    private final Runnable next = this::advance;

    /** Adds its layers to [root]: the pictures at [behindIndex], under the dock and the clock; the words on top. */
    AppSlides(Context context, FrameLayout root, int behindIndex, List<String> slidesOff) {
        this.context = context;
        this.off = new java.util.HashSet<>(slidesOff);
        this.root = root;
        this.density = context.getResources().getDisplayMetrics().density;
        FrameLayout box = new FrameLayout(context);
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(context);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            box.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pictures[i] = v;
        }
        // Dark enough that the clock, the row and the dock stay readable over any picture.
        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x55000000, 0xCC000000}));
        box.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.setAlpha(0f);
        box.setVisibility(View.GONE);
        layer = box;
        root.addView(box, behindIndex, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        caption = new LinearLayout(context);
        caption.setOrientation(LinearLayout.VERTICAL);
        // On the right, under the top buttons, its lines set to the right edge.
        caption.setGravity(Gravity.RIGHT);
        title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.create(title.getTypeface(), Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(6), 0, dp(1), 0xAA000000);
        line = new TextView(context);
        line.setTextColor(0xD9FFFFFF);
        line.setTextSize(12);
        line.setMaxLines(1);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setGravity(Gravity.RIGHT);
        line.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        title.setGravity(Gravity.RIGHT);
        title.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        line.setShadowLayer(dp(4), 0, dp(1), 0xAA000000);
        // Each line only as wide as its words, at most the caption's width: a Persian line left-aligned
        // in a wider box was cut at its start.
        line.setMaxWidth(dp(460));
        line.setMaxLines(2);
        title.setMaxWidth(dp(460));
        // Lines set to the block's right edge, as Persian reads.
        caption.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        caption.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        clp.topMargin = dp(112);
        clp.rightMargin = dp(60);
        caption.setAlpha(0f);
        caption.setVisibility(View.GONE);
        root.addView(caption, clp);
    }

    /** The dock app now in focus, or null when focus left the dock. */
    void focus(String packageName) {
        if (TextUtils.equals(packageName, pkg)) return;
        pkg = packageName;
        main.removeCallbacks(next);
        if (packageName == null) { hide(); return; }
        io.execute(() -> {
            List<Slide> read = read(packageName);
            main.post(() -> {
                if (!TextUtils.equals(packageName, pkg)) return;
                slides = read;
                if (read.isEmpty()) { hide(); return; }
                index = 0;
                show(read.get(0));
                main.postDelayed(next, EVERY_MS);
            });
        });
    }

    void release() { main.removeCallbacks(next); io.shutdownNow(); }

    private List<Slide> read(String packageName) {
        if (off.contains(packageName)) return new ArrayList<>();
        List<Slide> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                if (!packageName.equals(c.getString(4))) continue;
                // Only the wide pictures: a tall poster stretched across the screen looks wrong.
                if (!c.isNull(5) && c.getInt(5) != TvContract.PreviewPrograms.ASPECT_RATIO_16_9) continue;
                Slide s = new Slide();
                s.title = c.getString(0);
                s.line = c.getString(1);
                s.picture = c.getString(2);
                s.weight = c.isNull(3) ? 0 : c.getInt(3);
                if (TextUtils.isEmpty(s.picture)) continue;
                out.add(s);
            }
        } catch (RuntimeException ex) {
            Log.w(TAG, "slides: " + ex);
        }
        out.sort((a, b) -> Integer.compare(b.weight, a.weight));
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    private void advance() {
        if (slides.isEmpty()) return;
        index = (index + 1) % slides.size();
        show(slides.get(index));
        main.postDelayed(next, EVERY_MS);
    }

    private void show(Slide s) {
        String wanted = pkg;
        load(s.picture, bitmap -> {
            if (bitmap == null || !TextUtils.equals(wanted, pkg)) return;
            front = 1 - front;
            ImageView in = pictures[front], out = pictures[1 - front];
            in.setImageBitmap(bitmap);
            in.setScaleX(1.04f);
            in.setScaleY(1.04f);
            in.animate().alpha(1f).setDuration(900).start();
            // A slow drift while it is up, as the stock home screen's backdrop does.
            in.animate().scaleX(1f).scaleY(1f).setDuration(EVERY_MS + 900).start();
            out.animate().alpha(0f).setDuration(900).start();
            title.setText(s.title);
            line.setText(s.line == null ? "" : s.line);
            line.setVisibility(TextUtils.isEmpty(s.line) ? View.GONE : View.VISIBLE);
            layer.setVisibility(View.VISIBLE);
            wallpaperCaption(false);
            caption.setVisibility(View.VISIBLE);
            layer.animate().alpha(1f).setDuration(400).start();
            caption.animate().alpha(1f).setDuration(400).start();
        });
    }

    /** The wallpaper's own caption sits where the title's name goes: away while slides show. */
    private void wallpaperCaption(boolean shown) {
        View c = root.findViewWithTag("wallpaperCaption");
        if (c != null) c.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
    }

    private void hide() {
        wallpaperCaption(true);
        layer.animate().alpha(0f).setDuration(300).withEndAction(() -> { if (pkg == null || slides.isEmpty()) layer.setVisibility(View.GONE); }).start();
        caption.animate().alpha(0f).setDuration(200).withEndAction(() -> { if (pkg == null || slides.isEmpty()) caption.setVisibility(View.GONE); }).start();
    }

    interface Done { void bitmap(Bitmap b); }

    /** The picture, at the screen's width at most, from its https address. */
    private void load(String address, Done done) {
        Bitmap cached = cache.get(address);
        if (cached != null) { done.bitmap(cached); return; }
        io.execute(() -> {
            Bitmap b = null;
            HttpURLConnection http = null;
            try {
                InputStream in;
                if (address.startsWith("http")) {
                    http = (HttpURLConnection) new URL(address).openConnection();
                    http.setConnectTimeout(6000);
                    http.setReadTimeout(12000);
                    in = http.getInputStream();
                } else {
                    in = context.getContentResolver().openInputStream(android.net.Uri.parse(address));
                }
                try (InputStream s = in) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap full = BitmapFactory.decodeStream(s, null, o);
                    int max = context.getResources().getDisplayMetrics().widthPixels;
                    if (full != null && full.getWidth() > max) b = Bitmap.createScaledBitmap(full, max, full.getHeight() * max / full.getWidth(), true);
                    else b = full;
                }
            } catch (Exception ex) {
                Log.w(TAG, "picture: " + ex);
            } finally {
                if (http != null) http.disconnect();
            }
            Bitmap got = b;
            if (got != null) cache.put(address, got);
            main.post(() -> done.bitmap(got));
        });
    }

    private int dp(float v) { return Math.round(v * density); }
}
