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
 * game a small score ticker in the corner (plus, if game_app is set, a switch to that app at tip-off), and the
 * morning briefing when the TV wakes into another app. They draw over other apps, which needs "display over other
 * apps"; the install script grants it.
 */
public class OverlayService extends Service {
    private static final String TAG = "tvhome";
    private static final long FEED_EVERY_MS = 5 * 60 * 1000L, RAIN_EVERY_MS = 10 * 60 * 1000L, SCHEDULE_EVERY_MS = 20 * 60 * 1000L,
            LIVE_EVERY_MS = 20 * 1000L, FINAL_SHOWN_MS = 3 * 60 * 1000L, BANNER_MS = 12 * 1000L, URGENT_MS = 20 * 1000L,
            BRIEFING_MS = 20 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private SharedPreferences prefs;
    private LinearLayout ticker;
    private TextView tickerScore, tickerStatus;
    private View banner;
    private String lastScore = "", tickerGame = "";
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

    /** Hidden on Home, and for the rest of a game you hid it for from the Warriors card (ticker_hidden_game). */
    private void updateTickerVisibility() {
        boolean hidden = homeShowing || tickerGame.equals(prefs.getString("ticker_hidden_game", ""));
        if (ticker != null) ticker.setVisibility(hidden ? View.GONE : View.VISIBLE);
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
        handler.post(feedPoll);
        if (getResources().getBoolean(R.bool.rain_alert)) handler.post(rainPoll);
        if (getResources().getBoolean(R.bool.game_ticker) || !getString(R.string.game_app).isEmpty()) handler.post(gamePoll);
        android.content.IntentFilter sf = new android.content.IntentFilter(Intent.ACTION_SCREEN_ON);
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, sf);
        if (getResources().getBoolean(R.bool.movie_lights)) {
            movieLights = new MovieLights(this, handler);
            movieLights.start();
        }
    }

    private MovieLights movieLights;

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "music".equals(i.getAction())) {
            String url = i.getStringExtra("url");
            if (url == null) stopMusic(); else playMusic(url, i.getStringExtra("name"));
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- focus music

    // Internet radio played from here, so it carries on while you move around the home screen and other apps.
    // When something else takes the sound (a video app starting playback), the radio stops rather than talk over it.
    private RadioPlayer radio;
    private android.media.AudioFocusRequest focusReq;
    static volatile String nowPlaying; // the station's name while playing, for the Work view

    /** Start (url and name) or stop (url null) the radio. */
    static void music(Context c, String url, String name) {
        try {
            c.startForegroundService(new Intent(c, OverlayService.class).setAction("music")
                    .putExtra("url", url).putExtra("name", name));
        } catch (RuntimeException e) {
            android.util.Log.e(TAG, "music: " + e.getMessage());
        }
    }

    private void playMusic(String url, String name) {
        stopMusic();
        android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
        android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focusReq = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) handler.post(this::stopMusic);
                    else if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && radio != null) radio.setVolume(0f);
                    else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN && radio != null) radio.setVolume(1f);
                }, handler).build();
        if (am.requestAudioFocus(focusReq) != android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        RadioPlayer[] self = new RadioPlayer[1];
        self[0] = new RadioPlayer(url, why -> handler.post(() -> { if (radio == self[0]) stopMusic(); }));
        radio = self[0];
        nowPlaying = name;
        radio.start();
    }

    private void stopMusic() {
        nowPlaying = null;
        if (radio != null) { radio.stop(); radio = null; }
        if (focusReq != null) {
            getSystemService(android.media.AudioManager.class).abandonAudioFocusRequest(focusReq);
            focusReq = null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(screenOn);
        if (movieLights != null) movieLights.stop();
        stopMusic();
        hideTicker();
        if (running == this) running = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- alerts from the Personal feed

    private final Runnable feedPoll = new Runnable() {
        @Override public void run() {
            checkFeed();
            handler.postDelayed(this, FEED_EVERY_MS);
        }
    };

    /**
     * Each thing shows once, the first time the feed has it while the screen is on: urgent mail, a package out for
     * delivery or delivered, and a subscription that charges in the next day and a half. The very first check
     * only learns what's already there, so installing doesn't replay a week of alerts.
     */
    private void checkFeed() {
        String url = getString(R.string.personal_feed_url).trim();
        if (url.isEmpty() || !getSystemService(android.os.PowerManager.class).isInteractive()) return;
        new Thread(() -> {
            try {
                JSONObject feed = new JSONObject(httpGet(url));
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                JSONArray known = new JSONArray(prefs.getString("alerts_seen", "[]"));
                for (int i = 0; i < known.length(); i++) seen.add(known.getString(i));
                boolean first = !prefs.getBoolean("alerts_seeded", false);
                long now = System.currentTimeMillis();
                java.util.List<Runnable> show = new java.util.ArrayList<>();

                JSONArray urgent = feed.optJSONArray("urgent");
                for (int i = 0; urgent != null && getResources().getBoolean(R.bool.urgent_banner) && i < urgent.length(); i++) {
                    JSONObject u = urgent.getJSONObject(i);
                    if (!seen.add("urgent:" + u.optString("id")) || first || now - u.optLong("at") > 12 * 3600_000L) continue;
                    String from = u.optString("from"), why = u.optString("reason");
                    show.add(() -> showBanner("⚠️", from, why, URGENT_MS, 0xFFFFB86B));
                }
                JSONArray pk = feed.optJSONArray("packages");
                for (int i = 0; pk != null && getResources().getBoolean(R.bool.delivery_banner) && i < pk.length(); i++) {
                    JSONObject p = pk.getJSONObject(i);
                    String st = p.optString("status");
                    if (!st.equals("delivered") && !st.equals("out")) continue;
                    if (!seen.add(st + ":" + p.optString("title").toLowerCase(Locale.ROOT)) || first) continue;
                    String line = p.optString("title") + (p.optString("detail").isEmpty() ? "" : "  ·  " + p.optString("detail"));
                    if (st.equals("out")) show.add(() -> showBanner("🚚", "Out for delivery", line, BANNER_MS));
                    else show.add(() -> showBanner("📦", "Delivered", line, BANNER_MS));
                }
                JSONArray att = feed.optJSONArray("attention");
                for (int i = 0; att != null && i < att.length(); i++) {
                    JSONObject a = att.getJSONObject(i);
                    if (!"renewal".equals(a.optString("status"))) continue;
                    long t = parseTime(a.optString("time"));
                    if (t < now || t - now > 36 * 3600_000L) continue;
                    if (!seen.add("renewal:" + a.optString("title").toLowerCase(Locale.ROOT) + a.optString("time")) || first) continue;
                    String line = a.optString("title") + "  ·  " + a.optString("detail") + "  ·  " + a.optString("when");
                    show.add(() -> showBanner("🔁", "Subscription charges soon", line, BANNER_MS));
                }

                JSONArray out = new JSONArray();
                for (String k : seen) out.put(k);
                while (out.length() > 150) out.remove(0);
                prefs.edit().putString("alerts_seen", out.toString()).putBoolean("alerts_seeded", true).apply();
                for (Runnable r : show) handler.post(r);
            } catch (Exception e) {
                android.util.Log.e(TAG, "feed check failed: " + e.getMessage());
            }
        }).start();
    }

    private static long parseTime(String iso) {
        try { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
        catch (RuntimeException e) { return 0; }
    }

    // ---------------------------------------------------------------- rain on the way

    // Open-Meteo's 15-minute forecast for home: if it's dry now and rain starts within 45 minutes, say when.
    // At most one alert every 3 hours, so a showery afternoon doesn't keep interrupting.
    private final Runnable rainPoll = new Runnable() {
        @Override public void run() {
            checkRain();
            handler.postDelayed(this, RAIN_EVERY_MS);
        }
    };

    private void checkRain() {
        if (!getSystemService(android.os.PowerManager.class).isInteractive()
                || System.currentTimeMillis() - prefs.getLong("rain_alert_at", 0) < 3 * 3600_000L) return;
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + getString(R.string.home_lat)
                + "&longitude=" + getString(R.string.home_lon)
                + "&current=precipitation&minutely_15=precipitation&forecast_minutely_15=4&timezone=auto";
        new Thread(() -> {
            try {
                JSONObject w = new JSONObject(httpGet(url));
                if (w.getJSONObject("current").optDouble("precipitation", 0) > 0) return; // already raining
                JSONObject m = w.getJSONObject("minutely_15");
                JSONArray times = m.getJSONArray("time"), mm = m.getJSONArray("precipitation");
                for (int i = 1; i < mm.length(); i++) {
                    double r = mm.optDouble(i, 0);
                    if (r < 0.2) continue;
                    java.time.LocalDateTime at = java.time.LocalDateTime.parse(times.getString(i));
                    String when = at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()));
                    String kind = r >= 2.5 ? "Heavy rain" : r >= 0.8 ? "Rain" : "Light rain";
                    prefs.edit().putLong("rain_alert_at", System.currentTimeMillis()).apply();
                    handler.post(() -> showBanner("🌧", kind + " on the way", "Starting around " + when, BANNER_MS, 0xFF8FD4FF));
                    return;
                }
            } catch (Exception e) {
                android.util.Log.e(TAG, "rain check failed: " + e.getMessage());
            }
        }).start();
    }

    // ---------------------------------------------------------------- banners

    // One at a time: a banner that arrives while another is up waits its turn.
    private final java.util.ArrayDeque<Runnable> waiting = new java.util.ArrayDeque<>();

    private void showBanner(String glyph, String head, String body, long shownMs) {
        showBanner(glyph, head, body, shownMs, 0xFF7DDC8A);
    }

    private void showBanner(String glyph, String head, String body, long shownMs, int headColor) {
        if (!canDraw()) return;
        if (banner != null) { waiting.add(() -> showBanner(glyph, head, body, shownMs, headColor)); return; }
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
        TextView h = text(12, "sans-serif-medium", headColor);
        h.setText(head);
        col.addView(h);
        String[] lines = body.split("\n");
        for (int i = 0; i < Math.min(lines.length, 7); i++) { // one line each, cut short rather than wrapped
            TextView t = text(11, "sans-serif", 0xE6FFFFFF);
            t.setText(lines[i]);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setMaxWidth(dp(560));
            col.addView(t, new LinearLayout.LayoutParams(-2, -2));
            if (i > 0) t.setPadding(0, dp(3), 0, 0);
        }
        b.addView(col);
        banner = b;
        wm.addView(b, overlayParams(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(28)));
        b.setAlpha(0f);
        b.setTranslationY(-dp(24));
        b.animate().alpha(1f).translationY(0).setDuration(450).withEndAction(() ->
                b.animate().alpha(0f).translationY(-dp(24)).setStartDelay(shownMs).setDuration(400).withEndAction(() -> {
                    try { wm.removeView(b); } catch (RuntimeException ignored) { }
                    if (banner == b) banner = null;
                    Runnable next = waiting.poll();
                    if (next != null) handler.postDelayed(next, 600);
                }).start()).start();
    }

    // ---------------------------------------------------------------- morning briefing on wake

    // When the TV wakes into another app before noon, the day's briefing comes as a banner instead. If it wakes
    // on Home, the home screen shows its own full briefing; briefing_day makes sure you only get one a day.
    private final android.content.BroadcastReceiver screenOn = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) { // TV off: radio off, lights back to normal
                stopMusic();
                if (movieLights != null) movieLights.screenOff();
                return;
            }
            handler.removeCallbacks(wakeBriefing);
            handler.postDelayed(wakeBriefing, 4000); // give Home a moment to come back first
            // Alerts only show while the screen is on, so catch up on anything that came in while it was off.
            handler.removeCallbacks(feedPoll);
            handler.postDelayed(feedPoll, 30_000);
            if (getResources().getBoolean(R.bool.rain_alert)) { handler.removeCallbacks(rainPoll); handler.postDelayed(rainPoll, 30_000); }
        }
    };
    private final Runnable wakeBriefing = this::maybeWakeBriefing;

    private void maybeWakeBriefing() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int h = now.get(java.util.Calendar.HOUR_OF_DAY);
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        if (homeShowing || h < 4 || h >= 12 || today.equals(prefs.getString("briefing_day", "")) || !canDraw()) return;
        java.util.List<String> lines = new java.util.ArrayList<>();
        String wx = weatherLine();
        if (!wx.isEmpty()) lines.add("🌤  " + wx);
        try {
            JSONObject r = new JSONObject(prefs.getString("recap_json", "{}"));
            String recap = r.optString("text");
            if (!recap.isEmpty() && System.currentTimeMillis() - r.optLong("at") < 24 * 3600 * 1000L) lines.add("🏀  " + recap);
        } catch (org.json.JSONException ignored) { }
        for (String[] l : PersonalPage.briefingLines(prefs.getString("personal_json", null))) {
            lines.add(l[0] + "  " + l[1].replace('\n', ' '));
        }
        if (lines.isEmpty()) return;
        prefs.edit().putString("briefing_day", today).apply();
        showBanner("☀️", "Good morning", String.join("\n", lines), BRIEFING_MS);
    }

    /** "24° now, high 29° low 22°, rain likely later (60%)" from the home screen's last forecast. */
    private String weatherLine() {
        try {
            JSONObject w = new JSONObject(prefs.getString("wx_home", "{}"));
            if (!w.has("current")) return "";
            JSONObject d = w.getJSONObject("daily");
            StringBuilder s = new StringBuilder().append(Math.round(w.getJSONObject("current").getDouble("temperature_2m")))
                    .append("° now, high ").append(Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)))
                    .append("° low ").append(Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))).append('°');
            JSONArray rain = w.getJSONObject("hourly").getJSONArray("precipitation_probability");
            int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), max = 0;
            for (int i = hour; i < Math.min(24, rain.length()); i++) max = Math.max(max, rain.optInt(i));
            s.append(max >= 40 ? ", rain likely later (" + max + "%)" : ", dry today");
            return s.toString();
        } catch (Exception e) {
            return "";
        }
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
        tickerGame = e.getString("id");
        if (ticker == null) buildTicker();
        updateTickerVisibility();
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
