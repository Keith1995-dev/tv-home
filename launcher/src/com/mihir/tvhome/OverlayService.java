package com.mihir.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Runs while you watch other apps: a banner when the Personal feed sees a package delivered, and during a live
 * game a small score ticker in the corner (plus, if game_app is set, a switch to that app at tip-off). Both draw
 * over other apps, which needs "display over other apps"; the install script grants it.
 */
public class OverlayService extends Service {
    private static final String TAG = "tvhome";
    private static final long PACKAGES_EVERY_MS = 10 * 60 * 1000L, SCHEDULE_EVERY_MS = 20 * 60 * 1000L,
            LIVE_EVERY_MS = 20 * 1000L, FINAL_SHOWN_MS = 3 * 60 * 1000L, BANNER_MS = 12 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;
    private LinearLayout ticker;
    private TextView tickerScore, tickerStatus;
    private View banner;
    private String lastScore = "";
    private long nextTipoff;
    private boolean live, triedGrant;
    private static OverlayService running;
    private static boolean homeShowing;

    /** The home screen's own Warriors card shows the live score, so the ticker steps aside while Home is up. */
    static void setHomeShowing(boolean showing) {
        homeShowing = showing;
        OverlayService s = running;
        if (s != null) s.handler.post(s::updateTickerVisibility);
    }

    private void updateTickerVisibility() {
        if (ticker != null) ticker.setVisibility(homeShowing ? View.GONE : View.VISIBLE);
    }

