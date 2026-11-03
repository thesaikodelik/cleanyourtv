package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
 * A "Continue watching" row above the dock, read from the system's Watch Next table, as Google TV's own
 * row is. Only rows an app marks searchable can be read without ACCESS_ALL_EPG_DATA (docs/watch-next.md);
 * 30nama marks its rows so. Each card shows the poster, the title, the episode and how far it was watched,
 * and opens the app at that point through the row's own intent. Nothing is kept: the table is read again
 * whenever Home comes back.
 */
final class WatchNextRow {

    interface Host {
        /** Down from a card: back to the dock. */
        void onRowDown();
        /** Up from a card: the top bar. */
        void onRowUp();
    }

    private static final String TAG = "TvHomeWatchNext";
    private static final int MAX = 10;
    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
    };

    static final class Entry {
        String title, episode, poster, intent, pkg;
        long position, duration, engaged;
    }

    private final Context context;
    private final Host host;
    private final float density;
    private final HorizontalScrollView scroller;
    private final LinearLayout strip;
    private final TextView heading;
    private final View container;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> posters = new LruCache<>(12);
    private List<Entry> shown = new ArrayList<>();
    /** Everything read from the table; the row shows only the focused dock app's part of it. */
    private List<Entry> all = new ArrayList<>();
    /** The dock app in focus whose rows are shown, or null: the row is away. */
    private String pkg;
    /** The dock tiles' size and spacing, so the cards line up with the icons above them. */
    private int cardW, cardH, gap;

    /** Run when the row comes or goes, so the grid can make room for it or close the gap. */
    Runnable changed;

    WatchNextRow(Context context, Host host, ViewGroup parent) {
        this.context = context;
        this.host = host;
        this.density = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        heading = new TextView(context);
        heading.setText(context.getString(R.string.watch_next));
        heading.setTextColor(0xCCFFFFFF);
        heading.setTextSize(11);
        heading.setLetterSpacing(0.12f);
        heading.setAllCaps(true);
        // On the left above the first card, whatever the script of the words.
        heading.setGravity(Gravity.LEFT);
        heading.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        heading.setPadding(dp(4), 0, 0, dp(4));
        box.addView(heading);
        scroller = new HorizontalScrollView(context) {
            // Focus on the first card scrolls the row a few pixels to make room for its zoom: no fade there,
            // only once a card has really been scrolled past.
            @Override
            protected float getLeftFadingEdgeStrength() {
                if (getScrollX() < dp(60)) return 0f;
                // The focused card sits at the dock's first tile: the fade covers only the gap left of it.
                return Math.min(1f, strip.getPaddingLeft() / (float) getHorizontalFadingEdgeLength());
            }

            // Android scrolls a focused card clear of the fading edge; for the first one that moved the row
            // left, and the fade's layer cut off the left of its zoom. The first card keeps the row at rest.
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                if (rect.left <= strip.getPaddingLeft() + dp(4)) return -getScrollX();
                return super.computeScrollDeltaToGetChildRectOnScreen(rect);
            }
        };
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setFocusable(false);
        // The card at the screen's edge fades out instead of being cut off, and the ones scrolled past fade
        // at the other edge as the row slides.
        scroller.setHorizontalFadingEdgeEnabled(true);
        scroller.setFadingEdgeLength(dp(45));
        strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setClipChildren(false);
        strip.setClipToPadding(false);
        strip.setPadding(dp(4), dp(8), dp(24), dp(12));
        scroller.addView(strip);
        box.addView(scroller);
        container = box;
        container.setVisibility(View.GONE);
        parent.addView(container);
    }

    View view() { return container; }

    boolean hasCards() { return container.getVisibility() == View.VISIBLE && strip.getChildCount() > 0; }

    void focusFirst() {
        if (strip.getChildCount() > 0) strip.getChildAt(0).requestFocus();
    }

    /** Reads the table off the main thread and lays the cards out when it changed. */
    void refresh() {
        io.execute(() -> {
            List<Entry> read = read(context.getContentResolver());
            main.post(() -> { all = read; show(filtered()); });
        });
    }

    void release() { io.shutdownNow(); }

    /**
     * The dock app now in focus: the row shows its Continue watching, if it has any, and goes away
     * otherwise. Null keeps what is shown, for focus in the row.
     */
    void focusApp(String packageName) {
        if (TextUtils.equals(packageName, pkg)) return;
        pkg = packageName;
        show(filtered());
    }

    /** The dock tiles' width, height and gap; cards are made again at that size when it changes. */
    void matchTiles(int w, int h, int spacing, int start) {
        if (w <= 0 || h <= 0 || (w == cardW && h == cardH && spacing == gap)) return;
        cardW = w; cardH = h; gap = spacing;
        strip.setPaddingRelative(start, strip.getPaddingTop(), strip.getPaddingEnd(), strip.getPaddingBottom());
        heading.setPaddingRelative(start, heading.getPaddingTop(), 0, heading.getPaddingBottom());
        List<Entry> now = shown;
        shown = new ArrayList<>();
        show(now);
    }

    void hide() {
        if (pkg == null) return;
        pkg = null;
        show(filtered());
    }

    private List<Entry> filtered() {
        List<Entry> out = new ArrayList<>();
        if (pkg == null) return out;
        for (Entry e : all) if (pkg.equals(e.pkg)) out.add(e);
        return out;
    }

    private List<Entry> read(ContentResolver resolver) {
        List<Entry> out = new ArrayList<>();
        try (Cursor c = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, COLUMNS, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                if (!c.isNull(7) && c.getInt(7) == 0) continue;     // taken off the home screen by the viewer
                Entry e = new Entry();
                e.title = c.getString(0);
                e.episode = c.getString(1);
                e.poster = c.getString(2);
                e.intent = c.getString(3);
                e.position = c.isNull(4) ? 0 : c.getLong(4);
                e.duration = c.isNull(5) ? 0 : c.getLong(5);
                e.engaged = c.isNull(6) ? 0 : c.getLong(6);
                e.pkg = c.getString(8);
                if (TextUtils.isEmpty(e.title) || TextUtils.isEmpty(e.intent)) continue;
                out.add(e);
            }
        } catch (RuntimeException ex) {
            // No READ_TV_LISTINGS yet, or a provider that refuses: the row simply stays away.
            Log.w(TAG, "watch next: " + ex);
        }
        out.sort((a, b) -> Long.compare(b.engaged, a.engaged));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private void show(List<Entry> entries) {
        if (same(entries, shown)) return;
        View focused = strip.findFocus();
        int focusedAt = focused == null ? -1 : strip.indexOfChild(focused);
        shown = entries;
        heading.setText(context.getString(R.string.watch_next));
        strip.removeAllViews();
        for (int i = 0; i < entries.size(); i++) strip.addView(card(entries.get(i), i, entries.size()));
        boolean on = !entries.isEmpty();
        if (on && container.getVisibility() != View.VISIBLE) {
            container.setAlpha(0f);
            container.setVisibility(View.VISIBLE);
            container.animate().alpha(1f).setDuration(250).start();
        } else if (!on) {
            container.setVisibility(View.GONE);
        }
        if (changed != null) changed.run();
        if (focusedAt >= 0 && strip.getChildCount() > 0) strip.getChildAt(Math.min(focusedAt, strip.getChildCount() - 1)).requestFocus();
    }

    private static boolean same(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Entry x = a.get(i), y = b.get(i);
            if (!TextUtils.equals(x.title, y.title) || !TextUtils.equals(x.episode, y.episode)
                    || !TextUtils.equals(x.poster, y.poster) || x.position / 30000 != y.position / 30000) return false;
        }
        return true;
    }

    private View card(Entry e, int index, int count) {
        int w = cardW > 0 ? cardW : dp(168), h = cardH > 0 ? cardH : dp(95);
        FrameLayout card = new FrameLayout(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(gap > 0 ? gap : dp(12));
        card.setLayoutParams(lp);
        card.setFocusable(true);
        card.setClickable(true);
        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(dp(10));
        plate.setColor(0xFF1B1E26);
        card.setBackground(plate);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        card.setClipToOutline(true);
        card.setElevation(dp(2));

        ImageView art = new ImageView(context);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadPoster(e.poster, art);

        View shade = new View(context);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x22000000, 0xDD000000});
        shade.setBackground(fade);
        card.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(9), 0, dp(9), dp(8));
        TextView title = label(e.title, 12, true);
        text.addView(title);
        if (!TextUtils.isEmpty(e.episode)) text.addView(label(e.episode, 10, false));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        card.addView(text, tlp);

        // How far it was watched: a thin bar along the bottom edge.
        if (e.duration > 0 && e.position > 0) {
            float done = Math.max(0.02f, Math.min(1f, e.position / (float) e.duration));
            View track = new View(context);
            track.setBackgroundColor(0x40FFFFFF);
            card.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM));
            View bar = new View(context);
            bar.setBackgroundColor(0xFFE5484D);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Math.round(w * done), dp(3), Gravity.BOTTOM | Gravity.START);
            card.addView(bar, blp);
        }


        // In focus: lifted, a white edge, as the dock's tiles are.
        View ring = new View(context);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(dp(10));
        edge.setStroke(dp(2), Color.WHITE);
        ring.setBackground(edge);
        ring.setVisibility(View.INVISIBLE);
        card.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOnFocusChangeListener((v, has) -> {
            ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? dp(10) : 0).setDuration(160).start();
            if (has) scroller.smoothScrollTo(Math.max(0, v.getLeft() - strip.getPaddingLeft()), 0);   // in line with the dock's first tile, clear of the fade
        });
        card.setOnClickListener(v -> open(e));
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN: host.onRowDown(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: host.onRowUp(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: return index == 0 && !rtl() || index == count - 1 && rtl();
                case KeyEvent.KEYCODE_DPAD_RIGHT: return index == count - 1 && !rtl() || index == 0 && rtl();
                default: return false;
            }
        });
        return card;
    }

    private boolean rtl() { return container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL; }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextColor(bold ? Color.WHITE : 0xCCFFFFFF);
        t.setTextSize(sp);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.create(t.getTypeface(), Typeface.BOLD));
        t.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        // Set to the card's right edge, title and episode alike.
        t.setGravity(Gravity.RIGHT);
        t.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private void open(Entry e) {
        try {
            Intent intent = Intent.parseUri(e.intent, Intent.URI_INTENT_SCHEME);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Log.w(TAG, "open: " + ex);
            if (e.pkg != null) {
                Intent launch = context.getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
                if (launch != null) context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }

    /** The poster, small, from its address: https needs the network, content:// and file:// do not. */
    private void loadPoster(String address, ImageView into) {
        if (TextUtils.isEmpty(address)) return;
        Bitmap cached = posters.get(address);
        if (cached != null) { into.setImageBitmap(cached); return; }
        io.execute(() -> {
            Bitmap b = null;
            try {
                Uri uri = Uri.parse(address);
                InputStream in;
                HttpURLConnection http = null;
                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                    http = (HttpURLConnection) new URL(address).openConnection();
                    http.setConnectTimeout(6000);
                    http.setReadTimeout(10000);
                    in = http.getInputStream();
                } else {
                    in = context.getContentResolver().openInputStream(uri);
                }
                try (InputStream s = in) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap full = BitmapFactory.decodeStream(s, null, o);
                    if (full != null) {
                        int tw = cardW > 0 ? cardW : dp(168);
                        b = full.getWidth() > tw ? Bitmap.createScaledBitmap(full, tw, Math.max(1, full.getHeight() * tw / full.getWidth()), true) : full;
                    }
                } finally {
                    if (http != null) http.disconnect();
                }
            } catch (Exception ex) {
                Log.w(TAG, "poster: " + ex);
            }
            Bitmap done = b;
            if (done != null) posters.put(address, done);
            main.post(() -> { if (done != null) into.setImageBitmap(done); });
        });
    }

    private int dp(float v) { return Math.round(v * density); }
}