    /** Called from the home screen whenever it shows; starting it again is harmless. */
    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, OverlayService.class));
        } catch (RuntimeException e) {
            android.util.Log.e(TAG, "overlay service: " + e.getMessage());
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("tvhome", MODE_PRIVATE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("overlay", "Alerts over other apps", NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "overlay").setSmallIcon(R.drawable.ic_home)
                .setContentTitle("TV Home alerts").setOngoing(true).build());
        if (getResources().getBoolean(R.bool.delivery_banner)) handler.post(packagePoll);
        if (getResources().getBoolean(R.bool.game_ticker) || !getString(R.string.game_app).isEmpty()) handler.post(gamePoll);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideTicker();
        if (running == this) running = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- deliveries

    private final Runnable packagePoll = new Runnable() {
        @Override public void run() {
            checkPackages();
            handler.postDelayed(this, PACKAGES_EVERY_MS);
        }
    };

    private void checkPackages() {
        String url = getString(R.string.personal_feed_url).trim();
        if (url.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONArray pk = new JSONObject(httpGet(url)).optJSONArray("packages");
                if (pk == null) return;
                JSONArray known = new JSONArray(prefs.getString("delivered_seen", "[]"));
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                for (int i = 0; i < known.length(); i++) seen.add(known.getString(i));
                boolean first = !prefs.getBoolean("delivered_seeded", false);
                for (int i = 0; i < pk.length(); i++) {
                    JSONObject p = pk.getJSONObject(i);
                    if (!"delivered".equals(p.optString("status"))) continue;
                    String key = p.optString("title").toLowerCase(Locale.ROOT);
                    if (!seen.add(key) || first) continue; // the first check only learns what's already there
                    String title = p.optString("title"), detail = p.optString("detail");
                    handler.post(() -> showBanner("📦", "Delivered", title + (detail.isEmpty() ? "" : "  ·  " + detail)));
                }
                JSONArray out = new JSONArray();
                for (String k : seen) out.put(k);
                while (out.length() > 60) out.remove(0);
                prefs.edit().putString("delivered_seen", out.toString()).putBoolean("delivered_seeded", true).apply();
            } catch (Exception e) {
                android.util.Log.e(TAG, "delivery check failed: " + e.getMessage());
            }
        }).start();
    }

    private void showBanner(String glyph, String head, String body) {
        if (!canDraw()) return;
        if (banner != null) { try { wm.removeView(banner); } catch (RuntimeException ignored) { } }
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(16), dp(10), dp(20), dp(10));
        b.setBackground(pillBg(0xF2181B22, dp(22)));
        b.setElevation(dp(8));
        TextView g = text(16, "sans-serif", Color.WHITE);
        g.setText(glyph);
        b.addView(g, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView h = text(12, "sans-serif-medium", 0xFF7DDC8A);
        h.setText(head);
        TextView t = text(11, "sans-serif", 0xE6FFFFFF);
        t.setText(body);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(520));
        col.addView(h);
        col.addView(t);
        b.addView(col);
        banner = b;
        wm.addView(b, overlayParams(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(28)));
        b.setAlpha(0f);
        b.setTranslationY(-dp(24));
        b.animate().alpha(1f).translationY(0).setDuration(450).withEndAction(() ->
                b.animate().alpha(0f).translationY(-dp(24)).setStartDelay(BANNER_MS).setDuration(400).withEndAction(() -> {
                    try { wm.removeView(b); } catch (RuntimeException ignored) { }
                    if (banner == b) banner = null;
                }).start()).start();
    }

    // ---------------------------------------------------------------- game day

    private final Runnable gamePoll = new Runnable() {
        @Override public void run() {
            checkGame();
            long untilTip = nextTipoff - System.currentTimeMillis() + 30_000;
            long delay = live ? LIVE_EVERY_MS : untilTip > 0 ? Math.min(SCHEDULE_EVERY_MS, untilTip) : SCHEDULE_EVERY_MS;
            handler.postDelayed(this, Math.max(LIVE_EVERY_MS, delay));
        }
    };

    /** Schedule first (is there a game on, and when's the next), then the live scoreboard for the score and clock. */
    private void checkGame() {
        String league = getString(R.string.team_league), team = getString(R.string.team_id);
        new Thread(() -> {
            try {
                JSONObject sched = new JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/" + league
                        + "/teams/" + team + "/schedule"));
                String us = sched.getJSONObject("team").getString("id");
                JSONArray events = sched.getJSONArray("events");
                JSONObject game = null;
                long next = 0, now = System.currentTimeMillis();
                for (int i = 0; i < events.length(); i++) {
                    JSONObject e = events.getJSONObject(i);
                    String state = e.getJSONArray("competitions").getJSONObject(0).getJSONObject("status")
                            .getJSONObject("type").getString("state");
                    long tip = tipoff(e.getString("date"));
                    // The schedule can lag at tip-off, so a game that started in the last 4 hours counts too.
                    if (state.equals("in") || state.equals("pre") && tip <= now && now - tip < 4 * 3600_000L) { game = e; break; }
                    if (state.equals("pre") && next == 0 && tip > now) next = tip;
                }
                nextTipoff = next;
                JSONObject scored = game == null ? null : scoreboardEntry(league, game);
                JSONObject g = scored != null ? scored : game;
                handler.post(() -> showGame(g, us));
            } catch (Exception e) {
                android.util.Log.e(TAG, "game check failed: " + e.getMessage());
            }
        }).start();
    }

    /** The same game from the scoreboard, which has the live score and clock sooner than the schedule. */
    private JSONObject scoreboardEntry(String league, JSONObject game) throws Exception {
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US);
        day.setTimeZone(java.util.TimeZone.getTimeZone("America/New_York"));
        String id = game.getString("id");
        JSONArray events = new JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/" + league
                + "/scoreboard?dates=" + day.format(new java.util.Date(tipoff(game.getString("date")))))).getJSONArray("events");
        for (int i = 0; i < events.length(); i++) if (events.getJSONObject(i).getString("id").equals(id)) return events.getJSONObject(i);
        return null;
    }

    private void showGame(JSONObject e, String us) {
        try {
            String state = e == null ? "none" : e.getJSONArray("competitions").getJSONObject(0)
                    .getJSONObject("status").getJSONObject("type").getString("state");
            if (!state.equals("in")) {
                if (live && state.equals("post")) { // just ended: show the final for a few minutes
                    setTicker(e, us);
                    handler.postDelayed(this::hideTicker, FINAL_SHOWN_MS);
                } else if (!state.equals("post")) {
                    hideTicker();
                }
                live = false;
                return;
            }
            live = true;
            String id = e.getString("id");
            if (!id.equals(prefs.getString("switched_game", ""))) {
                prefs.edit().putString("switched_game", id).apply();
                String app = getString(R.string.game_app).trim();
                if (!app.isEmpty()) openApp(app);
            }
            if (getResources().getBoolean(R.bool.game_ticker)) setTicker(e, us);
        } catch (Exception ex) {
            android.util.Log.e(TAG, "game parse failed: " + ex.getMessage());
        }
    }

    private void setTicker(JSONObject e, String us) throws org.json.JSONException {
        if (!canDraw()) return;
        JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
        JSONArray cs = c.getJSONArray("competitors");
        JSONObject me = null, them = null;
        for (int i = 0; i < cs.length(); i++) {
            JSONObject t = cs.getJSONObject(i);
            if (t.getJSONObject("team").getString("id").equals(us)) me = t; else them = t;
        }
        if (me == null || them == null) return;
        String ms = score(me), ts = score(them);
        String line = me.getJSONObject("team").getString("abbreviation") + "  " + ms + " – " + ts + "  "
                + them.getJSONObject("team").getString("abbreviation");
        JSONObject st = c.getJSONObject("status");
        String state = st.getJSONObject("type").getString("state");
        String detail = state.equals("post") ? "FINAL" : st.getJSONObject("type").optString("shortDetail");
        if (ticker == null) buildTicker();
        tickerStatus.setText(detail.toUpperCase(Locale.ROOT));
        if (!line.equals(lastScore)) {
            boolean weScored = !lastScore.isEmpty() && !ms.equals(scoreOf(lastScore, true));
            lastScore = line;
            tickerScore.setText(line);
            if (weScored) { // a quick gold flash when your team scores
                tickerScore.setTextColor(getColor(R.color.team_secondary));
                tickerScore.postDelayed(() -> tickerScore.setTextColor(Color.WHITE), 2500);
            }
        }
    }

    private static String scoreOf(String line, boolean ours) {
        String[] p = line.split("  ")[1].split(" – ");
        return ours ? p[0] : p[1];
    }

    private void buildTicker() {
        ticker = new LinearLayout(this);
        ticker.setGravity(Gravity.CENTER_VERTICAL);
        ticker.setPadding(dp(6), dp(6), dp(14), dp(6));
        ticker.setBackground(pillBg(0xE6101318, dp(18)));
        TextView badge = text(9, "sans-serif-black", getColor(R.color.team_primary));
        badge.setText(getString(R.string.team_badge));
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(pillBg(getColor(R.color.team_secondary), dp(12)));
        ticker.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(24)));
        tickerScore = text(12, "sans-serif-medium", Color.WHITE);
        tickerScore.setPadding(dp(10), 0, dp(10), 0);
        ticker.addView(tickerScore);
        tickerStatus = text(9, "sans-serif-medium", 0xB3FFFFFF);
        tickerStatus.setLetterSpacing(0.08f);
        ticker.addView(tickerStatus);
        wm.addView(ticker, overlayParams(Gravity.TOP | Gravity.END, dp(28), dp(24)));
        updateTickerVisibility();
        ticker.setAlpha(0f);
        ticker.animate().alpha(1f).setDuration(500).start();
    }

    private void hideTicker() {
        if (ticker == null) return;
        View t = ticker;
        ticker = null;
        lastScore = "";
        try { wm.removeView(t); } catch (RuntimeException ignored) { }
    }

    private void openApp(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { android.util.Log.e(TAG, "game app: " + e.getMessage()); }
    }

    private static String score(JSONObject competitor) {
        JSONObject s = competitor.optJSONObject("score");
        return s != null ? s.optString("displayValue") : competitor.optString("score");
    }

    private static long tipoff(String iso) { // ESPN leaves out the seconds: 2027-04-09T01:30Z
        return java.time.Instant.parse(iso.length() == 17 ? iso.replace("Z", ":00Z") : iso).toEpochMilli();
    }

    // ---------------------------------------------------------------- helpers

    /** Drawing over other apps needs the appop; if the install didn't grant it, ask the TV's own ADB once. */
    private boolean canDraw() {
        if (Settings.canDrawOverlays(this)) return true;
        if (!triedGrant) {
            triedGrant = true;
            new Thread(() -> {
                try { new LocalAdb(getFilesDir()).shell("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow"); }
                catch (Exception e) { android.util.Log.e(TAG, "overlay grant failed: " + e.getMessage()); }
            }).start();
        }
        return false;
    }

    private WindowManager.LayoutParams overlayParams(int gravity, int x, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.x = x;
        p.y = y;
        return p;
    }

    private String httpGet(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = c.getInputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toString("UTF-8");
    }

    private static GradientDrawable pillBg(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
