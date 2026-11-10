package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock top-left, pills top-right, the focused dock app's name over an
 * ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on Down.
 * Plain framework views only (no AndroidX), no network, no background work.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private View gridShade;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private TextView greeting, clock, ampm, date, title, wifiPill;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    // Damped-spring ease: overshoots slightly then settles, no jitter.
    private static final android.animation.TimeInterpolator SPRING = t ->
            (float) (1 - Math.exp(-7 * t) * Math.cos(9 * t));
    // Material "emphasized decelerate": fast start, long soft landing.
    private static final android.animation.TimeInterpolator SOFT =
            new android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow inputsPopup;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = firstInstalled(R.array.live_tv_packages);
        inputPickerPkg = firstInstalled(R.array.input_picker_packages);
        loadAerialList(!prefs().getBoolean("hdr_failed", false) && dolbyVisionCapable());
        loadStillList();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        buildApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
        registerReceiver(installResult, new IntentFilter(ACTION_INSTALL));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs().getBoolean("google_tv_on", false)) hideGoogleTv();
        leaveAmbient(false);
        startWeather();
        startTeam();
        startFacts();
        startWatchFeeds();
        OverlayService.start(this);
        OverlayService.setHomeShowing(true);
        personalPage.start();
        handler.postDelayed(maybeBriefing, 1500);
        loadRecs(false);
        claudeCard.removeCallbacks(feedPoll);
        claudeCard.post(feedPoll);
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last == null && !dockCards.isEmpty()) last = dockCards.get(0);
        if (last != null) last.requestFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) buildApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        startAerials();
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        if (claudeCard != null) claudeCard.removeCallbacks(feedPoll);
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(weatherPoll);
        handler.removeCallbacks(teamPoll);
        handler.removeCallbacks(goAmbient);
        handler.removeCallbacks(nextFact);
        handler.removeCallbacks(stremioPoll);
        handler.removeCallbacks(maybeBriefing);
        handler.removeCallbacks(sleepNow);
        personalPage.stop();
        OverlayService.setHomeShowing(false);
        stopAerials();
        unregisterReceiver(tick);
        if (inputsPopup != null) inputsPopup.dismiss();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        unregisterReceiver(installResult);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (inputsPopup != null) inputsPopup.dismiss();
        closeParty();
        closeAppsDrawer(false);
        showTab(0);
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from Personal or Work returns to Entertainment,
        // Back from the grid returns to the dock.
        if (partyOpen) { closeParty(); return; }
        if (drawerOpen) { closeAppsDrawer(true); return; }
        if (tab != 0) { tabViews.get(0).requestFocus(); return; }
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        rootView = root;

        // Aerial video sits underneath everything on the hardware video plane (SurfaceView), so it costs
        // the GPU almost nothing; the photos above it cover the gap while a clip loads.
        if (aerialsOn()) {
            aerialView = new android.view.SurfaceView(this);
            aerialView.getHolder().addCallback(aerialSurface);
            root.addView(aerialView, new FrameLayout.LayoutParams(-1, -1));
        }
        LiquidGlass.backdrop = new LiquidGlass.Backdrop(aerialView);
        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        photos = new FrameLayout(this);
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        photos.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        photos.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        root.addView(photos, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildStillsLayer(), new FrameLayout.LayoutParams(-1, -1));
        veil = new View(this);
        veil.setBackgroundColor(Color.BLACK);
        veil.setAlpha(0f);
        root.addView(veil, new FrameLayout.LayoutParams(-1, -1));
        // Everything drawn over the background lives in one layer so idle mode can fade it away in one go.
        ui = new FrameLayout(this);
        ui.setClipChildren(false);
        root.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        // Scrim: darker at the top (clock, pills) and bottom (dock) so text stays readable.
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000}));
        ui.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        ui.addView(buildGameTint(), new FrameLayout.LayoutParams(-1, -1));
        gridShade = new View(this);
        gridShade.setBackgroundColor(0xB3000000);
        gridShade.setAlpha(0f);
        ui.addView(gridShade, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = new View(this);
        ui.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);
        // Over moving video the colour glow just tints it, and it's a full-screen blend every frame.
        if (aerialView != null) glow.setVisibility(View.GONE);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        ui.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(58), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        // Compact, centred dock: fixed-size tiles on a glass shelf that hugs them.
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        title = text(22, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, llp(-2, -2, 0, 0, 0, dp(10)));
        bottom.addView(buildPeek(), llp(-2, -2, 0, 0, 0, dp(12)));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        liquid(dockRow, dp(22));
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-2, -2));

        // Discover is its own page below the dock: Down from the dock slides it up.
        recsBox = new LinearLayout(this);
        recsBox.setOrientation(LinearLayout.VERTICAL);
        recsBox.setClipChildren(false);
        recsBox.setMinimumHeight(screenH);
        content.addView(recsBox, llp(-1, -2, dp(62), 0, dp(62), dp(120)));

        personalPage = new PersonalPage(this, getString(R.string.personal_feed_url));
        personalPage.setVisibility(View.INVISIBLE);
        ui.addView(personalPage, lp(-1, -1, Gravity.TOP, dp(48), dp(66), dp(48), dp(36)));
        workPage = buildWorkPage();
        workPage.setVisibility(View.INVISIBLE);
        ui.addView(workPage, lp(-1, -1, Gravity.TOP, dp(48), dp(66), dp(48), dp(36)));
        tabBar = buildTabs();
        ui.addView(tabBar, lp(-2, dp(32), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(16), 0, 0));
        // The tabs belong to the top of the home screen: they fade away as Discover scrolls up.
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateTabBar());
        ui.addView(buildAppsDrawer(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildClaudeCard(),lp(dp(560), -2, Gravity.BOTTOM | Gravity.END, 0, 0, dp(48), dp(48)));
        aerialLabel = text(10, "sans-serif-medium", 0xB3FFFFFF);
        aerialLabel.setLetterSpacing(0.3f);
        aerialLabel.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        aerialLabel.setAlpha(0f);
        root.addView(aerialLabel, lp(-2, -2, Gravity.TOP | Gravity.END, 0, dp(84), dp(52), 0));
        root.addView(buildStillCaption(), lp(dp(420), -2, Gravity.BOTTOM | Gravity.START, dp(48), 0, 0, dp(10)));
        root.addView(buildNightDim(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildNightClock(), lp(-1, -2, Gravity.CENTER, 0, 0, 0, 0));
        root.addView(buildBanner(), lp(-2, dp(34), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(58), 0, 0));
        root.addView(buildBriefing(), new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        greeting = text(10, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(10, "sans-serif-medium", 0x99FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.22f);
        left.addView(date);

        LinearLayout world = new LinearLayout(this);
        for (String[] city : WORLD_CLOCKS) world.addView(worldClock(city[0], city[1]), llp(-2, -2, 0, 0, dp(10), 0));
        left.addView(world, llp(-2, -2, 0, dp(14), 0, 0));
        left.addView(buildFactLine(), llp(dp(320), -2, dp(2), dp(12), 0, 0));

        recsStatus = text(11, "sans-serif-medium", 0xFF8FD4FF);
        recsStatus.setMaxLines(4);
        recsStatus.setVisibility(View.GONE);
        recsStatus.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        left.addView(recsStatus, llp(-2, -2, 0, dp(8), 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(560), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = iconPill(R.drawable.ic_input, "Inputs");
        inputs.setOnClickListener(v -> showInputs(v));
        wifiPill = iconPill(R.drawable.ic_wifi, "Wi-Fi");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        TextView settings = iconPill(R.drawable.ic_settings, "Settings");
        settings.setOnClickListener(v -> launch(new Intent(Settings.ACTION_SETTINGS)));
        TextView update = pill("Update", "\u21BB");
        update.setOnClickListener(v -> selfUpdate());
        TextView picks = pill("Discover", "\u2726");
        picks.setOnClickListener(v -> {
            if (!hasApiKey()) { toast("Add your Claude API key in secrets.xml first"); return; }
            recsJump = true;
            loadRecs(true);
        });
        List<TextView> row = new ArrayList<>(Arrays.asList(inputs, picks, wifiPill, settings, update));
        if (isInstalled(GOOGLE_TV)) {
            TextView gtv = pill("Google TV", "▣");
            gtv.setOnClickListener(v -> openGoogleTv());
            row.add(gtv);
        }
        for (TextView p : row) {
            boolean icon = p.getText().length() == 0;
            right.addView(p, llp(icon ? dp(PILL_H) : -2, dp(PILL_H), dp(7), 0, 0, 0));
            pills.add(p);
        }
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setGravity(Gravity.END);
        rightCol.setClipChildren(false);
        rightCol.addView(right, llp(-2, -2, 0, 0, 0, 0));
        rightCol.addView(buildWeatherCard(), llp(dp(280), -2, 0, dp(12), 0, 0));
        rightCol.addView(buildTeamCard(), llp(dp(280), -2, 0, dp(8), 0, 0));
        header.addView(rightCol, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    // Frosted glass without live blur (which would cost GPU time every frame over moving video):
    // a light top-to-bottom sheen, a hairline edge, and the darkened scrim behind does the rest.
    private static final int[] GLASS = {0x40FFFFFF, 0x1FFFFFFF};
    private static final int[] GLASS_DARK = {0xD92A2C33, 0xE6141519};
    private static final int[] GLASS_LIT = {0xFAFFFFFF, 0xF0E8E8EE};

    private GradientDrawable glass(float radius, int[] colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        g.setCornerRadius(radius);
        g.setStroke(dp(1), 0x38FFFFFF);
        return g;
    }

    /** Liquid glass (blurred live video behind it) as the view's background; see LiquidGlass. */
    private LiquidGlass liquid(View v, float radius) {
        LiquidGlass g = new LiquidGlass(v, radius);
        v.setBackground(g);
        return g;
    }

    private static final int PILL_H = 26; // dp

    private TextView pill(String label, String glyph) {
        TextView p = text(10, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(12), 0, dp(12), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        LiquidGlass bg = liquid(p, dp(PILL_H / 2f));
        p.setOnFocusChangeListener((v, has) -> {
            bg.setLit(has);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            for (android.graphics.drawable.Drawable d : ((TextView) v).getCompoundDrawables()) {
                if (d != null) d.setTint(has ? 0xFF111111 : 0xE6FFFFFF);
            }
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(has ? SPRING : SOFT).setDuration(has ? 360 : 220).withLayer().start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    /** A round glass button with just an icon; the name is kept for accessibility. */
    private TextView iconPill(int icon, String name) {
        TextView p = pill("", "");
        p.setText("");
        p.setContentDescription(name);
        android.graphics.drawable.Drawable d = getDrawable(icon).mutate();
        int s = dp(14);
        d.setBounds(0, 0, s, s);
        d.setTint(0xE6FFFFFF);
        p.setCompoundDrawables(d, null, null, null);
        p.setPadding((dp(PILL_H) - s) / 2, 0, 0, 0);
        return p;
    }

    // ---------------------------------------------------------------- views: Entertainment, Personal, Work

    private static final String[] TABS = {"Entertainment", "Personal", "Work"};
    private View tabBar;

    private void updateTabBar() {
        float a = drawerOpen ? 0f : 1f - Math.min(1f, scroll.getScrollY() / (float) dp(60));
        tabBar.setAlpha(a);
        tabBar.setVisibility(a <= 0.01f ? View.INVISIBLE : View.VISIBLE);
    }
    private final List<TextView> tabViews = new ArrayList<>();
    private int tab;
    private PersonalPage personalPage;
    private View workPage;

    /** Glass capsule at the top centre; moving focus across it switches views, like Apple TV. */
    private View buildTabs() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));
        liquid(bar, dp(16));
        for (int i = 0; i < TABS.length; i++) {
            int idx = i;
            TextView t = text(11, "sans-serif-medium", 0x99FFFFFF);
            t.setText(TABS[i]);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setOnFocusChangeListener((v, has) -> {
                if (has) { lastFocusedId = v.getId(); showTab(idx); }
                styleTabs();
            });
            t.setOnClickListener(v -> showTab(idx));
            tabViews.add(t);
            bar.addView(t, new LinearLayout.LayoutParams(-2, -1));
        }
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            t.setNextFocusUpId(t.getId());
            t.setNextFocusLeftId(tabViews.get(Math.max(0, i - 1)).getId());
            t.setNextFocusRightId(tabViews.get(Math.min(tabViews.size() - 1, i + 1)).getId());
        }
        styleTabs();
        return bar;
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView t = tabViews.get(i);
            boolean focused = t.hasFocus(), selected = i == tab;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(13));
            g.setColor(focused ? 0xF5F4F4F8 : selected ? 0x33FFFFFF : Color.TRANSPARENT);
            t.setBackground(g);
            t.setTextColor(focused ? 0xFF111111 : selected ? Color.WHITE : 0x99FFFFFF);
            // Down goes to the top bar on Entertainment; the other views have nothing to select yet.
            t.setNextFocusDownId(tab == 0 && !pills.isEmpty() ? pills.get(0).getId() : t.getId());
        }
    }

    private void showTab(int i) {
        if (i == tab) return;
        View[] pages = {scroll, personalPage, workPage};
        View from = pages[tab], to = pages[i];
        tab = i;
        styleTabs();
        from.animate().cancel();
        from.animate().alpha(0f).setDuration(180).withLayer().withEndAction(() -> {
            if (pages[tab] != from) from.setVisibility(View.INVISIBLE);
        }).start();
        to.animate().cancel();
        to.setVisibility(View.VISIBLE);
        to.setAlpha(0f);
        to.animate().alpha(1f).setDuration(260).setInterpolator(SOFT).withLayer().start();
        if (to == personalPage) personalPage.shown();
        if (to == scroll) scroll.scrollTo(0, 0);
    }

    private View buildWorkPage() {
        FrameLayout page = new FrameLayout(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(32), dp(24), dp(32), dp(24));
        liquid(card, dp(22));
        TextView h = text(20, "sans-serif-light", Color.WHITE);
        h.setText("Work");
        card.addView(h);
        TextView s = text(11, "sans-serif", 0x99FFFFFF);
        s.setText("Slack and your work Gmail will show up here in phase 2.");
        card.addView(s, llp(-2, -2, 0, dp(6), 0, 0));
        page.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return page;
    }

    // ---------------------------------------------------------------- apps

    private static final int DOCK_TILE_W = 112; // dp; 16:9 banners, about 75% of the old full-width tiles

    private void buildApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = new HashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        List<App> dock = new ArrayList<>();
        for (String p : dockOrder()) if (byPkg.containsKey(p)) dock.add(byPkg.get(p));
        // The drawer lists every app (dock ones too), A to Z; recently opened ones also get their own row.
        List<App> all = new ArrayList<>(byPkg.values());
        Collections.sort(all, (x, y) -> x.label.compareToIgnoreCase(y.label));
        List<App> recent = new ArrayList<>();
        for (String p : recentApps()) if (byPkg.containsKey(p) && recent.size() < DRAWER_COLS) recent.add(byPkg.get(p));

        dockRow.removeAllViews();
        grid.removeAllViews();
        recentRow.removeAllViews();
        dockCards.clear();
        gridCards.clear();
        recentCards.clear();

        // Tiles shrink a little when you pin more apps, so the shelf always fits the screen.
        float screenDp = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density;
        int tileW = Math.min(DOCK_TILE_W, (int) ((screenDp - 140) / (dock.size() + 1)) - 18);
        for (int i = 0; i <= dock.size(); i++) {
            View tile = i < dock.size() ? tile(dock.get(i), TILE_DOCK) : allAppsTile();
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(tileW), -2);
            p.setMargins(i == 0 ? 0 : dp(9), 0, i == dock.size() ? 0 : dp(9), 0);
            dockRow.addView(tile, p);
        }

        for (int i = 0; i < recent.size(); i++) recentRow.addView(tile(recent.get(i), TILE_RECENT), drawerCell(i));
        for (int i = recent.size(); i > 0 && i < DRAWER_COLS; i++) recentRow.addView(new View(this), drawerCell(i));
        recentLabel.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        recentRow.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);

        LinearLayout row = null;
        for (int i = 0; i < all.size(); i++) {
            if (i % DRAWER_COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(18)));
            }
            row.addView(tile(all.get(i), TILE_GRID), drawerCell(i));
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = all.size() % DRAWER_COLS; i != 0 && i < DRAWER_COLS; i++) row.addView(new View(this), drawerCell(i));
        wireFocus();

        if (!dock.isEmpty()) { title.setText(dock.get(0).label); setGlow(dock.get(0).color); }
        if (drawerOpen) { // rebuilt while browsing (pinned an app, or one was installed): stay in the drawer
            List<View> first = recentCards.isEmpty() ? gridCards : recentCards;
            if (!first.isEmpty()) first.get(0).requestFocus();
            return;
        }
        View last = lastFocusedId == View.NO_ID ? null : findViewById(lastFocusedId);
        if (last != null) last.requestFocus();
        else if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    private static final int TILE_DOCK = 0, TILE_GRID = 1, TILE_RECENT = 2;

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, int where) {
        boolean inDock = where == TILE_DOCK;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> {
            noteRecent(app.pkg);
            if (!inDock) closeAppsDrawer(false); // coming back lands on the home screen, not the drawer
            launch(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        });
        card.setOnLongClickListener(v -> { showAppMenu(v, app); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            v.animate().scaleX(s).scaleY(s)
                    .setInterpolator(has ? SPRING : SOFT)
                    .setDuration(has ? 420 : 260).withLayer().start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has || !inDock) return;
            lastFocusedId = v.getId();
            onDockFocus(app.pkg, v);
            title.setText(app.label);
            title.animate().alpha(1f).setDuration(200).start();
            animateGlow(app.color);
            fadeGlow(1f);
            scroll.smoothScrollTo(0, 0);
        });

        (inDock ? dockCards : where == TILE_RECENT ? recentCards : gridCards).add(card);
        return box;
    }

    // ---------------------------------------------------------------- dock order, recents, All apps drawer

    private static final int DRAWER_COLS = 6, DOCK_MAX = 7;
    private final List<View> recentCards = new ArrayList<>();
    private FrameLayout appsDrawer;
    private android.widget.ScrollView drawerScroll;
    private LinearLayout recentRow;
    private TextView recentLabel;
    private boolean drawerOpen;

    /** Dock apps: config.xml's list until you pin or unpin one, then your own order. */
    private List<String> dockOrder() {
        String saved = prefs().getString("dock_order", null);
        if (saved == null) return Arrays.asList(dockApps);
        return saved.isEmpty() ? new ArrayList<>() : Arrays.asList(saved.split(","));
    }

    private void setDockOrder(List<String> pkgs) {
        prefs().edit().putString("dock_order", android.text.TextUtils.join(",", pkgs)).apply();
        buildApps();
    }

    private List<String> recentApps() {
        String s = prefs().getString("recent_apps", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    private void noteRecent(String pkg) {
        List<String> r = recentApps();
        r.remove(pkg);
        r.add(0, pkg);
        while (r.size() > 12) r.remove(r.size() - 1);
        prefs().edit().putString("recent_apps", android.text.TextUtils.join(",", r)).apply();
    }

    private LinearLayout.LayoutParams drawerCell(int i) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        int col = i % DRAWER_COLS;
        p.setMargins(col == 0 ? 0 : dp(8), 0, col == DRAWER_COLS - 1 ? 0 : dp(8), 0);
        return p;
    }

    /** Last dock tile: opens the drawer. */
    private View allAppsTile() {
        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        LiquidGlass bg = liquid(card, dp(10));
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        TextView icon = text(20, "sans-serif-light", Color.WHITE);
        icon.setText("⊞");
        icon.setGravity(Gravity.CENTER);
        TextView name = text(9, "sans-serif-medium", 0xCCFFFFFF);
        name.setText("All apps");
        name.setGravity(Gravity.CENTER);
        inner.addView(icon);
        inner.addView(name);
        card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
        card.setOnClickListener(v -> openAppsDrawer());
        card.setOnFocusChangeListener((v, has) -> {
            bg.setLit(has);
            icon.setTextColor(has ? 0xFF111111 : Color.WHITE);
            name.setTextColor(has ? 0xFF333333 : 0xCCFFFFFF);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .setInterpolator(has ? SPRING : SOFT).setDuration(has ? 420 : 260).withLayer().start();
            if (!has) return;
            lastFocusedId = v.getId();
            title.setText("All apps");
            title.animate().alpha(1f).setDuration(200).start();
            fadeGlow(1f);
            scroll.smoothScrollTo(0, 0);
        });
        dockCards.add(card);
        return card;
    }

    /** Full-screen glass sheet over the background: recently used, then every app A to Z. */
    private View buildAppsDrawer() {
        appsDrawer = new FrameLayout(this);
        appsDrawer.setBackgroundColor(0xB3000000);
        appsDrawer.setVisibility(View.GONE);
        appsDrawer.setClickable(true);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        liquid(panel, dp(28));
        appsDrawer.addView(panel, lp(-1, -1, Gravity.CENTER, dp(40), dp(30), dp(40), dp(30)));

        TextView head = text(22, "sans-serif-light", Color.WHITE);
        head.setText("All apps");
        TextView hint = text(9, "sans-serif", 0x80FFFFFF);
        hint.setText("Hold OK on an app to add it to the dock or remove it  ·  Back to close");
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.BOTTOM);
        top.addView(head);
        top.addView(hint, llp(-2, -2, dp(16), 0, 0, dp(5)));
        panel.addView(top, llp(-1, -2, dp(36), dp(22), dp(36), dp(6)));

        drawerScroll = new android.widget.ScrollView(this);
        drawerScroll.setVerticalScrollBarEnabled(false);
        drawerScroll.setClipToPadding(false);
        drawerScroll.setPadding(dp(24), dp(10), dp(24), dp(30));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        body.setPadding(dp(12), 0, dp(12), 0); // room for the focused tile to grow
        drawerScroll.addView(body);
        panel.addView(drawerScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        recentLabel = drawerLabel("RECENTLY USED");
        body.addView(recentLabel, llp(-2, -2, 0, dp(6), 0, dp(12)));
        recentRow = new LinearLayout(this);
        recentRow.setClipChildren(false);
        recentRow.setClipToPadding(false);
        body.addView(recentRow, llp(-1, -2, 0, 0, 0, dp(22)));
        body.addView(drawerLabel("A – Z"), llp(-2, -2, 0, dp(6), 0, dp(12)));
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        body.addView(grid, new LinearLayout.LayoutParams(-1, -2));
        return appsDrawer;
    }

    private TextView drawerLabel(String s) {
        TextView t = text(10, "sans-serif-medium", 0x99FFFFFF);
        t.setText(s);
        t.setLetterSpacing(0.22f);
        return t;
    }

    private void openAppsDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        appsDrawer.setVisibility(View.VISIBLE);
        appsDrawer.setAlpha(0f);
        appsDrawer.setScaleX(1.03f);
        appsDrawer.setScaleY(1.03f);
        appsDrawer.animate().alpha(1f).scaleX(1f).scaleY(1f).setInterpolator(SOFT).setDuration(320).withLayer().start();
        drawerScroll.scrollTo(0, 0);
        updateTabBar();
        List<View> first = recentCards.isEmpty() ? gridCards : recentCards;
        if (!first.isEmpty()) first.get(0).requestFocus();
    }

    private void closeAppsDrawer(boolean animate) {
        if (!drawerOpen) return;
        drawerOpen = false;
        updateTabBar();
        appsDrawer.animate().cancel();
        if (animate) {
            appsDrawer.animate().alpha(0f).setDuration(200).withLayer()
                    .withEndAction(() -> { if (!drawerOpen) appsDrawer.setVisibility(View.GONE); }).start();
        } else {
            appsDrawer.setVisibility(View.GONE);
        }
        View all = dockCards.isEmpty() ? null : dockCards.get(dockCards.size() - 1);
        if (all != null) all.requestFocus();
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        for (int i = 0; i < pills.size(); i++) {
            View p = pills.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : pills.get(i - 1).getId());
            p.setNextFocusRightId(i == pills.size() - 1 ? p.getId() : pills.get(i + 1).getId());
            p.setNextFocusUpId(tabViews.get(0).getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = pills.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(c.getId()); // wireRecs points it at Discover once there are picks
        }
        // Drawer: the recent row sits above the A-Z grid; focus never leaves the sheet.
        int rc = recentCards.size();
        for (int k = 0; k < rc; k++) {
            View c = recentCards.get(k);
            c.setNextFocusUpId(c.getId());
            c.setNextFocusLeftId(k == 0 ? c.getId() : recentCards.get(k - 1).getId());
            c.setNextFocusRightId(k == rc - 1 ? c.getId() : recentCards.get(k + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(k, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / DRAWER_COLS;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / DRAWER_COLS, col = j % DRAWER_COLS;
            c.setNextFocusUpId(r > 0 ? gridCards.get(j - DRAWER_COLS).getId()
                    : rc > 0 ? recentCards.get(Math.min(col, rc - 1)).getId() : c.getId());
            c.setNextFocusDownId(j + DRAWER_COLS < g ? gridCards.get(j + DRAWER_COLS).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == DRAWER_COLS - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
        wireRecs();
    }

    // ---------------------------------------------------------------- inputs

    private void showInputs(View anchor) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackground(glass(dp(18), GLASS_DARK));

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        List<TextView> rows = new ArrayList<>();

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(inputRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(inputRow("Live TV", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(inputRow("All sources…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(inputRow("Settings", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { inputsPopup = null; anchor.requestFocus(); });
        inputsPopup = pw;
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView inputRow(String label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- picks (Claude + web search)

    private static final long RECS_MAX_AGE_MS = 20L * 3600 * 1000;
    private LinearLayout recsBox;
    private final List<List<View>> recRows = new ArrayList<>();
    private boolean recsLoading;
    private boolean recsJump; // Discover was pressed: show the results as soon as they arrive

    /** Moves focus to the first Discover title, which scrolls the screen down to the results. */
    private void jumpToRecs() {
        recsJump = false;
        if (recRows.isEmpty() || recRows.get(0).isEmpty() || tab != 0 || ambient || drawerOpen || !hasWindowFocus()) return;
        (featureCard != null ? featureCard : recRows.get(0).get(0)).requestFocus();
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("tvhome", MODE_PRIVATE);
    }

    private boolean hasApiKey() {
        String k = getString(R.string.claude_api_key).trim();
        return k.startsWith("sk-ant-");
    }

    /** Shows cached picks, then refreshes from Claude when they're stale (or force). */
    private void loadRecs(boolean force) {
        String cached = prefs().getString("recs_json", null);
        long at = prefs().getLong("recs_at", 0);
        if (cached != null && recRows.isEmpty()) { renderRecs(cached, at); wireFocus(); }
        if (!hasApiKey()) {
            if (cached == null) renderRecs(null, 0);
            return;
        }
        boolean stale = cached == null || System.currentTimeMillis() - at > RECS_MAX_AGE_MS;
        if ((force || stale) && !recsLoading) fetchRecs();
    }

    // Every title Claude returns has these fields; posters are looked up on the TV afterwards.
    private static final String ITEM_SHAPE = "{\"title\":\"\",\"platform\":\"OTT app, or Theatres\",\"kind\":"
            + "\"Series or Film\",\"lang\":\"\",\"rating\":\"IMDb rating like 8.1, or empty\",\"year\":\"\","
            + "\"date\":\"like 24 Oct, or empty\",\"wiki\":\"exact English Wikipedia article title, or empty\","
            + "\"imdb\":\"IMDb id like tt1234567 if you saw it, else empty\",\"note\":\"one short line\"}";

    /** Two Claude searches run side by side (charts, and new releases); results merge into one feed. */
    private void fetchRecs() {
        recsLoading = true;
        toast("Finding what's trending and new…");
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.ENGLISH)
                .format(new java.util.Date());
        String charts = "Today is " + today + ". Use web search (FlixPatrol, Ormax Media, the platforms' own Top 10 "
                + "lists, JustWatch) to find what is trending in India right now across all streaming platforms "
                + "(Netflix, Prime Video, JioHotstar, ZEE5, SonyLIV, Apple TV+, Sun NXT, Manorama MAX and others). "
                + "Reply with ONLY JSON, no markdown: {\"sections\":[{\"title\":\"Top 10 shows in India\",\"ranked\":"
                + "true,\"items\":[10 items]},{\"title\":\"Top 10 movies in India\",\"ranked\":true,\"items\":[10 "
                + "items]}]}. Each item: " + ITEM_SHAPE + ". Only titles you found a source for.";
        String fresh = "Today is " + today + ". Use web search to find (1) Malayalam films and series newly streaming "
                + "on OTT in India this week, (2) Hindi films and series newly streaming on OTT in India this week, "
                + "(3) notable Malayalam and Hindi releases coming soon (theatrical or OTT), and (4) six recent, "
                + "highly rated Malayalam or Hindi titles already on OTT that are well worth watching. Reply with "
                + "ONLY JSON, no markdown: {\"sections\":[{\"title\":\"New on OTT \\u00b7 Malayalam\",\"items\":[]},"
                + "{\"title\":\"New on OTT \\u00b7 Hindi\",\"items\":[]},{\"title\":\"Coming soon\",\"items\":[]},"
                + "{\"title\":\"Highly rated \\u00b7 worth your time\",\"items\":[]}]}. Max 6 items per section. "
                + "Each item: " + ITEM_SHAPE + ". Only titles you found a source for; skip anything uncertain.";
        String seen = seenTitles();
        if (!seen.isEmpty()) {
            // Ticked-off titles stay out of the new-release lists (the Top 10 charts stay complete).
            fresh += " I've already seen these, so leave them out: " + seen + ".";
        }
        String[] out = new String[2];
        String[] err = new String[1];
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        String[] prompts = {charts, fresh};
        for (int k = 0; k < 2; k++) {
            int idx = k;
            new Thread(() -> {
                try { out[idx] = askClaude(prompts[idx]); }
                catch (Exception e) { err[0] = e.getMessage(); }
                finally { done.countDown(); }
            }).start();
        }
        new Thread(() -> {
            try {
                done.await();
                org.json.JSONArray all = new org.json.JSONArray();
                for (String s : out) {
                    if (s == null) continue;
                    org.json.JSONArray secs = new org.json.JSONObject(s).getJSONArray("sections");
                    for (int i = 0; i < secs.length(); i++) all.put(secs.get(i));
                }
                if (all.length() == 0) throw new java.io.IOException(err[0] == null ? "empty reply" : err[0]);
                String json = new org.json.JSONObject().put("sections", all).toString();
                prefs().edit().putString("recs_json", json).putLong("recs_at", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    renderRecs(json, System.currentTimeMillis());
                    wireFocus();
                    if (recsJump) jumpToRecs();
                });
                toast(err[0] == null ? "Discover updated" : "Partly updated: " + err[0]);
            } catch (Exception e) {
                recsJump = false;
                toast("Discover failed: " + e.getMessage());
            } finally {
                recsLoading = false;
            }
        }).start();
    }

    private String askClaude(String prompt) throws Exception {
        org.json.JSONArray messages = new org.json.JSONArray()
                .put(new org.json.JSONObject().put("role", "user").put("content", prompt));
        String text = "";
        for (int turn = 0; turn < 4; turn++) {
            org.json.JSONObject body = new org.json.JSONObject()
                    .put("model", getString(R.string.claude_model))
                    .put("max_tokens", 8000)
                    .put("output_config", new org.json.JSONObject().put("effort", "low"))
                    .put("tools", new org.json.JSONArray().put(new org.json.JSONObject()
                            .put("type", "web_search_20260209").put("name", "web_search").put("max_uses", 6)
                            .put("user_location", new org.json.JSONObject()
                                    .put("type", "approximate").put("country", "IN"))))
                    .put("messages", messages);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("https://api.anthropic.com/v1/messages").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(180000);
            c.setDoOutput(true);
            c.setRequestProperty("x-api-key", getString(R.string.claude_api_key).trim());
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setRequestProperty("content-type", "application/json");
            try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
            int code = c.getResponseCode();
            java.io.InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (is != null && (n = is.read(buf)) > 0) bo.write(buf, 0, n);
            String resp = bo.toString("UTF-8");
            if (code != 200) throw new java.io.IOException("HTTP " + code + " " + resp.substring(0, Math.min(160, resp.length())));
            org.json.JSONObject r = new org.json.JSONObject(resp);
            org.json.JSONArray content = r.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            String lastText = "";
            for (int i = 0; i < content.length(); i++) {
                org.json.JSONObject b = content.getJSONObject(i);
                if ("text".equals(b.optString("type"))) {
                    sb.append(b.optString("text"));
                    if (b.optString("text").indexOf('{') >= 0) lastText = b.optString("text");
                }
            }
            text = lastText.isEmpty() ? sb.toString() : lastText;
            if (!"pause_turn".equals(r.optString("stop_reason"))) break;
            messages.put(new org.json.JSONObject().put("role", "assistant").put("content", content));
        }
        int a = text.indexOf('{'), z = text.lastIndexOf('}');
        if (a < 0 || z <= a) throw new java.io.IOException("no JSON in reply");
        String json = text.substring(a, z + 1);
        new org.json.JSONObject(json).getJSONArray("sections"); // validate
        return json;
    }

    private View featureCard;

    private void renderRecs(String json, long at) {
        recsBox.removeAllViews();
        recRows.clear();
        featureCard = null;
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        TextView h = text(26, "sans-serif-light", Color.WHITE);
        h.setText("Discover");
        h.setShadowLayer(dp(10), 0, dp(1), 0x99000000);
        head.addView(h);
        TextView when = text(9, "sans-serif", 0x80FFFFFF);
        when.setText(at > 0 ? "Trending in India and new Malayalam and Hindi releases  ·  updated "
                + android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS) + "  ·  hold OK on a title to tick it off" : "");
        head.addView(when, new LinearLayout.LayoutParams(0, -2, 1f));
        ((LinearLayout.LayoutParams) when.getLayoutParams()).setMargins(dp(14), 0, 0, dp(6));
        discoverPills.clear();
        TextView ask = pill("Ask", "🎙");
        ask.setOnClickListener(v -> startAsk());
        TextView refresh = pill("Refresh", "↻");
        refresh.setOnClickListener(v -> {
            if (!hasApiKey()) { toast("Add your Claude API key in secrets.xml first"); return; }
            recsJump = true;
            loadRecs(true);
        });
        TextView partyPill = pill("Watch party", "🍿");
        partyPill.setOnClickListener(v -> openParty());
        for (TextView p : new TextView[]{ask, partyPill, refresh}) {
            pills.remove(p); // pill() is shared with the top bar; these belong to Discover
            p.setOnFocusChangeListener(discoverPillFocus(p));
            head.addView(p, llp(-2, dp(PILL_H), dp(8), 0, 0, dp(2)));
            discoverPills.add(p);
        }
        recsBox.addView(head, llp(-1, -2, dp(6), dp(40), 0, dp(14)));
        posterByKey.clear();
        try {
            if (json == null) {
                TextView hint = text(12, "sans-serif", 0xB3FFFFFF);
                hint.setText("Add your Claude API key in launcher/res/values/secrets.xml to see this week's Malayalam and Hindi releases.");
                recsBox.addView(hint, llp(-2, -2, dp(6), 0, 0, dp(10)));
                return;
            }
            org.json.JSONArray secs = new org.json.JSONObject(json).getJSONArray("sections");
            // The top pick gets a big banner (the first one you haven't seen); it's still in its row below, too.
            for (int s = 0; s < secs.length() && featureCard == null; s++) {
                org.json.JSONArray items = secs.getJSONObject(s).getJSONArray("items");
                for (int i = 0; i < items.length() && featureCard == null; i++) {
                    if (isSeen(items.getJSONObject(i))) continue;
                    recsBox.addView(featureBanner(items.getJSONObject(i), secs.getJSONObject(s).optString("title")),
                            llp(-1, dp(196), 0, 0, 0, dp(10)));
                }
            }
            // Your last question's answers come first, above the regular rows.
            org.json.JSONArray asked = askSections();
            if (asked.length() > 0) {
                org.json.JSONArray all = new org.json.JSONArray();
                for (int s = 0; s < asked.length(); s++) all.put(asked.get(s));
                for (int s = 0; s < secs.length(); s++) all.put(secs.get(s));
                secs = all;
            }
            for (int s = 0; s < secs.length(); s++) {
                org.json.JSONObject sec = secs.getJSONObject(s);
                org.json.JSONArray items = sec.getJSONArray("items");
                if (items.length() == 0) continue;
                boolean ranked = sec.optBoolean("ranked");
                LinearLayout box = new LinearLayout(this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setClipChildren(false);
                TextView label = text(11, "sans-serif-medium", 0xB3FFFFFF);
                label.setText(sec.optString("title").toUpperCase());
                label.setLetterSpacing(0.22f);
                label.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
                box.addView(label, llp(-2, -2, dp(6), dp(8), 0, dp(4)));
                android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(this);
                hsv.setHorizontalScrollBarEnabled(false);
                hsv.setClipChildren(false);
                hsv.setClipToPadding(false);
                hsv.setSmoothScrollingEnabled(true);
                LinearLayout row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(dp(6), dp(14), dp(40), dp(10));
                hsv.addView(row);
                List<View> cards = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject it = items.getJSONObject(i);
                    LinearLayout cell = posterCard(it, ranked ? i + 1 : 0, box);
                    row.addView(cell, llp(-2, -2, 0, 0, dp(ranked ? 22 : 16), 0));
                    cards.add(cell.getChildAt(0));
                }
                box.addView(hsv, new LinearLayout.LayoutParams(-1, -2));
                recsBox.addView(box, new LinearLayout.LayoutParams(-1, -2));
                recRows.add(cards);
            }
        } catch (Exception e) {
            recsBox.removeAllViews();
            recRows.clear();
        }
    }

    /** Wide card for the #1 pick: blurred artwork behind, the poster, title, details and Claude's note. */
    private View featureBanner(org.json.JSONObject it, String section) {
        FrameLayout card = new FrameLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(18)); }
        });
        card.setBackgroundColor(0xFF1A1C22);
        ImageView back = new ImageView(this);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setColorFilter(0x8C000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            back.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(24), dp(24),
                    android.graphics.Shader.TileMode.CLAMP));
        }
        card.addView(back, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xCC000000, 0x33000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(18), dp(28), dp(18));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackgroundColor(0xFF2A2C33);
        poster.setClipToOutline(true);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10)); }
        });
        row.addView(poster, llp(dp(106), dp(160), 0, 0, dp(22), 0));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView kicker = text(9, "sans-serif-medium", 0xFFFFC266);
        kicker.setText(("#1 · " + section).toUpperCase(Locale.getDefault()));
        kicker.setLetterSpacing(0.2f);
        info.addView(kicker);
        TextView t = text(26, "sans-serif-medium", Color.WHITE);
        t.setText(it.optString("title"));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(t, llp(-1, -2, 0, dp(2), 0, 0));
        StringBuilder meta = new StringBuilder(subline(it));
        for (String k : new String[]{"kind", "lang", "year"}) {
            String v = it.optString(k);
            if (!v.isEmpty() && meta.indexOf(v) < 0) meta.append(meta.length() > 0 ? " · " : "").append(v);
        }
        TextView m = text(11, "sans-serif-medium", 0xFF8FD4FF);
        m.setText(meta);
        info.addView(m, llp(-2, -2, 0, dp(4), 0, dp(8)));
        TextView n = text(12, "sans-serif", 0xD9FFFFFF);
        n.setText(it.optString("note"));
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(n);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(row, new FrameLayout.LayoutParams(-1, -1));

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(18));
        ring.setStroke(dp(2), 0xF2FFFFFF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        loadPoster(poster, it);
        loadPoster(back, it);
        card.setOnClickListener(v -> showDetails(it, poster));
        card.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f)
                    .setInterpolator(has ? SPRING : SOFT).setDuration(has ? 360 : 200).withLayer().start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(140).start();
            if (has) { lastFocusedId = v.getId(); showDiscoverPage(); }
        });
        featureCard = card;
        return card;
    }

    /** Slides the home screen away so the Discover page fills the screen. */
    private void showDiscoverPage() {
        fadeGlow(0.35f);
        title.animate().alpha(0f).setDuration(150).start();
        scroll.smoothScrollTo(0, recsBox.getTop());
    }

    private static final int POSTER_W = 116;

    /** A 2:3 poster (focusable, child 0) with the title and "★ rating · platform" under it. */
    private LinearLayout posterCard(org.json.JSONObject it, int rank, View section) {
        String name = it.optString("title");
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);

        FrameLayout poster = new FrameLayout(this);
        poster.setId(View.generateViewId());
        poster.setFocusable(true);
        poster.setClickable(true);
        poster.setClipToOutline(true);
        poster.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        // Placeholder until (or if no) artwork arrives: a tinted card with the title set in type.
        int hue = (name.hashCode() & 0x7fffffff) % 360;
        poster.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
                Color.HSVToColor(new float[]{hue, 0.55f, 0.42f}), Color.HSVToColor(new float[]{(hue + 40) % 360, 0.6f, 0.16f})}));
        TextView ph = text(13, "sans-serif-medium", 0xE6FFFFFF);
        ph.setText(name);
        ph.setGravity(Gravity.CENTER);
        ph.setPadding(dp(10), 0, dp(10), 0);
        poster.addView(ph, new FrameLayout.LayoutParams(-1, -1));
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.addView(art, new FrameLayout.LayoutParams(-1, -1));
        if (rank > 0) {
            // Big outlined rank number in the corner, Top 10 style.
            View fade = new View(this);
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
            poster.addView(fade, new FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM));
            TextView num = text(46, "sans-serif-black", Color.WHITE);
            num.setText(String.valueOf(rank));
            num.setIncludeFontPadding(false);
            num.setShadowLayer(dp(6), 0, 0, 0xCC000000);
            poster.addView(num, lp(-2, -2, Gravity.BOTTOM | Gravity.START, dp(8), 0, 0, dp(2)));
        }
        if (isSeen(it)) {
            // Watched: dim it and tick it, so new things stand out.
            View dimmer = new View(this);
            dimmer.setBackgroundColor(0x99000000);
            poster.addView(dimmer, new FrameLayout.LayoutParams(-1, -1));
            TextView tick = text(10, "sans-serif-medium", 0xFF111111);
            tick.setText("✓ SEEN");
            tick.setLetterSpacing(0.1f);
            tick.setPadding(dp(7), dp(2), dp(7), dp(2));
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(dp(8));
            tb.setColor(0xE6FFFFFF);
            tick.setBackground(tb);
            poster.addView(tick, lp(-2, -2, Gravity.TOP | Gravity.START, dp(7), dp(7), 0, 0));
        }
        String rating = it.optString("rating");
        if (!rating.isEmpty()) {
            TextView badge = text(10, "sans-serif-medium", Color.WHITE);
            badge.setText("★ " + rating);
            badge.setPadding(dp(7), dp(2), dp(7), dp(2));
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(dp(8));
            b.setColor(0xB3000000);
            badge.setBackground(b);
            poster.addView(badge, lp(-2, -2, Gravity.TOP | Gravity.END, 0, dp(7), dp(7), 0));
        }
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xF2FFFFFF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        poster.addView(shine, new FrameLayout.LayoutParams(-1, -1));
        cell.addView(poster, new LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_W * 3 / 2)));

        TextView t = text(11, "sans-serif-medium", Color.WHITE);
        t.setText(name);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        cell.addView(t, new LinearLayout.LayoutParams(dp(POSTER_W), -2));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).topMargin = dp(10);
        TextView s = text(10, "sans-serif", 0xB3FFFFFF);
        s.setText(subline(it));
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.addView(s, new LinearLayout.LayoutParams(dp(POSTER_W), -2));

        loadPoster(art, it);
        poster.setOnClickListener(v -> showDetails(it, art));
        poster.setOnLongClickListener(v -> { toggleSeen(it); return true; });
        posterByKey.put(recKey(it), poster);
        poster.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .setInterpolator(has ? SPRING : SOFT).setDuration(has ? 300 : 180).withLayer().start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(120).start();
            t.animate().translationY(has ? dp(8) : 0).setDuration(180).start();
            s.animate().translationY(has ? dp(8) : 0).setDuration(180).start();
            if (has) {
                lastFocusedId = v.getId();
                fadeGlow(0.35f);
                title.animate().alpha(0f).setDuration(150).start();
                int screenH = getResources().getDisplayMetrics().heightPixels;
                int y = recsBox.getTop() + section.getTop() - (int) (screenH * 0.14f);
                int max = Math.max(0, content.getHeight() - scroll.getHeight());
                scroll.smoothScrollTo(0, Math.max(0, Math.min(y, max)));
            }
        });
        return cell;
    }

    private static String subline(org.json.JSONObject it) {
        StringBuilder sb = new StringBuilder();
        if (!it.optString("rating").isEmpty()) sb.append("★ ").append(it.optString("rating"));
        for (String k : new String[]{"platform", "date"}) {
            String v = it.optString(k);
            if (!v.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(v);
        }
        return sb.toString();
    }

    /** Full-screen dimmed sheet with the poster, details, and buttons to watch it. */
    private void showDetails(org.json.JSONObject it, ImageView art) {
        String name = it.optString("title");
        FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0xB3000000);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setPadding(dp(28), dp(28), dp(32), dp(28));
        sheet.setBackground(glass(dp(26), GLASS_DARK));
        dim.addView(sheet, new FrameLayout.LayoutParams(dp(640), -2, Gravity.CENTER));

        ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.CENTER_CROP);
        big.setImageDrawable(art.getDrawable());
        big.setBackgroundColor(0xFF22242A);
        big.setClipToOutline(true);
        big.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        sheet.addView(big, llp(dp(170), dp(255), 0, 0, dp(26), 0));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView t = text(24, "sans-serif-medium", Color.WHITE);
        t.setText(name);
        info.addView(t);
        StringBuilder meta = new StringBuilder(subline(it));
        for (String k : new String[]{"kind", "lang", "year"}) {
            String v = it.optString(k);
            if (!v.isEmpty() && meta.indexOf(v) < 0) meta.append(meta.length() > 0 ? " · " : "").append(v);
        }
        TextView m = text(12, "sans-serif-medium", 0xFF8FD4FF);
        m.setText(meta);
        info.addView(m, llp(-2, -2, 0, dp(6), 0, dp(12)));
        TextView n = text(14, "sans-serif", 0xE6FFFFFF);
        n.setText(it.optString("note"));
        n.setLineSpacing(0, 1.15f);
        info.addView(n, llp(-1, -2, 0, 0, 0, dp(22)));

        PopupWindow pw = new PopupWindow(dim, -1, -1, true);
        LinearLayout buttons = new LinearLayout(this);
        info.addView(buttons);
        List<TextView> row = new ArrayList<>();
        String pkg = appFor(it.optString("platform"));
        if (pkg != null) row.add(inputRow("▶  Open " + labelFor(pkg), () -> openPackage(pkg)));
        if (isInstalled(STREMIO)) row.add(inputRow("Open in Stremio", () -> openStremio(it)));
        row.add(inputRow(isSeen(it) ? "Not seen" : "✓  Seen it", () -> toggleSeen(it)));
        row.add(inputRow("Close", () -> { }));
        for (TextView b : row) {
            b.setBackground(glass(dp(12), GLASS));
            b.setOnFocusChangeListener((v, has) -> {
                ((GradientDrawable) v.getBackground()).setColors(has ? GLASS_LIT : GLASS);
                ((TextView) v).setTextColor(has ? 0xFF111111 : Color.WHITE);
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setInterpolator(has ? SPRING : SOFT)
                        .setDuration(has ? 260 : 160).start();
            });
            b.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            buttons.addView(b, llp(-2, dp(40), 0, 0, dp(12), 0));
        }
        for (int i = 0; i < row.size(); i++) {
            TextView b = row.get(i);
            b.setNextFocusLeftId(row.get(Math.max(0, i - 1)).getId());
            b.setNextFocusRightId(row.get(Math.min(row.size() - 1, i + 1)).getId());
            b.setNextFocusUpId(b.getId());
            b.setNextFocusDownId(b.getId());
        }
        View from = getCurrentFocus();
        pw.setAnimationStyle(android.R.style.Animation_Dialog);
        pw.setOnDismissListener(() -> { if (from != null) from.requestFocus(); });
        pw.showAtLocation(rootView, Gravity.CENTER, 0, 0);
        row.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- where to watch

    private static final String STREMIO = "com.stremio.one";
    private static final String[][] PLATFORM_APPS = {
            {"netflix", "com.netflix.ninja"}, {"prime", "com.amazon.amazonvideo.livingroom"},
            {"hotstar", "in.startv.hotstar"}, {"disney", "com.disney.disneyplus"},
            {"apple", "com.apple.atve.androidtv.appletv"}, {"youtube", "com.google.android.youtube.tv"},
            {"zee5", "com.graymatrix.did"}, {"sony", "com.sonyliv"}, {"sun nxt", "com.suntv.sunnxt"},
            {"manorama", "com.mmtv.manoramamax.android"}, {"hulu", "com.hulu.livingroomplus"},
            {"hbo", "com.wbd.stream"}, {"max", "com.wbd.stream"}, {"tubi", "com.tubitv"}};

    private String appFor(String platform) {
        String p = platform.toLowerCase(Locale.ROOT);
        for (String[] e : PLATFORM_APPS) if (p.contains(e[0]) && isInstalled(e[1])) return e[1];
        return null;
    }

    private String labelFor(String pkg) {
        try { return String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }

    /**
     * Stremio's own links: straight to the title page. Without an IMDb id from Claude, Stremio's public catalog
     * (Cinemeta) finds it by name; a plain Stremio search is the last resort.
     */
    private void openStremio(org.json.JSONObject it) {
        String imdb = it.optString("imdb");
        String type = it.optString("kind").toLowerCase(Locale.ROOT).startsWith("series") ? "series" : "movie";
        if (imdb.matches("tt\\d+")) { openStremioUri("stremio:///detail/" + type + "/" + imdb); return; }
        String title = it.optString("title"), year = it.optString("year");
        new Thread(() -> {
            String uri = "stremio:///search?search=" + android.net.Uri.encode(title);
            try {
                org.json.JSONArray metas = new org.json.JSONObject(httpGet("https://v3-cinemeta.strem.io/catalog/" + type
                        + "/top/search=" + android.net.Uri.encode(title) + ".json")).optJSONArray("metas");
                for (int i = 0; metas != null && i < metas.length(); i++) {
                    org.json.JSONObject m = metas.getJSONObject(i);
                    String a = norm(m.optString("name")), b = norm(title);
                    boolean sameYear = year.isEmpty() || m.optString("releaseInfo").startsWith(year);
                    // "Anomie: The Equation of Death" is just "Anomie" in the catalog; the year keeps that safe.
                    boolean sameName = a.equals(b) || !year.isEmpty() && !a.isEmpty() && (a.startsWith(b) || b.startsWith(a));
                    if (sameName && sameYear) { uri = "stremio:///detail/" + type + "/" + m.optString("id"); break; }
                }
            } catch (Exception ignored) { }
            String u = uri;
            runOnUiThread(() -> openStremioUri(u));
        }).start();
    }

    private void openStremioUri(String uri) {
        if (!launch(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(STREMIO))) openPackage(STREMIO);
    }

    // ---------------------------------------------------------------- Discover: seen it, Ask (voice)

    private static final int REQ_VOICE = 41;
    private static final long ASK_MAX_AGE_MS = 12L * 3600 * 1000;
    private final List<TextView> discoverPills = new ArrayList<>();
    private final Map<String, View> posterByKey = new HashMap<>();
    private boolean asking;

    private static String recKey(org.json.JSONObject it) {
        return (it.optString("title") + "|" + it.optString("year")).toLowerCase(Locale.ROOT);
    }

    private Set<String> seenSet() {
        Set<String> s = new HashSet<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(prefs().getString("seen", "[]"));
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (org.json.JSONException ignored) { }
        return s;
    }

    private boolean isSeen(org.json.JSONObject it) { return seenSet().contains(recKey(it)); }

    /** Ticks a title off (or back on), redraws Discover and keeps the same title selected. */
    private void toggleSeen(org.json.JSONObject it) {
        Set<String> s = seenSet();
        String key = recKey(it);
        boolean now = !s.remove(key);
        if (now) s.add(key);
        org.json.JSONArray a = new org.json.JSONArray();
        for (String k : s) a.put(k);
        prefs().edit().putString("seen", a.toString()).apply();
        renderRecs(prefs().getString("recs_json", null), prefs().getLong("recs_at", 0));
        wireFocus();
        View again = posterByKey.get(key);
        if (again != null) {
            again.requestFocus();
            // The page was just rebuilt: scroll to the title once the new layout has its real positions.
            recsBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    recsBox.removeOnLayoutChangeListener(this);
                    View.OnFocusChangeListener f = again.getOnFocusChangeListener();
                    if (f != null && again.hasFocus()) f.onFocusChange(again, true);
                }
            });
        }
        toast(it.optString("title") + (now ? " ticked off" : " back on your list"));
    }

    /** Titles you've ticked off, for Claude to leave out next time. */
    private String seenTitles() {
        StringBuilder sb = new StringBuilder();
        for (String k : seenSet()) {
            if (sb.length() > 1500) break;
            sb.append(sb.length() > 0 ? "; " : "").append(k.substring(0, k.indexOf('|')));
        }
        return sb.toString();
    }

    private View.OnFocusChangeListener discoverPillFocus(TextView p) {
        View.OnFocusChangeListener base = p.getOnFocusChangeListener();
        return (v, has) -> {
            if (base != null) base.onFocusChange(v, has);
            if (has) showDiscoverPage(); // the shared pill handler scrolled to the top; stay on Discover
        };
    }

    /** "Ask": Google's voice input (the TV's own "Speak now"), or the keyboard if there's no voice input. */
    private void startAsk() {
        if (!hasApiKey()) { toast("Add your Claude API key in secrets.xml first"); return; }
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Ask Discover, e.g. \"new Malayalam movies this year\"");
        // Two apps answer this on the Hisense; go straight to Google's TV voice input instead of asking which.
        if (isInstalled("com.google.android.katniss")) i.setPackage("com.google.android.katniss");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (RuntimeException e) {
            typeAsk();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_VOICE || result != RESULT_OK || data == null) return;
        List<String> heard = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (heard != null && !heard.isEmpty() && !heard.get(0).trim().isEmpty()) runAsk(heard.get(0).trim());
    }

    private void typeAsk() {
        android.widget.EditText box = new android.widget.EditText(this);
        box.setSingleLine(true);
        box.setHint("e.g. new Malayalam movies this year");
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        new android.app.AlertDialog.Builder(this).setTitle("Ask Discover").setView(box)
                .setPositiveButton("Ask", (d, w) -> { String q = box.getText().toString().trim(); if (!q.isEmpty()) runAsk(q); })
                .setNegativeButton("Cancel", null).show();
    }

    /** One Claude call with web search; the answer becomes the first row of Discover, each title one click from Stremio. */
    private void runAsk(String q) {
        if (asking) return;
        asking = true;
        toast("Looking up: “" + q + "”");
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.ENGLISH).format(new java.util.Date());
        String seen = seenTitles();
        String prompt = "Today is " + today + ". I'm choosing something to watch on my TV (I live in Florida and watch "
                + "Malayalam, Hindi and English titles, mostly through Stremio, so anything with an IMDb entry works). "
                + "I asked: “" + q + "”. Use web search to answer with real titles. Reply with ONLY JSON, no "
                + "markdown: {\"sections\":[{\"title\":\"short heading for the answer, under 40 characters\",\"items\":"
                + "[up to 15 items, best first]}]}. Each item: " + ITEM_SHAPE + ". Always fill imdb with the IMDb id. "
                + "Only titles you found a source for."
                + (seen.isEmpty() ? "" : " I've already seen these, leave them out unless I asked for them: " + seen + ".");
        new Thread(() -> {
            try {
                String json = askClaude(prompt);
                org.json.JSONArray secs = new org.json.JSONObject(json).getJSONArray("sections");
                for (int s = 0; s < secs.length(); s++) {
                    org.json.JSONObject sec = secs.getJSONObject(s);
                    sec.put("title", "🎙 " + q + "  →  " + sec.optString("title"));
                }
                prefs().edit().putString("ask_json", secs.toString()).putLong("ask_at", System.currentTimeMillis()).apply();
                runOnUiThread(() -> {
                    renderRecs(prefs().getString("recs_json", null), prefs().getLong("recs_at", 0));
                    wireFocus();
                    if (!recRows.isEmpty() && tab == 0 && !drawerOpen) recRows.get(0).get(0).requestFocus();
                });
            } catch (Exception e) {
                toast("Couldn't answer that: " + e.getMessage());
            } finally {
                asking = false;
            }
        }).start();
    }

    // ---------------------------------------------------------------- watch party

    private static final long PARTY_LINGER_MS = 10 * 60 * 1000L; // phones keep showing the picks this long
    private WatchParty party;
    private String partyUrl;
    private FrameLayout partyLayer;
    private LinearLayout partyGuests;
    private TextView partyPick, partyStatus;
    private boolean partyOpen, partyPicking;
    private int uiFocusability;
    private final Runnable endParty = () -> { if (!partyOpen && party != null) { party.stop(); party = null; } };

    /** A QR code friends scan to pick their mood; Claude then picks three films for the whole group. */
    private void openParty() {
        if (!hasApiKey()) { toast("Add your Claude API key in secrets.xml first"); return; }
        if (partyOpen) return;
        handler.removeCallbacks(endParty);
        if (party == null) {
            party = new WatchParty(() -> runOnUiThread(this::renderGuests));
            partyUrl = party.start();
            if (partyUrl == null) { party = null; toast("The TV needs to be on Wi-Fi or Ethernet for a watch party"); return; }
        }
        partyOpen = true;
        buildParty();
        renderGuests();
        partyLayer.setAlpha(0f);
        partyLayer.animate().alpha(1f).setDuration(350).start();
        partyPick.requestFocus();
        // Keep the remote inside the party card while it's open.
        uiFocusability = ui.getDescendantFocusability();
        ui.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    private void closeParty() {
        if (!partyOpen) return;
        partyOpen = false;
        ui.setDescendantFocusability(uiFocusability);
        View layer = partyLayer;
        layer.animate().alpha(0f).setDuration(250).withEndAction(() -> rootView.removeView(layer)).start();
        if (partyPicking) return; // the picks will still arrive in Discover
        handler.postDelayed(endParty, PARTY_LINGER_MS);
        if (!recRows.isEmpty()) recRows.get(0).get(0).requestFocus();
    }

    private void buildParty() {
        partyLayer = new FrameLayout(this);
        partyLayer.setBackgroundColor(0xB3000000);
        LinearLayout card = new LinearLayout(this);
        card.setPadding(dp(28), dp(26), dp(30), dp(24));
        liquid(card, dp(28));
        partyLayer.addView(card, new FrameLayout.LayoutParams(dp(820), -2, Gravity.CENTER));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(QrCode.bitmap(partyUrl, 8));
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setClipToOutline(true);
        qr.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        left.addView(qr, new LinearLayout.LayoutParams(dp(196), dp(196)));
        TextView scan = text(10, "sans-serif-medium", 0xE6FFFFFF);
        scan.setText("Scan with your phone camera");
        left.addView(scan, llp(-2, -2, 0, dp(10), 0, 0));
        TextView link = text(8, "sans-serif", 0x80FFFFFF);
        link.setText(partyUrl.replace("http://", "") + "  ·  same Wi-Fi");
        left.addView(link, llp(-2, -2, 0, dp(2), 0, 0));
        card.addView(left, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = llp(0, -2, dp(28), 0, 0, 0);
        rp.weight = 1;
        card.addView(right, rp);
        TextView h = text(26, "sans-serif-light", Color.WHITE);
        h.setText("🍿  Watch party");
        right.addView(h);
        TextView sub = text(11, "sans-serif", 0xB3FFFFFF);
        sub.setText("Everyone scans the code and picks a mood. Claude finds three films the whole group will enjoy, "
                + "leaving out ones you've ticked off.");
        sub.setLineSpacing(0, 1.12f);
        right.addView(sub, llp(-1, -2, 0, dp(4), 0, dp(14)));
        TextView who = text(8, "sans-serif-medium", 0x99FFFFFF);
        who.setText("WHO'S IN");
        who.setLetterSpacing(0.2f);
        right.addView(who);
        partyGuests = new LinearLayout(this);
        partyGuests.setOrientation(LinearLayout.VERTICAL);
        partyGuests.setMinimumHeight(dp(92));
        right.addView(partyGuests, llp(-1, -2, 0, dp(6), 0, dp(12)));
        partyStatus = text(10, "sans-serif", 0xB3FFFFFF);
        partyStatus.setVisibility(View.GONE);
        right.addView(partyStatus, llp(-2, -2, 0, 0, 0, dp(8)));
        LinearLayout buttons = new LinearLayout(this);
        partyPick = pill("Pick films", "✨");
        partyPick.setOnClickListener(v -> pickPartyFilms());
        TextView close = pill("Close", "✕");
        close.setOnClickListener(v -> closeParty());
        for (TextView p : new TextView[]{partyPick, close}) {
            pills.remove(p); // pill() is shared with the top bar; these belong to the party card
            buttons.addView(p, llp(-2, dp(PILL_H + 4), 0, 0, dp(10), 0));
        }
        partyPick.setNextFocusLeftId(partyPick.getId());
        close.setNextFocusRightId(close.getId());
        for (View p : new View[]{partyPick, close}) { p.setNextFocusUpId(p.getId()); p.setNextFocusDownId(p.getId()); }
        right.addView(buttons);
        rootView.addView(partyLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void renderGuests() {
        if (!partyOpen || party == null) return;
        partyGuests.removeAllViews();
        List<org.json.JSONObject> gs = party.guests();
        if (gs.isEmpty()) {
            TextView none = text(11, "sans-serif", 0x80FFFFFF);
            none.setText("Nobody yet. Scan the code to join.");
            partyGuests.addView(none);
        }
        for (org.json.JSONObject g : gs) {
            StringBuilder what = new StringBuilder();
            for (String key : new String[]{"moods", "langs"}) {
                org.json.JSONArray a = g.optJSONArray(key);
                for (int i = 0; a != null && i < a.length(); i++) what.append(what.length() > 0 ? ",  " : "").append(a.optString(i));
            }
            if (!g.optString("avoid").isEmpty()) what.append(what.length() > 0 ? "  ·  " : "").append("not ").append(g.optString("avoid"));
            TextView row = text(12, "sans-serif", 0xE6FFFFFF);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setText(android.text.Html.fromHtml("<b>" + android.text.TextUtils.htmlEncode(g.optString("name"))
                    + "</b>&nbsp;&nbsp;<font color='#99FFFFFF'>" + android.text.TextUtils.htmlEncode(
                    what.length() > 0 ? what.toString() : "happy with anything") + "</font>", android.text.Html.FROM_HTML_MODE_LEGACY));
            partyGuests.addView(row, llp(-1, -2, 0, 0, 0, dp(6)));
        }
        if (!partyPicking) {
            partyPick.setText("✨  Pick films" + (gs.isEmpty() ? "" : " for " + gs.size() + (gs.size() == 1 ? " person" : " people")));
        }
    }

    private void pickPartyFilms() {
        List<org.json.JSONObject> gs = party.guests();
        if (gs.isEmpty()) { partyStatus.setText("Waiting for someone to scan the code"); partyStatus.setVisibility(View.VISIBLE); return; }
        if (partyPicking) return;
        partyPicking = true;
        party.setPicking();
        partyPick.setText("✨  Picking…");
        partyStatus.setText("Claude is picking three films for the group. This takes about a minute.");
        partyStatus.setVisibility(View.VISIBLE);
        StringBuilder who = new StringBuilder(), names = new StringBuilder();
        for (org.json.JSONObject g : gs) {
            names.append(names.length() > 0 ? ", " : "").append(g.optString("name"));
            who.append("- ").append(g.optString("name")).append(": in the mood for ")
                    .append(list(g.optJSONArray("moods"), "anything")).append("; languages ")
                    .append(list(g.optJSONArray("langs"), "any"));
            if (!g.optString("avoid").isEmpty()) who.append("; not in the mood for ").append(g.optString("avoid"));
            who.append('\n');
        }
        Set<String> apps = new java.util.LinkedHashSet<>();
        for (String[] e : PLATFORM_APPS) if (isInstalled(e[1])) apps.add(labelFor(e[1]));
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.ENGLISH).format(new java.util.Date());
        String seen = seenTitles();
        String prompt = "Today is " + today + ". We're having a watch party at home (in Florida) and want one film to "
                + "watch together. Each guest said what they're in the mood for:\n" + who
                + "Pick exactly 3 films (not series) the whole group will enjoy together, balancing everyone's moods "
                + "and languages; when languages differ, prefer a shared one or a film that works well with English "
                + "subtitles. We can watch on " + String.join(", ", apps) + ", and on Stremio, which plays anything with "
                + "an IMDb entry. Use web search to check each film is real, well reviewed and where it streams. Reply "
                + "with ONLY JSON, no markdown: {\"sections\":[{\"title\":\"Watch party picks\",\"items\":[3 items, "
                + "best first]}]}. Each item: " + ITEM_SHAPE + ". Always fill imdb with the IMDb id. In note, say in "
                + "under 70 characters why it suits this group, naming guests where it helps."
                + (seen.isEmpty() ? "" : " We've already seen these, leave them out: " + seen + ".");
        String title = "🍿 Watch party  →  " + names;
        new Thread(() -> {
            try {
                org.json.JSONArray secs = new org.json.JSONObject(askClaude(prompt)).getJSONArray("sections");
                org.json.JSONObject sec = secs.getJSONObject(0).put("title", title);
                // Only real films: each needs an IMDb id (Stremio opens it), and no series or half-written entries.
                org.json.JSONArray got = sec.getJSONArray("items"), items = new org.json.JSONArray();
                for (int i = 0; i < got.length() && items.length() < 3; i++) {
                    org.json.JSONObject it = got.getJSONObject(i);
                    if (it.optString("imdb").matches("tt\\d+") && !it.optString("kind").toLowerCase(Locale.ROOT).startsWith("series")
                            && !it.optString("note").toLowerCase(Locale.ROOT).contains("placeholder")) items.put(it);
                }
                if (items.length() == 0) throw new java.io.IOException("no films came back");
                sec.put("items", items);
                prefs().edit().putString("ask_json", new org.json.JSONArray().put(sec).toString())
                        .putLong("ask_at", System.currentTimeMillis()).apply();
                party.setPicks(items);
                runOnUiThread(() -> {
                    partyPicking = false;
                    closeParty();
                    handler.removeCallbacks(endParty);
                    handler.postDelayed(endParty, PARTY_LINGER_MS);
                    renderRecs(prefs().getString("recs_json", null), prefs().getLong("recs_at", 0));
                    wireFocus();
                    showDiscoverPage();
                    if (!recRows.isEmpty()) recRows.get(0).get(0).requestFocus();
                });
            } catch (Exception e) {
                party.setFailed();
                runOnUiThread(() -> {
                    partyPicking = false;
                    if (!partyOpen) { handler.postDelayed(endParty, PARTY_LINGER_MS); return; }
                    partyStatus.setText("Couldn't pick films: " + e.getMessage());
                    renderGuests();
                });
            }
        }).start();
    }

    private static String list(org.json.JSONArray a, String none) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            // Drop the emoji from "😂 Funny" so Claude reads plain words.
            sb.append(sb.length() > 0 ? ", " : "").append(a.optString(i).replaceAll("^[^\\p{L}]+", ""));
        }
        return sb.length() > 0 ? sb.toString() : none;
    }

    private org.json.JSONArray askSections() {
        if (System.currentTimeMillis() - prefs().getLong("ask_at", 0) > ASK_MAX_AGE_MS) return new org.json.JSONArray();
        try { return new org.json.JSONArray(prefs().getString("ask_json", "[]")); }
        catch (org.json.JSONException e) { return new org.json.JSONArray(); }
    }

    // ---------------------------------------------------------------- posters (Wikipedia, then iTunes)

    private final android.util.LruCache<String, Bitmap> posterMem = new android.util.LruCache<>(48);
    private final java.util.concurrent.ExecutorService posterPool = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
        Thread th = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        });
        th.setDaemon(true);
        return th;
    });

    private void loadPoster(ImageView iv, org.json.JSONObject it) {
        String key = (it.optString("title") + "|" + it.optString("year")).toLowerCase(Locale.ROOT);
        iv.setTag(key);
        Bitmap mem = posterMem.get(key);
        if (mem != null) { iv.setImageBitmap(mem); return; }
        posterPool.execute(() -> {
            Bitmap bm = null;
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "posters3"); // v3: IMDb first, title-checked matches
                dir.mkdirs();
                String base = Integer.toHexString(key.hashCode());
                java.io.File f = new java.io.File(dir, base + ".jpg"), none = new java.io.File(dir, base + ".none");
                if (!f.exists() && !none.exists()) {
                    String url = posterUrl(it);
                    if (url == null) { none.createNewFile(); return; }
                    java.io.File part = new java.io.File(dir, base + ".part");
                    try (java.io.InputStream in = open(url).getInputStream();
                         java.io.OutputStream o = new java.io.FileOutputStream(part)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    part.renameTo(f);
                }
                if (!f.exists()) return;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= dp(POSTER_W)) o.inSampleSize *= 2;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeFile(f.getPath(), o);
            } catch (Exception ignored) { }
            if (bm == null) return;
            Bitmap b = bm;
            posterMem.put(key, b);
            runOnUiThread(() -> {
                if (!key.equals(iv.getTag())) return;
                iv.setImageBitmap(b);
                iv.setAlpha(0f);
                iv.animate().alpha(1f).setDuration(220).start();
            });
        });
    }

    /** Poster image URL: IMDb's search suggestions (best for new Indian releases), then Wikipedia, then iTunes. */
    private String posterUrl(org.json.JSONObject it) {
        String name = it.optString("title"), wiki = it.optString("wiki"), year = it.optString("year");
        boolean series = it.optString("kind").toLowerCase(Locale.ROOT).startsWith("series");
        String imdb = imdbPoster(it, name, year, series);
        if (imdb != null) return imdb;
        String common = "https://en.wikipedia.org/w/api.php?action=query&format=json&redirects=1&prop=pageimages"
                + "&piprop=thumbnail&pithumbsize=400&pilicense=any";
        String[] tries = {
                wiki.isEmpty() ? null : common + "&titles=" + android.net.Uri.encode(wiki),
                common + "&generator=search&gsrlimit=1&gsrsearch=" + android.net.Uri.encode(
                        name + " " + year + (series ? " TV series" : " film"))};
        for (int i = 0; i < tries.length; i++) {
            if (tries[i] == null) continue;
            try {
                org.json.JSONObject pages = new org.json.JSONObject(httpGet(tries[i])).getJSONObject("query").getJSONObject("pages");
                for (java.util.Iterator<String> k = pages.keys(); k.hasNext(); ) {
                    org.json.JSONObject pg = pages.getJSONObject(k.next());
                    // A search hit must actually be this title, or we'd show some actor's photo instead.
                    if (i > 0 && !norm(pg.optString("title").replaceAll("\\s*\\(.*\\)$", "")).equals(norm(name))) continue;
                    org.json.JSONObject th = pg.optJSONObject("thumbnail");
                    if (th != null && !th.optString("source").isEmpty()) return th.optString("source");
                }
            } catch (Exception ignored) { }
        }
        try {
            org.json.JSONArray r = new org.json.JSONObject(httpGet("https://itunes.apple.com/search?country=IN&limit=1&media="
                    + (series ? "tvShow" : "movie") + "&term=" + android.net.Uri.encode(name))).getJSONArray("results");
            if (r.length() > 0) {
                org.json.JSONObject hit = r.getJSONObject(0);
                String found = hit.optString("trackName", hit.optString("collectionName", hit.optString("artistName")));
                if (!norm(found).startsWith(norm(name)) && !norm(name).startsWith(norm(found))) return null;
                String a = hit.optString("artworkUrl100");
                if (!a.isEmpty()) return a.replace("100x100bb", "400x600bb");
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Picks the best IMDb suggestion: the exact id if Claude gave one, else an exact title match, scored by
     * matching year and film/series, newest first. Also fills in the IMDb id so Stremio can open the title.
     */
    private String imdbPoster(org.json.JSONObject it, String name, String year, boolean series) {
        try {
            String q = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", "%20");
            if (q.isEmpty()) return null;
            org.json.JSONArray d = new org.json.JSONObject(httpGet("https://v3.sg.media-imdb.com/suggestion/x/" + q + ".json"))
                    .optJSONArray("d");
            if (d == null) return null;
            String want = it.optString("imdb");
            org.json.JSONObject best = null;
            int bestScore = -1;
            for (int i = 0; i < d.length(); i++) {
                org.json.JSONObject x = d.getJSONObject(i);
                org.json.JSONObject img = x.optJSONObject("i");
                if (img == null || img.optString("imageUrl").isEmpty()) continue;
                int score;
                if (!want.isEmpty() && want.equals(x.optString("id"))) score = 1000;
                else if (norm(x.optString("l")).equals(norm(name))) {
                    boolean isSeries = x.optString("q").toLowerCase(Locale.ROOT).contains("series");
                    score = 10 + (year.equals(String.valueOf(x.optInt("y"))) ? 50 : 0) + (isSeries == series ? 20 : 0)
                            + Math.max(0, x.optInt("y") - 2000) / 2;
                } else continue;
                if (score > bestScore) { bestScore = score; best = x; }
            }
            if (best == null) return null;
            if (want.isEmpty() && best.optString("id").startsWith("tt")) it.put("imdb", best.optString("id"));
            // Amazon's image server resizes on the fly: ask for a 400px-wide poster instead of the full original.
            return best.getJSONObject("i").getString("imageUrl").replace("._V1_.", "._V1_SX400.");
        } catch (Exception e) {
            return null;
        }
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private java.net.HttpURLConnection open(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "tvhome/1.0 (Android TV home screen; personal use)");
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        return c;
    }

    private String httpGet(String url) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = open(url).getInputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toString("UTF-8");
    }

    private void wireRecs() {
        if (recRows.isEmpty()) return;
        int d = dockCards.size();
        List<View> first = recRows.get(0);
        // Down from anywhere on the dock opens Discover at its banner (or first row).
        int entry = featureCard != null ? featureCard.getId() : first.get(0).getId();
        for (int i = 0; i < d; i++) dockCards.get(i).setNextFocusDownId(entry);
        // Ask / Refresh sit in the header: Up from the banner (or first row), Down back into the page.
        int dockId = d > 0 ? dockCards.get(0).getId() : View.NO_ID;
        for (int i = 0; i < discoverPills.size(); i++) {
            View p = discoverPills.get(i);
            p.setNextFocusUpId(dockId == View.NO_ID ? p.getId() : dockId);
            p.setNextFocusDownId(entry);
            p.setNextFocusLeftId(discoverPills.get(Math.max(0, i - 1)).getId());
            p.setNextFocusRightId(discoverPills.get(Math.min(discoverPills.size() - 1, i + 1)).getId());
        }
        int headId = discoverPills.isEmpty() ? dockId : discoverPills.get(0).getId();
        if (featureCard != null) {
            featureCard.setNextFocusUpId(headId == View.NO_ID ? featureCard.getId() : headId);
            featureCard.setNextFocusDownId(first.get(0).getId());
            featureCard.setNextFocusLeftId(featureCard.getId());
            featureCard.setNextFocusRightId(featureCard.getId());
        }
        for (int r = 0; r < recRows.size(); r++) {
            List<View> row = recRows.get(r);
            List<View> up = r == 0 ? null : recRows.get(r - 1);
            List<View> dn = r == recRows.size() - 1 ? null : recRows.get(r + 1);
            for (int k = 0; k < row.size(); k++) {
                View c = row.get(k);
                c.setNextFocusLeftId(k == 0 ? c.getId() : row.get(k - 1).getId());
                c.setNextFocusRightId(k == row.size() - 1 ? c.getId() : row.get(k + 1).getId());
                c.setNextFocusUpId(up != null ? up.get(Math.min(k, up.size() - 1)).getId()
                        : featureCard != null ? featureCard.getId()
                        : headId != View.NO_ID ? headId : c.getId());
                c.setNextFocusDownId(dn != null ? dn.get(Math.min(k, dn.size() - 1)).getId() : c.getId());
            }
        }
    }

    // ---------------------------------------------------------------- Claude replies from the phone remote

    private LinearLayout claudeCard;
    private TextView claudeQ, claudeA;
    private int lastFeedId = -1, claudeToken;
    private volatile int remoteIdx;
    private final Runnable feedPoll = new Runnable() {
        @Override public void run() {
            pollFeed();
            if (claudeCard != null) claudeCard.postDelayed(this, 3000);
        }
    };

    private View buildClaudeCard() {
        claudeCard = new LinearLayout(this);
        claudeCard.setOrientation(LinearLayout.VERTICAL);
        claudeCard.setPadding(dp(22), dp(16), dp(22), dp(18));
        claudeCard.setBackground(glass(dp(22), GLASS_DARK));
        claudeCard.setElevation(dp(20));
        claudeCard.setVisibility(View.GONE);
        TextView tag = text(10, "sans-serif-medium", 0xFF8FD4FF);
        tag.setText("CLAUDE");
        tag.setLetterSpacing(0.22f);
        claudeQ = text(12, "sans-serif", 0x99FFFFFF);
        claudeQ.setMaxLines(2);
        claudeA = text(17, "sans-serif", Color.WHITE);
        claudeA.setMaxLines(9);
        claudeCard.addView(tag, llp(-2, -2, 0, 0, 0, dp(6)));
        claudeCard.addView(claudeQ, llp(-1, -2, 0, 0, 0, dp(8)));
        claudeCard.addView(claudeA, llp(-1, -2, 0, 0, 0, 0));
        return claudeCard;
    }

    private void pollFeed() {
        String[] bases = getResources().getStringArray(R.array.remote_urls);
        if (bases.length == 0) return;
        String base = bases[remoteIdx % bases.length];
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(base + "/api/feed").openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(3000);
                if (c.getResponseCode() != 200) return;
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                org.json.JSONObject f = new org.json.JSONObject(bo.toString("UTF-8"));
                int id = f.getInt("id");
                String q = f.optString("q"), a = f.optString("text");
                runOnUiThread(() -> {
                    if (lastFeedId == -1) { lastFeedId = id; return; } // ignore whatever was there at launch
                    if (id == lastFeedId) return;
                    lastFeedId = id;
                    showClaude(q, a);
                });
            } catch (Exception ignored) {
                remoteIdx++; // Mac asleep, remote not running, or on the other network: try the next address
            }
        }).start();
    }

    private void showClaude(String q, String a) {
        if (claudeCard == null) return;
        int tok = ++claudeToken;
        if (a.isEmpty()) { claudeCard.setVisibility(View.GONE); return; }
        claudeQ.setText(q.isEmpty() ? "" : "You: " + q);
        claudeQ.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        claudeA.setText(a);
        claudeCard.setAlpha(0f);
        claudeCard.setTranslationY(dp(24));
        claudeCard.setVisibility(View.VISIBLE);
        claudeCard.animate().alpha(1f).translationY(0).setInterpolator(SOFT).setDuration(450).start();
        claudeCard.postDelayed(() -> {
            if (tok != claudeToken) return;
            claudeCard.animate().alpha(0f).setDuration(500).withEndAction(() -> {
                if (tok == claudeToken) claudeCard.setVisibility(View.GONE);
            }).start();
        }, 30000);
    }

    // ---------------------------------------------------------------- app menu (long-press)

    private void showAppMenu(View anchor, App app) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setBackground(glass(dp(18), GLASS_DARK));
        PopupWindow pw = new PopupWindow(list, dp(220), -2, true);

        boolean system = false;
        try {
            android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(app.pkg, 0);
            system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (PackageManager.NameNotFoundException ignored) { }

        List<TextView> rows = new ArrayList<>();
        List<String> order = new ArrayList<>(dockOrder());
        boolean docked = order.contains(app.pkg);
        rows.add(inputRow(docked ? "Remove from dock" : "Add to dock", () -> {
            if (docked) order.remove(app.pkg);
            else if (dockCards.size() - 1 >= DOCK_MAX) { toast("The dock is full: remove an app first"); return; }
            else order.add(app.pkg);
            setDockOrder(order);
            toast(app.label + (docked ? " removed from the dock" : " added to the dock"));
            // The tile that was selected is gone after the rebuild: land on the dock instead.
            handler.post(() -> {
                if (!drawerOpen && !dockCards.isEmpty()) dockCards.get(docked ? 0 : dockCards.size() - 2).requestFocus();
            });
        }));
        rows.add(inputRow("Update (Play Store)", () -> {
            if (!launch(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + app.pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) toast("Play Store isn't available");
        }));
        if (!system) rows.add(inputRow("Uninstall", () -> launch(new Intent(Intent.ACTION_DELETE,
                android.net.Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        rows.add(inputRow(system ? "App info / Disable" : "App info", () -> launch(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + app.pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));

        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { if (anchor.isAttachedToWindow()) anchor.requestFocus(); });
        pw.showAsDropDown(anchor, 0, dp(8), Gravity.START);
        rows.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- self-update

    private static final String ACTION_INSTALL = "com.mihir.tvhome.INSTALL_RESULT";

    private final BroadcastReceiver installResult = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1);
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                toast("Update failed: " + i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    };

    private TextView recsStatus;
    private int statusToken;

    /** On-screen status under the clock; many TVs never show toasts. Clears itself after 15s. */
    private void toast(String m) {
        runOnUiThread(() -> {
            if (recsStatus == null) return;
            recsStatus.setText(m);
            recsStatus.setVisibility(View.VISIBLE);
            int tok = ++statusToken;
            recsStatus.postDelayed(() -> {
                if (tok == statusToken) recsStatus.setVisibility(View.GONE);
            }, 15000);
        });
    }

    // Google TV's launcher outranks any other Home app, so this one only works with it disabled. The Google TV
    // pill turns it back on and opens it (via the TV's own ADB, see LocalAdb); opening "Home" from Google TV's
    // apps row lands back here, and onResume turns it off again.
    private static final String GOOGLE_TV = "com.google.android.apps.tv.launcherx";

    private void openGoogleTv() {
        toast("Switching to Google TV… (first time: allow debugging on screen)");
        new Thread(() -> {
            try {
                new LocalAdb(getFilesDir()).shell("pm enable " + GOOGLE_TV
                        + " && am start -n " + GOOGLE_TV + "/.home.HomeActivity");
                prefs().edit().putBoolean("google_tv_on", true).apply();
            } catch (Exception e) {
                android.util.Log.e(TAG, "google tv switch failed", e);
                toast("Couldn't switch. Allow network debugging when the TV asks, then try again");
            }
        }).start();
    }

    private void hideGoogleTv() {
        new Thread(() -> {
            try {
                new LocalAdb(getFilesDir()).shell("pm disable-user --user 0 " + GOOGLE_TV);
                prefs().edit().putBoolean("google_tv_on", false).apply();
            } catch (Exception e) {
                android.util.Log.e(TAG, "google tv hide failed", e);
            }
        }).start();
    }

    /** Downloads the APK from the first update_urls entry (secrets.xml) that answers, then installs it. */
    private void selfUpdate() {
        String[] urls = getResources().getStringArray(R.array.update_urls);
        toast("Downloading update\u2026");
        new Thread(() -> {
            try {
                java.net.HttpURLConnection c = null;
                for (String url : urls) {
                    try {
                        c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                        c.setConnectTimeout(3000);
                        c.setReadTimeout(20000);
                        if (c.getResponseCode() == 200) break;
                    } catch (java.io.IOException e) { /* try the next address */ }
                    c = null;
                }
                if (c == null) throw new java.io.IOException("no update server answered");
                android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
                int id = pi.createSession(new android.content.pm.PackageInstaller.SessionParams(
                        android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL));
                try (android.content.pm.PackageInstaller.Session ses = pi.openSession(id)) {
                    try (java.io.InputStream in = c.getInputStream();
                         java.io.OutputStream out = ses.openWrite("tvhome", 0, -1)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        ses.fsync(out);
                    }
                    android.app.PendingIntent pend = android.app.PendingIntent.getBroadcast(this, id,
                            new Intent(ACTION_INSTALL).setPackage(getPackageName()),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                    ses.commit(pend.getIntentSender());
                }
            } catch (Exception e) {
                toast("Update failed: " + e.getMessage() + " (is the Mac server running?)");
            }
        }).start();
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 17 ? "GOOD AFTERNOON" : "GOOD EVENING");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        for (WorldClock w : worldClocks) w.update(now.getTimeInMillis(), is24);
        renderWeather();
        updateBedtime(now);
    }

    // ---------------------------------------------------------------- world clocks

    // name, time zone, latitude, longitude (for the small weather readout)
    private static final String[][] WORLD_CLOCKS = {
            {"Kochi", "Asia/Kolkata", "9.9312", "76.2673"},
            {"Sydney", "Australia/Sydney", "-33.8688", "151.2093"},
            {"Belfast", "Europe/London", "54.5973", "-5.9301"}};
    private final List<WorldClock> worldClocks = new ArrayList<>();

    /** A glass chip: small analog dial (light by day, dark at night), the time, and the day plus hours ahead. */
    private View worldClock(String name, String zone) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        liquid(chip, dp(14));
        chip.setPadding(dp(8), dp(6), dp(12), dp(6));
        WorldClock w = new WorldClock();
        w.zone = java.util.TimeZone.getTimeZone(zone);
        w.dial = new Dial(this);
        chip.addView(w.dial, llp(dp(26), dp(26), 0, 0, dp(9), 0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView city = text(8, "sans-serif-medium", 0xB3FFFFFF);
        city.setText(name.toUpperCase(Locale.getDefault()));
        city.setLetterSpacing(0.2f);
        w.time = text(13, "sans-serif-light", Color.WHITE);
        w.time.setIncludeFontPadding(false);
        w.sub = text(8, "sans-serif", 0x99FFFFFF);
        col.addView(city);
        col.addView(w.time);
        col.addView(w.sub);
        chip.addView(col);
        w.wx = text(12, "sans-serif-light", Color.WHITE);
        w.wx.setGravity(Gravity.CENTER);
        w.wx.setVisibility(View.GONE);
        chip.addView(w.wx, llp(-2, -2, dp(12), 0, 0, 0));
        worldClocks.add(w);
        return chip;
    }

    private static final class WorldClock {
        java.util.TimeZone zone;
        Dial dial;
        TextView time, sub, wx;

        void update(long nowMs, boolean is24) {
            Calendar c = Calendar.getInstance(zone);
            c.setTimeInMillis(nowMs);
            int h = c.get(Calendar.HOUR_OF_DAY), m = c.get(Calendar.MINUTE);
            SimpleDateFormat f = new SimpleDateFormat(is24 ? "H:mm" : "h:mm a", Locale.getDefault());
            f.setTimeZone(zone);
            time.setText(f.format(c.getTime()));
            int diffMin = (zone.getOffset(nowMs) - java.util.TimeZone.getDefault().getOffset(nowMs)) / 60000;
            String ahead = (diffMin >= 0 ? "+" : "−") + Math.abs(diffMin) / 60
                    + (Math.abs(diffMin) % 60 != 0 ? ":" + String.format(Locale.US, "%02d", Math.abs(diffMin) % 60) : "") + "h";
            SimpleDateFormat d = new SimpleDateFormat("EEE", Locale.getDefault());
            d.setTimeZone(zone);
            sub.setText(d.format(c.getTime()) + "  ·  " + ahead);
            dial.set(h, m, h >= 6 && h < 18);
        }
    }

    // ---------------------------------------------------------------- weather (Open-Meteo, no key needed)

    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000L;
    private View weatherCard;
    private TextView wxIcon, wxTemp, wxCond, wxRange, wxSunrise, wxSunset, tennisTitle, tennisVerdict;
    private SunBar sunBar;
    private RainBars rainBars;
    private org.json.JSONObject wxHome;

    private final Runnable weatherPoll = new Runnable() {
        @Override public void run() {
            fetchWeather();
            handler.postDelayed(this, WEATHER_EVERY_MS);
        }
    };

    /** Home city now, high/low, sunrise-to-sunset bar, and the tennis-window rain check. */
    private View buildWeatherCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        liquid(card, dp(20));
        card.setPadding(dp(15), dp(10), dp(15), dp(11));
        card.setVisibility(View.GONE); // until the first forecast arrives
        weatherCard = card;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        wxIcon = text(20, "sans-serif", Color.WHITE);
        top.addView(wxIcon, llp(-2, -2, 0, 0, dp(10), 0));
        wxTemp = text(27, "sans-serif-thin", Color.WHITE);
        wxTemp.setIncludeFontPadding(false);
        top.addView(wxTemp, llp(-2, -2, 0, 0, dp(14), 0));
        LinearLayout desc = new LinearLayout(this);
        desc.setOrientation(LinearLayout.VERTICAL);
        TextView city = text(8, "sans-serif-medium", 0x99FFFFFF);
        city.setText(getString(R.string.home_city).toUpperCase(Locale.getDefault()));
        city.setLetterSpacing(0.2f);
        wxCond = text(12, "sans-serif", Color.WHITE);
        wxCond.setSingleLine(true);
        wxRange = text(9, "sans-serif", 0x99FFFFFF);
        desc.addView(city);
        desc.addView(wxCond);
        desc.addView(wxRange);
        top.addView(desc);
        card.addView(top);

        LinearLayout sun = new LinearLayout(this);
        sun.setGravity(Gravity.CENTER_VERTICAL);
        wxSunrise = text(9, "sans-serif-medium", 0xCCFFFFFF);
        wxSunset = text(9, "sans-serif-medium", 0xCCFFFFFF);
        sunBar = new SunBar(this);
        LinearLayout.LayoutParams barLp = llp(0, dp(14), dp(10), 0, dp(10), 0);
        barLp.weight = 1;
        sun.addView(wxSunrise);
        sun.addView(sunBar, barLp);
        sun.addView(wxSunset);
        card.addView(sun, llp(-1, -2, 0, dp(8), 0, 0));

        View rule = new View(this);
        rule.setBackgroundColor(0x26FFFFFF);
        card.addView(rule, llp(-1, 1, 0, dp(8), 0, dp(6)));

        LinearLayout tennis = new LinearLayout(this);
        tennis.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tcol = new LinearLayout(this);
        tcol.setOrientation(LinearLayout.VERTICAL);
        tennisTitle = text(8, "sans-serif-medium", 0x99FFFFFF);
        tennisTitle.setLetterSpacing(0.2f);
        tennisVerdict = text(12, "sans-serif-medium", Color.WHITE);
        tcol.addView(tennisTitle);
        tcol.addView(tennisVerdict, llp(-2, -2, 0, dp(2), 0, 0));
        LinearLayout.LayoutParams tLp = llp(0, -2, 0, 0, 0, 0);
        tLp.weight = 1;
        tennis.addView(tcol, tLp);
        rainBars = new RainBars(this);
        tennis.addView(rainBars, llp(dp(100), dp(34), dp(8), 0, 0, 0));
        card.addView(tennis);
        return card;
    }

    private void fetchWeather() {
        String unit = "&temperature_unit=" + getString(R.string.temperature_unit);
        String home = "https://api.open-meteo.com/v1/forecast?latitude=" + getString(R.string.home_lat)
                + "&longitude=" + getString(R.string.home_lon)
                + "&current=temperature_2m,weather_code,is_day&hourly=precipitation_probability"
                + "&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=2" + unit;
        StringBuilder lat = new StringBuilder(), lon = new StringBuilder();
        for (String[] c : WORLD_CLOCKS) {
            lat.append(lat.length() > 0 ? "," : "").append(c[2]);
            lon.append(lon.length() > 0 ? "," : "").append(c[3]);
        }
        String world = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,weather_code,is_day&timezone=auto" + unit;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                String h = httpGet(home), w = httpGet(world);
                prefs().edit().putString("wx_home", h).putString("wx_world", w)
                        .putLong("wx_at", System.currentTimeMillis()).apply();
                runOnUiThread(() -> showWeather(h, w));
            } catch (Exception e) {
                android.util.Log.e(TAG, "weather failed", e);
            }
        }).start();
    }

    /** Shows the cached forecast now and schedules the next fetch for when it goes stale. */
    private void startWeather() {
        String h = prefs().getString("wx_home", null), w = prefs().getString("wx_world", null);
        if (h != null && w != null) showWeather(h, w);
        long age = System.currentTimeMillis() - prefs().getLong("wx_at", 0);
        handler.removeCallbacks(weatherPoll);
        handler.postDelayed(weatherPoll, Math.max(0, WEATHER_EVERY_MS - age));
    }

    // Celsius first, Fahrenheit small beside it. Open-Meteo says which unit it sent, so older cached
    // forecasts in °F still show correctly.
    private static int celsius(double v, String unit) {
        return (int) Math.round(unit.contains("F") ? (v - 32) * 5 / 9 : v);
    }

    private static int fahrenheit(int c) { return Math.round(c * 9 / 5f + 32); }

    private static CharSequence temps(double v, String unit, float smaller) {
        int c = celsius(v, unit);
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(c + "°");
        int start = s.length();
        s.append("  ").append(String.valueOf(fahrenheit(c))).append("°F");
        s.setSpan(new android.text.style.RelativeSizeSpan(smaller), start, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), start, s.length(), 0);
        return s;
    }

    private void showWeather(String homeJson, String worldJson) {
        try {
            wxHome = new org.json.JSONObject(homeJson);
            org.json.JSONArray world = new org.json.JSONArray(worldJson);
            for (int i = 0; i < world.length() && i < worldClocks.size(); i++) {
                org.json.JSONObject cur = world.getJSONObject(i).getJSONObject("current");
                TextView wx = worldClocks.get(i).wx;
                String unit = world.getJSONObject(i).optJSONObject("current_units") == null ? "°F"
                        : world.getJSONObject(i).getJSONObject("current_units").optString("temperature_2m", "°F");
                android.text.SpannableStringBuilder t = new android.text.SpannableStringBuilder(
                        wmoIcon(cur.getInt("weather_code"), cur.getInt("is_day") == 1) + " ");
                t.append(temps(cur.getDouble("temperature_2m"), unit, 0.62f));
                wx.setText(t);
                wx.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "weather parse failed", e);
        }
        renderWeather();
    }

    /** Called on fetch and every minute (TIME_TICK) so the sun bar and tennis window stay current. */
    private void renderWeather() {
        if (wxHome == null || weatherCard == null) return;
        try {
            org.json.JSONObject cur = wxHome.getJSONObject("current"), daily = wxHome.getJSONObject("daily");
            int offset = wxHome.getInt("utc_offset_seconds");
            int code = cur.getInt("weather_code");
            wxIcon.setText(wmoIcon(code, cur.getInt("is_day") == 1));
            org.json.JSONObject units = wxHome.optJSONObject("current_units");
            String unit = units == null ? "°F" : units.optString("temperature_2m", "°F");
            wxTemp.setText(temps(cur.getDouble("temperature_2m"), unit, 0.42f));
            wxCond.setText(wmoText(code));
            double hi = daily.getJSONArray("temperature_2m_max").getDouble(0), lo = daily.getJSONArray("temperature_2m_min").getDouble(0);
            int hiC = celsius(hi, unit), loC = celsius(lo, unit);
            wxRange.setText("H " + hiC + "°   L " + loC + "°    " + fahrenheit(hiC) + " / " + fahrenheit(loC) + "°F");

            long now = System.currentTimeMillis();
            long rise = localToEpoch(daily.getJSONArray("sunrise").getString(0), offset);
            long set = localToEpoch(daily.getJSONArray("sunset").getString(0), offset);
            wxSunrise.setText("↑ " + clockText(rise));
            wxSunset.setText(clockText(set) + " ↓");
            sunBar.set(now < rise || now > set ? -1f : (float) (now - rise) / (set - rise));

            // Tennis window: tonight until it's over, then tomorrow evening.
            int start = getResources().getInteger(R.integer.tennis_start_hour);
            int end = getResources().getInteger(R.integer.tennis_end_hour);
            Calendar local = Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT"));
            local.setTimeInMillis(now + offset * 1000L);
            int dayIdx = local.get(Calendar.HOUR_OF_DAY) < end ? 0 : 1;
            String date = daily.getJSONArray("time").getString(dayIdx);
            org.json.JSONArray times = wxHome.getJSONObject("hourly").getJSONArray("time");
            org.json.JSONArray probs = wxHome.getJSONObject("hourly").getJSONArray("precipitation_probability");
            int[] p = new int[end - start];
            for (int i = 0; i < times.length(); i++) {
                String t = times.getString(i);
                if (!t.startsWith(date)) continue;
                int hr = Integer.parseInt(t.substring(11, 13));
                if (hr >= start && hr < end) p[hr - start] = probs.optInt(i, 0);
            }
            int worst = 0, worstHr = start;
            for (int i = 0; i < p.length; i++) if (p[i] > worst) { worst = p[i]; worstHr = start + i; }
            tennisTitle.setText("🎾  " + (dayIdx == 0 ? "TONIGHT" : "TOMORROW") + "  "
                    + hourText(start) + "–" + hourText(end));
            if (worst < 20) {
                tennisVerdict.setText("Dry — game on");
                tennisVerdict.setTextColor(0xFF7CE38B);
            } else if (worst < 50) {
                tennisVerdict.setText("Maybe showers · " + worst + "% at " + hourText(worstHr));
                tennisVerdict.setTextColor(0xFFFFC857);
            } else {
                tennisVerdict.setText("Rain likely · " + worst + "% at " + hourText(worstHr));
                tennisVerdict.setTextColor(0xFFFF7A6E);
            }
            rainBars.set(p, start);
            weatherCard.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            android.util.Log.e(TAG, "weather render failed", e);
        }
    }

    /** Open-Meteo gives local wall-clock times ("2026-11-08T06:39") plus the UTC offset. */
    private static long localToEpoch(String s, int offsetSec) throws java.text.ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.parse(s).getTime() - offsetSec * 1000L;
    }

    private String clockText(long ms) {
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        return new SimpleDateFormat(is24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(new java.util.Date(ms));
    }

    private static String hourText(int h) {
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 || h == 24 ? " AM" : " PM");
    }

    private static String wmoIcon(int code, boolean day) {
        if (code <= 1) return day ? "☀️" : "🌙";
        if (code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 57) return "🌦️";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "🌧️";
    }

    private static String wmoText(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return code >= 65 ? "Heavy rain" : "Rain";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    /** Sunrise-to-sunset track with a sun marking where we are in the day; just the track at night. */
    private static final class SunBar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float f = -1f;

        SunBar(Context c) { super(c); p.setStrokeCap(Paint.Cap.ROUND); }

        void set(float f) {
            if (Math.abs(f - this.f) < 0.002f) return;
            this.f = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), w = getWidth(), cy = h / 2f, r = h * 0.32f, x0 = r, x1 = w - r;
            p.setStrokeWidth(h * 0.14f);
            p.setColor(0x40FFFFFF);
            cv.drawLine(x0, cy, x1, cy, p);
            if (f < 0) return;
            float x = x0 + (x1 - x0) * f;
            p.setColor(0xFFFFC857);
            cv.drawLine(x0, cy, x, cy, p);
            p.setColor(0x55FFC857);
            cv.drawCircle(x, cy, r * 1.5f, p);
            p.setColor(0xFFFFD87A);
            cv.drawCircle(x, cy, r, p);
        }
    }

    /** One bar per hour of the tennis window: height is chance of rain, colour goes green → amber → red. */
    private static final class RainBars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int[] probs = new int[0];
        private int start;

        RainBars(Context c) { super(c); p.setTextAlign(Paint.Align.CENTER); }

        void set(int[] probs, int start) {
            if (Arrays.equals(probs, this.probs) && start == this.start) return;
            this.probs = probs;
            this.start = start;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            int n = probs.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight(), slot = w / n, bw = slot * 0.46f;
            float label = h * 0.3f, top = 0, bottom = h - label - h * 0.06f, rad = bw / 2f;
            p.setTextSize(label * 0.9f);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                p.setColor(0x26FFFFFF);
                cv.drawRoundRect(cx - bw / 2, top, cx + bw / 2, bottom, rad, rad, p);
                int v = probs[i];
                p.setColor(v < 20 ? 0xFF7CE38B : v < 50 ? 0xFFFFC857 : 0xFFFF7A6E);
                float bh = Math.max(bw, (bottom - top) * v / 100f);
                cv.drawRoundRect(cx - bw / 2, bottom - bh, cx + bw / 2, bottom, rad, rad, p);
                p.setColor(0x99FFFFFF);
                int hr = start + i;
                cv.drawText(String.valueOf(hr % 12 == 0 ? 12 : hr % 12), cx, h - h * 0.02f, p);
            }
        }
    }

    // ---------------------------------------------------------------- team ticker (ESPN's public scoreboard feed)

    private static final long TEAM_EVERY_MS = 20 * 60 * 1000L, TEAM_LIVE_MS = 45 * 1000L;
    private View teamCard;
    private TextView teamBadge, teamLine1, teamLine2, teamRecap;
    private CharSequence teamInfo = "";
    private String liveGameId;
    private boolean teamLive;
    private long nextTipoff;

    // ---------------------------------------------------------------- continue watching (peek above the dock)

    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final long STREMIO_EVERY_MS = 15 * 60 * 1000L;

    /** One thing to resume or watch: shown as a small card in the peek strip. */
    private static final class Watch {
        String title, sub, image;
        float progress = -1;
        boolean wide;
        Intent open;
    }

    private final Map<String, List<Watch>> watchByPkg = new HashMap<>();
    private final Map<String, String> watchLabel = new HashMap<>();
    private LinearLayout peek, peekRow;
    private TextView peekLabel;
    private View peekAnchor;
    private final List<View> peekCards = new ArrayList<>();
    private final android.util.LruCache<String, Bitmap> thumbMem = new android.util.LruCache<>(40);
    private String peekPending;
    private View peekPendingAnchor;
    private final Runnable showPeekNow = () -> { if (peekPending != null) showPeek(peekPending, peekPendingAnchor); };

    private final Runnable stremioPoll = new Runnable() {
        @Override public void run() {
            fetchStremio();
            handler.postDelayed(this, STREMIO_EVERY_MS);
        }
    };

    private View buildPeek() {
        peek = new LinearLayout(this);
        peek.setOrientation(LinearLayout.VERTICAL);
        peek.setGravity(Gravity.CENTER_HORIZONTAL);
        peek.setClipChildren(false);
        peek.setClipToPadding(false);
        peek.setVisibility(View.GONE);
        peekLabel = text(8, "sans-serif-medium", 0xB3FFFFFF);
        peekLabel.setLetterSpacing(0.22f);
        peekLabel.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        peek.addView(peekLabel, llp(-2, -2, 0, 0, 0, dp(6)));
        peekRow = new LinearLayout(this);
        peekRow.setClipChildren(false);
        peekRow.setClipToPadding(false);
        peek.addView(peekRow, new LinearLayout.LayoutParams(-2, -2)); // its own width, not the label's
        // Hide the strip as soon as focus goes anywhere other than the strip or the app it belongs to.
        peek.getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            if (now != peekPendingAnchor) { handler.removeCallbacks(showPeekNow); peekPending = null; }
            if (peek.getVisibility() == View.VISIBLE && now != peekAnchor && !peekCards.contains(now)) hidePeek();
        });
        return peek;
    }

    /** A dock app got focus: if it has something to resume, open the strip after a short pause. */
    private void onDockFocus(String pkg, View card) {
        handler.removeCallbacks(showPeekNow);
        List<Watch> items = watchByPkg.get(pkg);
        if (items == null || items.isEmpty()) return;
        peekPending = pkg;
        peekPendingAnchor = card;
        handler.postDelayed(showPeekNow, 450);
    }

    private void showPeek(String pkg, View anchor) {
        List<Watch> items = watchByPkg.get(pkg);
        if (items == null || items.isEmpty() || !anchor.hasFocus() && !anchor.isFocused()) return;
        peekAnchor = anchor;
        peekRow.removeAllViews();
        peekCards.clear();
        peekLabel.setText(watchLabel.get(pkg));
        for (int i = 0; i < Math.min(3, items.size()); i++) {
            View c = watchCard(items.get(i));
            peekRow.addView(c, llp(dp(232), dp(62), i == 0 ? 0 : dp(7), 0, i == 2 ? 0 : dp(7), 0));
            peekCards.add(c);
        }
        int topId = pills.isEmpty() ? anchor.getId() : pills.get(0).getId();
        for (int i = 0; i < peekCards.size(); i++) {
            View c = peekCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusDownId(anchor.getId());
            c.setNextFocusLeftId(peekCards.get(Math.max(0, i - 1)).getId());
            c.setNextFocusRightId(peekCards.get(Math.min(peekCards.size() - 1, i + 1)).getId());
        }
        anchor.setNextFocusUpId(peekCards.get(0).getId());
        title.setVisibility(View.GONE);
        peek.setVisibility(View.VISIBLE);
        peek.setAlpha(0f);
        peek.setTranslationY(dp(10));
        peek.animate().alpha(1f).translationY(0).setInterpolator(SOFT).setDuration(280).start();
    }

    private void hidePeek() {
        if (peekAnchor != null && !pills.isEmpty()) peekAnchor.setNextFocusUpId(pills.get(0).getId());
        peekAnchor = null;
        peek.animate().cancel();
        peek.setVisibility(View.GONE);
        peekCards.clear();
        title.setVisibility(View.VISIBLE);
    }

    private View watchCard(Watch w) {
        LinearLayout card = new LinearLayout(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(6), dp(6), dp(10), dp(6));
        LiquidGlass bg = liquid(card, dp(12));
        FrameLayout pic = new FrameLayout(this);
        pic.setClipToOutline(true);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(7)); }
        });
        pic.setBackgroundColor(0xFF22242A);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        if (w.progress >= 0) {
            View track = new View(this);
            track.setBackgroundColor(0x66000000);
            pic.addView(track, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
            View bar = new View(this);
            bar.setBackgroundColor(0xFFE50914);
            pic.addView(bar, new FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM));
            pic.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int want = Math.round((r - l) * Math.min(1f, w.progress));
                if (bar.getLayoutParams().width != want) { bar.getLayoutParams().width = want; bar.requestLayout(); }
            });
        }
        card.addView(pic, llp(w.wide ? dp(88) : dp(34), dp(50), 0, 0, dp(9), 0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(10, "sans-serif-medium", Color.WHITE);
        t.setText(w.title);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = text(8, "sans-serif", 0x99FFFFFF);
        s.setText(w.sub);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        col.addView(s, llp(-2, -2, 0, dp(2), 0, 0));
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        loadThumb(img, w.image);
        card.setOnClickListener(v -> { if (!launch(w.open) && peekAnchor != null) peekAnchor.performClick(); });
        card.setOnFocusChangeListener((v, has) -> {
            bg.setLit(has);
            t.setTextColor(has ? 0xFF111111 : Color.WHITE);
            s.setTextColor(has ? 0x99111111 : 0x99FFFFFF);
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f)
                    .setInterpolator(has ? SPRING : SOFT).setDuration(has ? 300 : 180).withLayer().start();
        });
        return card;
    }

    private void loadThumb(ImageView iv, String url) {
        if (url == null || url.isEmpty()) return;
        iv.setTag(url);
        Bitmap mem = thumbMem.get(url);
        if (mem != null) { iv.setImageBitmap(mem); return; }
        posterPool.execute(() -> {
            try (java.io.InputStream in = open(url).getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = 2;
                Bitmap bm = BitmapFactory.decodeStream(in, null, o);
                if (bm == null) return;
                thumbMem.put(url, bm);
                runOnUiThread(() -> { if (url.equals(iv.getTag())) iv.setImageBitmap(bm); });
            } catch (Exception ignored) { }
        });
    }

    /** YouTube: newest uploads from your subscriptions, from the Personal feed (tools/personal). */
    private void loadYoutubeWatch() {
        List<Watch> out = new ArrayList<>();
        try {
            org.json.JSONArray vids = new org.json.JSONObject(prefs().getString("personal_json", "{}")).optJSONArray("youtube");
            for (int i = 0; vids != null && i < vids.length(); i++) {
                org.json.JSONObject v = vids.getJSONObject(i);
                Watch w = new Watch();
                w.title = v.optString("title");
                w.sub = v.optString("channel") + " · " + android.text.format.DateUtils.getRelativeTimeSpanString(
                        v.optLong("at"), System.currentTimeMillis(), android.text.format.DateUtils.HOUR_IN_MILLIS);
                w.image = "https://i.ytimg.com/vi/" + v.optString("id") + "/mqdefault.jpg";
                w.wide = true;
                w.open = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + v.optString("id")))
                        .setPackage(YOUTUBE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                out.add(w);
            }
        } catch (org.json.JSONException ignored) { }
        watchByPkg.put(YOUTUBE, out);
        watchLabel.put(YOUTUBE, "NEW FROM YOUR SUBSCRIPTIONS");
    }

    /** Stremio: your library with playback progress, from Stremio's own API (needs stremio_auth_key in secrets.xml). */
    private void fetchStremio() {
        String key = getString(R.string.stremio_auth_key).trim();
        if (key.isEmpty()) return;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                org.json.JSONObject body = new org.json.JSONObject().put("authKey", key)
                        .put("collection", "libraryItem").put("ids", new org.json.JSONArray()).put("all", true);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.strem.io/api/datastoreGet").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(10000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                String json = bo.toString("UTF-8");
                prefs().edit().putString("stremio_json", json).apply();
                runOnUiThread(() -> showStremio(json));
            } catch (Exception e) {
                android.util.Log.e(TAG, "stremio failed: " + e.getMessage());
            }
        }).start();
    }

    private void showStremio(String json) {
        List<org.json.JSONObject> items = new ArrayList<>();
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            if (o.has("error")) { android.util.Log.e(TAG, "stremio: " + o.optJSONObject("error")); return; }
            org.json.JSONArray all = o.getJSONArray("result");
            for (int i = 0; i < all.length(); i++) {
                org.json.JSONObject it = all.getJSONObject(i);
                org.json.JSONObject st = it.optJSONObject("state");
                if (it.optBoolean("removed") || it.optBoolean("temp") && st == null || st == null) continue;
                long off = st.optLong("timeOffset"), dur = st.optLong("duration");
                boolean started = off > 60_000 && (dur == 0 || off < dur * 0.95);
                boolean nextEpisode = "series".equals(it.optString("type")) && !st.optString("video_id").isEmpty();
                if (started || nextEpisode) items.add(it);
            }
        } catch (org.json.JSONException e) {
            android.util.Log.e(TAG, "stremio parse failed", e);
            return;
        }
        Collections.sort(items, (a, b) -> b.optJSONObject("state").optString("lastWatched")
                .compareTo(a.optJSONObject("state").optString("lastWatched")));
        List<Watch> out = new ArrayList<>();
        for (org.json.JSONObject it : items) {
            if (out.size() >= 8) break;
            org.json.JSONObject st = it.optJSONObject("state");
            Watch w = new Watch();
            w.title = it.optString("name");
            int season = st.optInt("season"), ep = st.optInt("episode");
            // Stremio usually leaves season/episode at 0 and keeps them in the video id: tt13623632:1:8
            String[] vid = st.optString("video_id").split(":");
            if (season == 0 && vid.length == 3) {
                try { season = Integer.parseInt(vid[1]); ep = Integer.parseInt(vid[2]); } catch (NumberFormatException ignored) { }
            }
            boolean series = "series".equals(it.optString("type"));
            w.sub = series ? (season > 0 ? "S" + season + " · E" + ep : "Series") : "Movie";
            if (st.optLong("duration") > 0) w.sub += "  ·  " + Math.round(100f * st.optLong("timeOffset") / st.optLong("duration")) + "%";
            long off = st.optLong("timeOffset"), dur = st.optLong("duration");
            if (dur > 0) w.progress = off / (float) dur;
            w.image = it.optString("poster");
            String id = it.optString("_id"), video = st.optString("video_id");
            w.open = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("stremio:///detail/" + it.optString("type") + "/"
                    + id + (video.isEmpty() ? "" : "/" + video))).setPackage(STREMIO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            out.add(w);
        }
        watchByPkg.put(STREMIO, out);
        watchLabel.put(STREMIO, "CONTINUE WATCHING");
    }

    private void startWatchFeeds() {
        loadYoutubeWatch();
        String cached = prefs().getString("stremio_json", null);
        if (cached != null) showStremio(cached);
        handler.removeCallbacks(stremioPoll);
        handler.post(stremioPoll);
    }

    // ---------------------------------------------------------------- banner (live game alert)

    private TextView banner;

    private View buildBanner() {
        banner = text(12, "sans-serif-medium", Color.WHITE);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), 0, dp(18), 0);
        banner.setSingleLine(true);
        liquid(banner, dp(17));
        banner.setAlpha(0f);
        banner.setVisibility(View.GONE);
        return banner;
    }

    private void showBanner(CharSequence msg, long ms) {
        banner.animate().cancel();
        banner.setText(msg);
        banner.setVisibility(View.VISIBLE);
        banner.setTranslationY(-dp(20));
        banner.animate().alpha(1f).translationY(0).setInterpolator(SPRING).setDuration(500).withEndAction(() ->
                banner.animate().alpha(0f).translationY(-dp(20)).setStartDelay(ms).setDuration(400)
                        .withEndAction(() -> banner.setVisibility(View.GONE)).start()).start();
    }

    /** Called from showTeam: the first time a game shows as live, say so (once per game). */
    private void maybeAlertLive(org.json.JSONObject live, String us) {
        try {
            String id = live.getString("id");
            if (id.equals(prefs().getString("alerted_game", ""))) return;
            prefs().edit().putString("alerted_game", id).apply();
            showBanner("🏀  " + getString(R.string.team_badge) + " game is live   " + scoreLine(live, us)
                    + "   " + status(live), 15000);
        } catch (org.json.JSONException ignored) { }
    }

    // ---------------------------------------------------------------- morning briefing

    private FrameLayout briefing;
    private boolean briefingShowing;
    private final Runnable maybeBriefing = this::maybeShowBriefing;

    private View buildBriefing() {
        briefing = new FrameLayout(this);
        briefing.setBackgroundColor(0x8C000000);
        briefing.setVisibility(View.GONE);
        return briefing;
    }

    /** First time the home screen shows after 4 AM each day, until noon. */
    private void maybeShowBriefing() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
        if (h < 4 || h >= 12 || today.equals(prefs().getString("briefing_day", "")) || ambient || drawerOpen) return;
        prefs().edit().putString("briefing_day", today).apply();
        showBriefing();
    }

    private void showBriefing() {
        briefing.removeAllViews();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(30), dp(24), dp(30), dp(20));
        liquid(card, dp(26));
        briefing.addView(card, new FrameLayout.LayoutParams(dp(600), -2, Gravity.CENTER));
        TextView hi = text(24, "sans-serif-light", Color.WHITE);
        hi.setText("Good morning");
        card.addView(hi);
        TextView day = text(9, "sans-serif-medium", 0x99FFFFFF);
        day.setLetterSpacing(0.22f);
        day.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(new java.util.Date()).toUpperCase(Locale.getDefault()));
        card.addView(day, llp(-2, -2, 0, dp(2), 0, dp(14)));

        List<String[]> lines = new ArrayList<>();
        if (wxTemp != null && wxTemp.getText().length() > 0) {
            lines.add(new String[]{wxIcon.getText().toString(), wxTemp.getText() + " " + wxCond.getText() + "  ·  " + wxRange.getText()
                    + "\n" + tennisTitle.getText().toString().toLowerCase(Locale.getDefault()) + ": " + tennisVerdict.getText()});
        }
        lines.addAll(PersonalPage.briefingLines(prefs().getString("personal_json", null)));
        if (teamCard.getVisibility() == View.VISIBLE) lines.add(new String[]{"🏀", teamLine1.getText() + "\n"
                + (teamRecap.getVisibility() == View.VISIBLE ? teamRecap.getText() : teamInfo)});
        for (String[] l : lines) {
            LinearLayout row = new LinearLayout(this);
            TextView icon = text(14, "sans-serif", Color.WHITE);
            icon.setText(l[0]);
            row.addView(icon, llp(dp(30), -2, 0, 0, dp(8), 0));
            TextView t = text(12, "sans-serif", 0xE6FFFFFF);
            t.setText(l[1]);
            t.setLineSpacing(0, 1.12f);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(row, llp(-1, -2, 0, 0, 0, dp(10)));
        }
        TextView hint = text(8, "sans-serif-medium", 0x66FFFFFF);
        hint.setLetterSpacing(0.2f);
        hint.setText("PRESS ANY BUTTON");
        card.addView(hint, llp(-2, -2, 0, dp(6), 0, 0));
        briefingShowing = true;
        briefing.setVisibility(View.VISIBLE);
        briefing.setAlpha(0f);
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        briefing.animate().alpha(1f).setDuration(400).start();
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(SOFT).setDuration(500).start();
        handler.postDelayed(this::hideBriefing, 5 * 60 * 1000L);
    }

    private void hideBriefing() {
        if (!briefingShowing) return;
        briefingShowing = false;
        briefing.animate().cancel();
        briefing.animate().alpha(0f).setDuration(300).withEndAction(() -> briefing.setVisibility(View.GONE)).start();
        resetIdle();
    }

    // ---------------------------------------------------------------- bedtime: dim, big clock, sleep timer

    private View nightDim;
    private LinearLayout nightClock;
    private TextView nightTime, nightDate;
    private boolean night;
    private final Runnable sleepNow = this::goToSleep;

    private View buildNightDim() {
        nightDim = new View(this);
        nightDim.setBackgroundColor(Color.BLACK);
        nightDim.setAlpha(0f);
        nightDim.setVisibility(View.GONE);
        return nightDim;
    }

    private View buildNightClock() {
        nightClock = new LinearLayout(this);
        nightClock.setOrientation(LinearLayout.VERTICAL);
        nightClock.setGravity(Gravity.CENTER_HORIZONTAL);
        nightClock.setAlpha(0f);
        nightClock.setVisibility(View.GONE);
        nightTime = text(110, "sans-serif-thin", 0xD9FFFFFF);
        nightTime.setIncludeFontPadding(false);
        nightTime.setSingleLine(true);
        nightDate = text(11, "sans-serif-medium", 0x80FFFFFF);
        nightDate.setLetterSpacing(0.3f);
        for (TextView t : new TextView[]{nightTime, nightDate}) t.setShadowLayer(dp(16), 0, 0, 0x99000000);
        nightTime.setGravity(Gravity.CENTER);
        nightClock.addView(nightTime, new LinearLayout.LayoutParams(-1, -2)); // full width: never clipped
        nightClock.addView(nightDate, llp(-2, -2, 0, dp(6), 0, 0));
        return nightClock;
    }

    /** Every minute: between bedtime and wake-up the screen dims, and idle mode becomes a big quiet clock. */
    private void updateBedtime(Calendar now) {
        int h = now.get(Calendar.HOUR_OF_DAY);
        int start = getResources().getInteger(R.integer.bedtime_hour), end = getResources().getInteger(R.integer.wake_hour);
        boolean n = start != end && (start > end ? h >= start || h < end : h >= start && h < end);
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        nightTime.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        nightDate.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
        if (n == night) return;
        night = n;
        applyNight();
    }

    private void applyNight() {
        float dim = !night ? 0f : ambient ? 0.6f : 0.3f;
        nightDim.animate().cancel();
        if (dim > 0) nightDim.setVisibility(View.VISIBLE);
        nightDim.animate().alpha(dim).setDuration(1500)
                .withEndAction(() -> { if (dim == 0) nightDim.setVisibility(View.GONE); }).start();
        boolean bigClock = night && ambient;
        nightClock.animate().cancel();
        if (bigClock) { nightClock.setVisibility(View.VISIBLE); nightClock.requestLayout(); }
        nightClock.animate().alpha(bigClock ? 1f : 0f).setDuration(1500)
                .withEndAction(() -> { if (!bigClock) nightClock.setVisibility(View.GONE); }).start();
        handler.removeCallbacks(sleepNow);
        int mins = getResources().getInteger(R.integer.sleep_after_minutes);
        if (bigClock && mins > 0) handler.postDelayed(sleepNow, mins * 60_000L);
    }

    /** Puts the TV in standby, through the TV's own ADB (same as the Google TV button). */
    private void goToSleep() {
        if (!ambient || !night) return;
        new Thread(() -> {
            try {
                new LocalAdb(getFilesDir()).shell("input keyevent KEYCODE_SLEEP");
            } catch (Exception e) {
                android.util.Log.e(TAG, "sleep failed: " + e.getMessage());
            }
        }).start();
    }

    // ---------------------------------------------------------------- did you know (Claude, a day's worth at a time)

    private static final long FACT_EVERY_MS = 5 * 60 * 1000L, FACTS_MAX_AGE_MS = 24L * 3600 * 1000;
    private TextView factText;
    private boolean factsLoading;
    private final Runnable nextFact = this::showNextFact;

    private View buildFactLine() {
        factText = text(9, "sans-serif", 0xB3FFFFFF);
        factText.setMaxLines(2);
        factText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        factText.setLineSpacing(0, 1.1f);
        factText.setShadowLayer(dp(8), 0, dp(1), 0xB3000000);
        factText.setVisibility(View.GONE); // until the first batch arrives
        return factText;
    }

    private void startFacts() {
        showNextFact();
        boolean stale = System.currentTimeMillis() - prefs().getLong("facts_at", 0) > FACTS_MAX_AGE_MS;
        if (stale && hasApiKey() && !factsLoading) fetchFacts();
    }

    /** Next fact from today's batch (in order, so none repeats until the batch runs out), every 5 minutes. */
    private void showNextFact() {
        handler.removeCallbacks(nextFact);
        handler.postDelayed(nextFact, FACT_EVERY_MS);
        try {
            org.json.JSONArray facts = new org.json.JSONArray(prefs().getString("facts", "[]"));
            if (facts.length() == 0) return;
            int i = prefs().getInt("fact_index", 0) % facts.length();
            prefs().edit().putInt("fact_index", i + 1).apply();
            org.json.JSONObject f = facts.getJSONObject(i);
            // Every fourth turn, today's "on this day" from your own mail (Personal feed), when there is one.
            String otd = new org.json.JSONObject(prefs().getString("personal_json", "{}")).optString("onThisDay");
            if (i % 4 == 3 && !otd.isEmpty()) f = new org.json.JSONObject().put("topic", "On this day").put("text", otd);
            CharSequence line = android.text.Html.fromHtml("<b>" + android.text.TextUtils.htmlEncode(
                    f.optString("topic", "Did you know").toUpperCase(Locale.getDefault())) + "</b>&nbsp;&nbsp;"
                    + android.text.TextUtils.htmlEncode(f.optString("text")), android.text.Html.FROM_HTML_MODE_LEGACY);
            if (factText.getVisibility() != View.VISIBLE) {
                factText.setText(line);
                factText.setVisibility(View.VISIBLE);
                return;
            }
            factText.animate().cancel();
            factText.animate().alpha(0f).setDuration(400).withEndAction(() -> {
                factText.setText(line);
                factText.animate().alpha(1f).setDuration(600).start();
            }).start();
        } catch (org.json.JSONException ignored) { }
    }

    /** One small Claude call a day: ~80 short, true, surprising facts across topics you care about. */
    private void fetchFacts() {
        factsLoading = true;
        String prompt = "Write 80 short, surprising, true facts for a TV home screen ticker. Mix topics: space, "
                + "science, nature, history, India and Kerala, Australia, food, language, tennis, the NBA and the "
                + "Golden State Warriors, technology, the human body, geography. Each under 110 characters, plain "
                + "English, no fact that is commonly known, nothing you are unsure of, no repeats. Reply with ONLY "
                + "JSON, no markdown: {\"facts\":[{\"topic\":\"one or two words, e.g. Space\",\"text\":\"...\"}]}";
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                org.json.JSONObject body = new org.json.JSONObject()
                        .put("model", getString(R.string.claude_model))
                        .put("max_tokens", 8000)
                        .put("output_config", new org.json.JSONObject().put("effort", "low"))
                        .put("messages", new org.json.JSONArray().put(new org.json.JSONObject()
                                .put("role", "user").put("content", prompt)));
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.anthropic.com/v1/messages").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(10000);
                c.setReadTimeout(120000);
                c.setDoOutput(true);
                c.setRequestProperty("x-api-key", getString(R.string.claude_api_key).trim());
                c.setRequestProperty("anthropic-version", "2023-06-01");
                c.setRequestProperty("content-type", "application/json");
                try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                }
                org.json.JSONArray content = new org.json.JSONObject(bo.toString("UTF-8")).getJSONArray("content");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    if ("text".equals(content.getJSONObject(i).optString("type"))) sb.append(content.getJSONObject(i).optString("text"));
                }
                String text = sb.toString();
                int a = text.indexOf('{'), z = text.lastIndexOf('}');
                org.json.JSONArray facts = new org.json.JSONObject(text.substring(a, z + 1)).getJSONArray("facts");
                if (facts.length() == 0) throw new java.io.IOException("no facts");
                prefs().edit().putString("facts", facts.toString()).putInt("fact_index", 0)
                        .putLong("facts_at", System.currentTimeMillis()).apply();
                runOnUiThread(() -> { if (factText.getVisibility() != View.VISIBLE) showNextFact(); });
            } catch (Exception e) {
                android.util.Log.e(TAG, "facts failed: " + e.getMessage());
            } finally {
                factsLoading = false;
            }
        }).start();
    }

    private final Runnable teamPoll = new Runnable() {
        @Override public void run() {
            fetchTeam();
            // Check again a minute after tip-off rather than up to 20 minutes late, so the live alert is prompt.
            long untilTip = nextTipoff - System.currentTimeMillis() + 60_000;
            long delay = teamLive ? TEAM_LIVE_MS : untilTip > 0 ? Math.min(TEAM_EVERY_MS, untilTip) : TEAM_EVERY_MS;
            handler.postDelayed(this, Math.max(30_000, delay));
        }
    };

    private View buildTeamCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LiquidGlass bg = liquid(card, dp(18));
        card.setPadding(dp(10), dp(8), dp(14), dp(8));
        card.setVisibility(View.GONE);
        card.setId(View.generateViewId());
        // Only selectable during a game: OK hides (or brings back) the score ticker over other apps for that game.
        card.setClickable(true);
        card.setOnClickListener(v -> toggleTicker());
        card.setOnFocusChangeListener((v, has) -> {
            bg.setLit(has);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f)
                    .setInterpolator(has ? SPRING : SOFT).setDuration(has ? 360 : 220).withLayer().start();
            if (has) { lastFocusedId = v.getId(); scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
            showTeamHint();
        });
        teamCard = card;
        teamBadge = text(10, "sans-serif-black", getColor(R.color.team_primary));
        teamBadge.setText(getString(R.string.team_badge));
        teamBadge.setGravity(Gravity.CENTER);
        GradientDrawable b = new GradientDrawable();
        b.setColor(getColor(R.color.team_secondary));
        b.setCornerRadius(dp(10));
        teamBadge.setBackground(b);
        card.addView(teamBadge, llp(dp(38), dp(28), 0, 0, dp(10), 0));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        teamLine1 = text(12, "sans-serif-medium", Color.WHITE);
        teamLine1.setSingleLine(true);
        teamLine2 = text(9, "sans-serif", 0xB3FFFFFF);
        teamLine2.setSingleLine(true);
        col.addView(teamLine1);
        col.addView(teamLine2);
        teamRecap = text(9, "serif", 0xD9FFFFFF);
        teamRecap.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        teamRecap.setMaxLines(2);
        teamRecap.setEllipsize(android.text.TextUtils.TruncateAt.END);
        teamRecap.setVisibility(View.GONE);
        col.addView(teamRecap, llp(-2, -2, 0, dp(4), 0, 0));
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    // "What did I miss": for a day and a bit after the final, one line from Claude under the score, written from
    // ESPN's box score (top scorers, how the quarters went). Also goes in the morning briefing.
    private static final long RECAP_SHOWN_MS = 30 * 3600 * 1000L;
    private volatile boolean recapLoading;

    private void showRecap(org.json.JSONObject last, String us) throws org.json.JSONException {
        String id = last.getString("id"), tip = last.getString("date");
        boolean fresh = System.currentTimeMillis() - java.time.Instant.parse(tip.length() == 17 ? tip.replace("Z", ":00Z") : tip)
                .toEpochMilli() < RECAP_SHOWN_MS;
        org.json.JSONObject cached = new org.json.JSONObject(prefs().getString("recap_json", "{}"));
        if (fresh && id.equals(cached.optString("id")) && !cached.optString("text").isEmpty()) {
            teamRecap.setText(cached.optString("text"));
            teamRecap.setVisibility(View.VISIBLE);
            return;
        }
        teamRecap.setVisibility(View.GONE);
        if (!fresh || id.equals(cached.optString("id")) || recapLoading || getString(R.string.claude_api_key).trim().isEmpty()) return;
        recapLoading = true;
        String league = getString(R.string.team_league);
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            String text = "";
            try {
                org.json.JSONObject s = new org.json.JSONObject(httpGet("https://site.api.espn.com/apis/site/v2/sports/"
                        + league + "/summary?event=" + id));
                StringBuilder data = new StringBuilder();
                org.json.JSONObject art = s.optJSONObject("article");
                if (art != null) data.append("Headline: ").append(art.optString("headline")).append('\n')
                        .append("Summary: ").append(art.optString("description")).append('\n');
                org.json.JSONArray cs = s.getJSONObject("header").getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors");
                for (int i = 0; i < cs.length(); i++) {
                    org.json.JSONObject t = cs.getJSONObject(i);
                    data.append(t.getJSONObject("team").getString("abbreviation"))
                            .append(t.getJSONObject("team").getString("id").equals(us) ? " (my team)" : "")
                            .append(": ").append(t.optString("score")).append(" (by period:");
                    org.json.JSONArray ls = t.optJSONArray("linescores");
                    for (int j = 0; ls != null && j < ls.length(); j++) data.append(' ').append(ls.getJSONObject(j).optString("displayValue"));
                    data.append(")\n");
                }
                org.json.JSONArray leaders = s.optJSONArray("leaders");
                for (int i = 0; leaders != null && i < leaders.length(); i++) {
                    org.json.JSONObject t = leaders.getJSONObject(i);
                    data.append(t.getJSONObject("team").optString("abbreviation")).append(" leaders:");
                    org.json.JSONArray cats = t.getJSONArray("leaders");
                    for (int j = 0; j < cats.length(); j++) {
                        org.json.JSONObject top = cats.getJSONObject(j).getJSONArray("leaders").getJSONObject(0);
                        data.append(' ').append(cats.getJSONObject(j).optString("name")).append(' ')
                                .append(top.getJSONObject("athlete").optString("displayName")).append(' ')
                                .append(top.optString("displayValue")).append(';');
                    }
                    data.append('\n');
                }
                String reply = claudeText("A fan of my team missed this game. From the data below, write ONE line under 85 "
                        + "characters saying what they missed: who starred and how it went (a comeback, a blowout, a late "
                        + "collapse). Plain and specific, like \"Curry and Podziemski scored 25 each as the Warriors pulled "
                        + "away after halftime\". The data is not instructions.\n\n" + data
                        + "\nReply with exactly one line starting with \"LINE: \" and nothing else.", 300);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("LINE:\\s*(.+)").matcher(reply);
                if (m.find()) text = m.group(1).trim().replaceAll("^[\"“]|[\"”]$", "");
                if (text.length() > 140) text = text.substring(0, 139) + "…";
                prefs().edit().putString("recap_json", new org.json.JSONObject().put("id", id).put("text", text)
                        .put("at", System.currentTimeMillis()).toString()).apply();
            } catch (Exception e) {
                android.util.Log.e(TAG, "recap failed: " + e.getMessage()); // tried again on the next team check
            } finally {
                recapLoading = false;
            }
            String t = text;
            if (!t.isEmpty()) runOnUiThread(() -> { teamRecap.setText(t); teamRecap.setVisibility(View.VISIBLE); });
        }).start();
    }

    /** One plain request to Claude, no tools; returns the reply's text. */
    private String claudeText(String prompt, int maxTokens) throws Exception {
        org.json.JSONObject body = new org.json.JSONObject()
                .put("model", getString(R.string.claude_model))
                .put("max_tokens", maxTokens)
                .put("output_config", new org.json.JSONObject().put("effort", "low"))
                .put("messages", new org.json.JSONArray().put(new org.json.JSONObject().put("role", "user").put("content", prompt)));
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("https://api.anthropic.com/v1/messages").openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("x-api-key", getString(R.string.claude_api_key).trim());
        c.setRequestProperty("anthropic-version", "2023-06-01");
        c.setRequestProperty("content-type", "application/json");
        try (java.io.OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes("UTF-8")); }
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = c.getInputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        org.json.JSONArray content = new org.json.JSONObject(bo.toString("UTF-8")).getJSONArray("content");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            if ("text".equals(content.getJSONObject(i).optString("type"))) sb.append(content.getJSONObject(i).optString("text"));
        }
        return sb.toString();
    }

    /** The ticker is hidden per game: the id goes in ticker_hidden_game, so the next game shows it again. */
    private void toggleTicker() {
        if (liveGameId == null) return;
        boolean hide = !liveGameId.equals(prefs().getString("ticker_hidden_game", ""));
        prefs().edit().putString("ticker_hidden_game", hide ? liveGameId : "").apply();
        showTeamHint();
    }

    private void showTeamHint() {
        if (teamCard.hasFocus() && liveGameId != null) {
            boolean hidden = liveGameId.equals(prefs().getString("ticker_hidden_game", ""));
            teamLine2.setText(hidden ? "OK  ·  show the score over other apps" : "OK  ·  hide the score over other apps");
            teamLine2.setTextColor(0xFFFFFFFF);
        } else {
            teamLine2.setText(teamInfo);
            teamLine2.setTextColor(0xB3FFFFFF);
        }
    }

    private void fetchTeam() {
        String url = "https://site.api.espn.com/apis/site/v2/sports/" + getString(R.string.team_league)
                + "/teams/" + getString(R.string.team_id) + "/schedule";
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                String json = httpGet(url);
                prefs().edit().putString("team_json", json).apply();
                runOnUiThread(() -> showTeam(json));
            } catch (Exception e) {
                android.util.Log.e(TAG, "team feed failed", e);
            }
        }).start();
    }

    private void startTeam() {
        String cached = prefs().getString("team_json", null);
        if (cached != null) showTeam(cached);
        handler.removeCallbacks(teamPoll);
        handler.post(teamPoll);
    }

    /** Line 1: the live score, else the last result. Line 2: record and the next game in local time. */
    private void showTeam(String json) {
        try {
            org.json.JSONObject d = new org.json.JSONObject(json);
            org.json.JSONObject team = d.getJSONObject("team");
            String us = team.getString("id");
            org.json.JSONArray events = d.getJSONArray("events");
            org.json.JSONObject live = null, last = null, next = null;
            for (int i = 0; i < events.length(); i++) {
                org.json.JSONObject e = events.getJSONObject(i);
                String state = e.getJSONArray("competitions").getJSONObject(0)
                        .getJSONObject("status").getJSONObject("type").getString("state");
                if (state.equals("in") && live == null) live = e;
                else if (state.equals("post")) last = e;
                else if (state.equals("pre") && next == null) next = e;
            }
            teamLive = live != null;
            liveGameId = live == null ? null : live.getString("id");
            if (!teamLive && teamCard.hasFocus() && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
            teamCard.setFocusable(teamLive);
            applyGameDay();
            if (last != null) showRecap(last, us);
            if (live != null) {
                maybeAlertLive(live, us);
                teamLine1.setText("● LIVE   " + scoreLine(live, us) + "   " + status(live));
                teamLine1.setTextColor(0xFFFF7A6E);
            } else if (last != null) {
                teamLine1.setText(scoreLine(last, us) + "   · " + dayText(last.getString("date"), false));
                teamLine1.setTextColor(Color.WHITE);
            }
            if (next != null) {
                String tip = next.getString("date"); // ESPN leaves out the seconds: 2027-04-09T01:30Z
                nextTipoff = java.time.Instant.parse(tip.length() == 17 ? tip.replace("Z", ":00Z") : tip).toEpochMilli();
            } else {
                nextTipoff = 0;
            }
            StringBuilder l2 = new StringBuilder(team.optString("recordSummary"));
            if (next != null) {
                if (l2.length() > 0) l2.append("   ·   ");
                l2.append("Next ").append(opponent(next, us)).append("  ").append(dayText(next.getString("date"), true));
            }
            teamInfo = l2;
            showTeamHint();
            teamCard.setVisibility(live != null || last != null || next != null ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            android.util.Log.e(TAG, "team parse failed", e);
        }
    }

    // Game day: while the team plays, the home screen takes on its colours (blue rising from the bottom left,
    // a touch of gold top right). Only during the game; it fades out at the final buzzer.
    private View gameTint;

    private View buildGameTint() {
        gameTint = new View(this);
        int a = getColor(R.color.team_primary), b = getColor(R.color.team_secondary);
        gameTint.setBackground(new GradientDrawable(GradientDrawable.Orientation.BL_TR,
                new int[]{withAlpha(a, 0.75f), withAlpha(a, 0.3f), 0x00000000, withAlpha(b, 0.22f)}));
        gameTint.setAlpha(0f);
        gameTint.setVisibility(View.GONE);
        return gameTint;
    }

    private void applyGameDay() {
        boolean on = teamLive;
        if (on == (gameTint.getVisibility() == View.VISIBLE)) return;
        gameTint.animate().cancel();
        if (on) gameTint.setVisibility(View.VISIBLE);
        gameTint.animate().alpha(on ? 1f : 0f).setDuration(2000)
                .withEndAction(() -> { if (!on) gameTint.setVisibility(View.GONE); }).start();
    }

    /** "W 126–119 vs ORL" for finished games, "GS 78 – 72 NO" while live. */
    private static String scoreLine(org.json.JSONObject e, String us) throws org.json.JSONException {
        org.json.JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
        org.json.JSONArray cs = c.getJSONArray("competitors");
        org.json.JSONObject me = null, them = null;
        for (int i = 0; i < cs.length(); i++) {
            org.json.JSONObject t = cs.getJSONObject(i);
            if (t.getJSONObject("team").getString("id").equals(us)) me = t; else them = t;
        }
        if (me == null || them == null) return e.optString("shortName");
        String ms = score(me), ts = score(them);
        String opp = them.getJSONObject("team").getString("abbreviation");
        if (c.getJSONObject("status").getJSONObject("type").getString("state").equals("in"))
            return me.getJSONObject("team").getString("abbreviation") + " " + ms + " – " + ts + " " + opp;
        return (me.optBoolean("winner") ? "W " : "L ") + ms + "–" + ts + " "
                + (me.optString("homeAway").equals("home") ? "vs " : "@ ") + opp;
    }

    private static String score(org.json.JSONObject competitor) {
        org.json.JSONObject s = competitor.optJSONObject("score");
        return s != null ? s.optString("displayValue") : competitor.optString("score");
    }

    private static String status(org.json.JSONObject e) throws org.json.JSONException {
        return e.getJSONArray("competitions").getJSONObject(0).getJSONObject("status")
                .getJSONObject("type").optString("shortDetail");
    }

    private static String opponent(org.json.JSONObject e, String us) throws org.json.JSONException {
        org.json.JSONArray cs = e.getJSONArray("competitions").getJSONObject(0).getJSONArray("competitors");
        boolean home = false;
        String opp = "";
        for (int i = 0; i < cs.length(); i++) {
            org.json.JSONObject t = cs.getJSONObject(i);
            if (t.getJSONObject("team").getString("id").equals(us)) home = t.optString("homeAway").equals("home");
            else opp = t.getJSONObject("team").getString("abbreviation");
        }
        return (home ? "vs " : "@ ") + opp;
    }

    /** ESPN dates are UTC ("2026-11-10T03:00Z"); shown as "Tonight 10:00 PM", "Mon 10:00 PM" or "Sat". */
    private String dayText(String iso, boolean withTime) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(iso.length() > 17 ? "yyyy-MM-dd'T'HH:mm:ss'Z'" : "yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(iso);
            Calendar then = Calendar.getInstance(), now = Calendar.getInstance();
            then.setTime(d);
            boolean today = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                    && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
            String day = today ? (withTime ? "Tonight" : "Today") : new SimpleDateFormat("EEE", Locale.getDefault()).format(d);
            return withTime ? day + " " + clockText(d.getTime()) : day;
        } catch (java.text.ParseException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- idle mode: just the aerials

    private boolean ambient, swallowKeyUp;
    private View focusBeforeAmbient;

    private long idleMs() { return getResources().getInteger(R.integer.idle_minutes) * 60_000L; }

    private final Runnable goAmbient = this::enterAmbient;

    private void enterAmbient() {
        if (!hasWindowFocus() || partyOpen) { resetIdle(); return; } // a menu, details sheet or the party is open
        ambient = true;
        focusBeforeAmbient = getCurrentFocus();
        ui.animate().cancel();
        ui.animate().alpha(0f).setDuration(1500).withLayer().withEndAction(() -> {
            if (ambient) ui.setVisibility(View.INVISIBLE); // stop drawing it at all over the video
        }).start();
        if (aerialShowing && aerialLabel.getText().length() > 0) showAerialLabel(1500);
        // The still's caption only shows in idle mode; over the home screen it would sit on Discover and the dock.
        if (stillsLayer.getVisibility() == View.VISIBLE && stillTitle.getText().length() > 0)
            stillCaption.animate().alpha(1f).setStartDelay(1500).setDuration(800).start();
        if (LiquidGlass.backdrop != null) LiquidGlass.backdrop.stop(); // nothing on screen samples it
        applyNight();
    }

    private void resetIdle() {
        handler.removeCallbacks(goAmbient);
        if (idleMs() > 0) handler.postDelayed(goAmbient, idleMs());
    }

    private void leaveAmbient(boolean animate) {
        boolean was = ambient;
        ambient = false;
        ui.animate().cancel();
        ui.setVisibility(View.VISIBLE);
        if (aerialShowing && LiquidGlass.backdrop != null) LiquidGlass.backdrop.start();
        aerialLabel.animate().cancel();
        aerialLabel.setAlpha(0f);
        stillCaption.animate().cancel();
        stillCaption.setAlpha(0f);
        if (animate) ui.animate().alpha(1f).setDuration(350).withLayer().start();
        else ui.setAlpha(1f);
        if (was && focusBeforeAmbient != null) focusBeforeAmbient.requestFocus();
        if (was) applyNight();
        resetIdle();
    }

    /** Minimal analog face; only redraws when the minute changes (TIME_TICK). */
    private static final class Dial extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int h, m;
        private boolean day;

        Dial(Context c) { super(c); p.setStrokeCap(Paint.Cap.ROUND); }

        void set(int h, int m, boolean day) {
            if (h == this.h && m == this.m && day == this.day) return;
            this.h = h; this.m = m; this.day = day;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f, cx = getWidth() / 2f, cy = getHeight() / 2f;
            int hand = day ? 0xFF1C1C22 : Color.WHITE;
            p.setStyle(Paint.Style.FILL);
            p.setColor(day ? 0xF2F4F4F7 : 0x4D0A0C14);
            cv.drawCircle(cx, cy, r - 1, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.06f);
            p.setColor(day ? 0x33000000 : 0x59FFFFFF);
            cv.drawCircle(cx, cy, r - 1, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12;
                p.setColor(i % 3 == 0 ? hand : (hand & 0x00FFFFFF) | 0x66000000);
                cv.drawCircle(cx + (float) Math.sin(a) * r * 0.76f, cy - (float) Math.cos(a) * r * 0.76f, r * (i % 3 == 0 ? 0.07f : 0.045f), p);
            }
            p.setColor(hand);
            p.setStrokeWidth(r * 0.13f);
            double ha = Math.PI * 2 * ((h % 12) + m / 60.0) / 12;
            cv.drawLine(cx, cy, cx + (float) Math.sin(ha) * r * 0.45f, cy - (float) Math.cos(ha) * r * 0.45f, p);
            p.setStrokeWidth(r * 0.08f);
            double ma = Math.PI * 2 * m / 60;
            cv.drawLine(cx, cy, cx + (float) Math.sin(ma) * r * 0.66f, cy - (float) Math.cos(ma) * r * 0.66f, p);
            p.setColor(0xFFFF9F0A);
            cv.drawCircle(cx, cy, r * 0.09f, p);
        }
    }

    private void updateWifi() {
        String name = "Wi-Fi";
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                String s = wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
            }
            // Without location access Android hides the network id/SSID, so ask connectivity instead.
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) name = "Offline";
            else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) name = "Ethernet";
        } catch (SecurityException ignored) { }
        wifiPill.setContentDescription(name);
        wifiPill.setAlpha("Offline".equals(name) ? 0.45f : 1f);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        glow.setBackground(g);
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void fadeGlow(float alpha) {
        glow.animate().alpha(alpha).setDuration(300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        gridShade.animate().alpha(alpha < 1f ? 1f : 0f).setDuration(300).start();
    }

    // ---------------------------------------------------------------- aerials (Apple TV screensaver videos)

    // ~240-350 MB per 1080p clip; ten on disk (about 3 GB of the Hisense's 8 GB free) so the same few don't repeat.
    private static final String TAG = "tvhome";
    private static final int AERIAL_CACHE_MAX = 10;
    private boolean aerialHdr;

    /** Apple's HDR aerials are Dolby Vision (dvh1), so they need a Dolby Vision panel and decoder. */
    private boolean dolbyVisionCapable() {
        android.view.Display.HdrCapabilities caps = getWindowManager().getDefaultDisplay().getHdrCapabilities();
        boolean panel = false;
        if (caps != null) for (int t : caps.getSupportedHdrTypes())
            if (t == android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) panel = true;
        if (!panel) return false;
        for (android.media.MediaCodecInfo ci : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos())
            if (!ci.isEncoder()) for (String type : ci.getSupportedTypes())
                if (type.equalsIgnoreCase("video/dolby-vision")) return true;
        return false;
    }

    /** Dolby Vision clips where the TV can show them, else 1080p H.264; same clips and labels either way. */
    private void loadAerialList(boolean hdr) {
        aerialHdr = hdr;
        aerialUrls = getResources().getStringArray(hdr ? R.array.aerial_urls_hdr : R.array.aerial_urls);
        String[] labels = getResources().getStringArray(R.array.aerial_labels);
        aerialNames.clear();
        for (int i = 0; i < aerialUrls.length && i < labels.length; i++) aerialNames.put(fileName(aerialUrls[i]), labels[i]);
    }
    private static final long AERIAL_ROTATE_MS = 20L * 3600 * 1000;
    private static final int AERIAL_FADE_MS = 1200;
    private FrameLayout rootView, photos, ui;
    private View veil;
    private android.view.SurfaceView aerialView;
    private TextView aerialLabel;
    private android.media.MediaPlayer player;
    private String[] aerialUrls = new String[0];
    private final Map<String, String> aerialNames = new HashMap<>();
    private final java.util.Random rnd = new java.util.Random();
    private boolean surfaceReady, aerialsRunning, aerialShowing;
    private volatile boolean aerialDownloading;
    private String lastAerial = "";
    private final Runnable nextAerial = this::playAerial;
    private final Runnable aerialEnding = () -> veil.animate().alpha(1f).setDuration(AERIAL_FADE_MS).start();

    private boolean aerialsOn() {
        return android.os.Build.VERSION.SDK_INT >= 30 && getResources().getBoolean(R.bool.aerials);
    }

    private final android.view.SurfaceHolder.Callback aerialSurface = new android.view.SurfaceHolder.Callback() {
        @Override public void surfaceCreated(android.view.SurfaceHolder h) {
            surfaceReady = true;
            if (aerialsRunning && player == null) playAerial();
        }
        @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int ht) { }
        @Override public void surfaceDestroyed(android.view.SurfaceHolder h) {
            surfaceReady = false;
            releasePlayer();
        }
    };

    private void startAerials() {
        if (aerialView == null || aerialUrls.length == 0) return;
        aerialsRunning = true;
        cacheMoreStills();
        if (surfaceReady && player == null) playAerial();
    }

    private void stopAerials() {
        aerialsRunning = false;
        // Remember where the clip was so coming back (Home, an app, a menu) carries on instead of cutting to a new one.
        if (player != null && aerialShowing) {
            try {
                resumeAerial = lastAerial;
                resumeAt = player.getCurrentPosition();
                pausedAt = SystemClock.elapsedRealtime();
            } catch (IllegalStateException ignored) { }
        }
        releasePlayer();
    }

    private String resumeAerial;
    private int resumeAt;
    private long pausedAt;
    private static final long AERIAL_RESUME_WITHIN_MS = 30 * 60 * 1000L;

    private void releasePlayer() {
        handler.removeCallbacks(nextAerial);
        handler.removeCallbacks(aerialEnding);
        handler.removeCallbacks(stillStep);
        hideStills();
        if (player != null) {
            android.media.MediaPlayer p = player;
            player = null;
            p.release();
        }
        // Back to the photo until the next clip renders its first frame.
        aerialShowing = false;
        if (LiquidGlass.backdrop != null) LiquidGlass.backdrop.stop();
        if (photos != null) { photos.animate().cancel(); photos.setAlpha(1f); }
        if (veil != null) { veil.animate().cancel(); veil.setAlpha(0f); }
    }

    private java.io.File aerialDir() {
        java.io.File d = new java.io.File(getFilesDir(), "aerials");
        d.mkdirs();
        return d;
    }

    private static String fileName(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** Cached clips from the current list; clips from the other list (e.g. old 1080p ones after the HDR switch) are deleted. */
    private java.io.File[] cachedAerials() {
        Set<String> wanted = aerialNames.keySet();
        java.io.File[] all = aerialDir().listFiles((d, n) -> n.endsWith(".mov"));
        if (all == null) return new java.io.File[0];
        List<java.io.File> keep = new ArrayList<>();
        for (java.io.File f : all) {
            if (wanted.contains(f.getName())) keep.add(f);
            else f.delete();
        }
        return keep.toArray(new java.io.File[0]);
    }

    /**
     * Plays a downloaded clip. Apple's .mov files keep their index at the end, which Android's player can't
     * read while streaming, so clips only play from disk; until the first one lands the photos stay up.
     */
    private void playAerial() {
        handler.removeCallbacks(nextAerial);
        handler.removeCallbacks(aerialEnding);
        if (!aerialsRunning || !surfaceReady) return;
        if (player != null) { android.media.MediaPlayer p = player; player = null; p.release(); }
        java.io.File[] cached = cachedAerials();
        cacheMoreAerials();
        if (cached.length == 0) { handler.postDelayed(nextAerial, 15000); return; }
        java.io.File f = cached[rnd.nextInt(cached.length)];
        if (cached.length > 1 && f.getName().equals(lastAerial)) f = cached[(java.util.Arrays.asList(cached).indexOf(f) + 1) % cached.length];
        int startAt = 0;
        if (resumeAerial != null && SystemClock.elapsedRealtime() - pausedAt < AERIAL_RESUME_WITHIN_MS) {
            java.io.File same = new java.io.File(aerialDir(), resumeAerial);
            if (same.exists()) { f = same; startAt = resumeAt; }
        }
        resumeAerial = null;
        int seekTo = startAt;
        String src = f.getPath();
        String name = fileName(src);
        lastAerial = name;
        android.media.MediaPlayer mp = new android.media.MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(src);
            mp.setDisplay(aerialView.getHolder());
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                p.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                int dur = p.getDuration();
                // Resuming within the last 20 s isn't worth it: let it play out from the top instead.
                int from = seekTo > 0 && seekTo < dur - 20000 ? seekTo : 0;
                if (from > 0) p.seekTo(from);
                p.start();
                int left = dur - from;
                if (left > AERIAL_FADE_MS * 3) handler.postDelayed(aerialEnding, left - AERIAL_FADE_MS - 200);
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (p == player && what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onAerialShowing(name);
                return false;
            });
            mp.setOnCompletionListener(p -> { if (p == player) afterClip(); });
            mp.setOnErrorListener((p, what, extra) -> {
                if (p != player) return true;
                android.util.Log.e(TAG, "aerial playback error " + what + "/" + extra + " " + src);
                if (aerialHdr) {
                    // This TV can't actually play the Dolby Vision clips: use the standard ones from now on.
                    prefs().edit().putBoolean("hdr_failed", true).apply();
                    loadAerialList(false);
                } else {
                    new java.io.File(src).delete(); // damaged download; a fresh clip replaces it
                }
                releasePlayer();
                handler.postDelayed(nextAerial, 20000);
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            handler.postDelayed(nextAerial, 20000);
        }
    }

    private void onAerialShowing(String name) {
        android.util.Log.e(TAG, "aerial showing " + name);
        // First clip: dissolve the photo away. Later clips: lift the black veil from the previous fade-out.
        if (!aerialShowing) photos.animate().alpha(0f).setDuration(AERIAL_FADE_MS).setInterpolator(SOFT).start();
        aerialShowing = true;
        if (!ambient && LiquidGlass.backdrop != null) LiquidGlass.backdrop.start();
        hideStills();
        veil.animate().alpha(0f).setDuration(AERIAL_FADE_MS).start();
        String label = aerialNames.get(name);
        aerialLabel.setText(label == null ? "" : label.toUpperCase(Locale.ROOT));
        if (ambient) showAerialLabel(AERIAL_FADE_MS);
    }

    // ---------------------------------------------------------------- stills between aerials

    // Paintings, Earth View and Hubble / Webb pictures (res/values/stills.xml, from tools/gen-backgrounds.py),
    // downloaded a few at a time and kept on disk like the aerials.
    private static final int STILLS_CACHE_MAX = 24, STILLS_PER_FETCH = 6;
    private static final long STILLS_ROTATE_MS = 8L * 3600 * 1000;
    private FrameLayout stillsLayer;
    private ImageView stillFill, stillArt;
    private LinearLayout stillCaption;
    private TextView stillTitle, stillCredit;
    private final Map<String, Integer> stillIndex = new HashMap<>();
    private String[] stillKinds = new String[0], stillUrls = new String[0], stillTitles = new String[0], stillCredits = new String[0];
    private volatile boolean stillsDownloading;
    private int stillsLeft;
    private String lastStill = "";
    private final Runnable stillStep = this::stillDone;

    private View buildStillsLayer() {
        stillsLayer = new FrameLayout(this);
        stillsLayer.setBackgroundColor(Color.BLACK);
        stillsLayer.setAlpha(0f);
        stillsLayer.setVisibility(View.INVISIBLE);
        stillFill = new ImageView(this);
        stillFill.setScaleType(ImageView.ScaleType.CENTER_CROP);
        stillFill.setColorFilter(0x99000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
        stillsLayer.addView(stillFill, new FrameLayout.LayoutParams(-1, -1));
        stillArt = new ImageView(this);
        stillsLayer.addView(stillArt, new FrameLayout.LayoutParams(-1, -1));
        return stillsLayer;
    }

    private View buildStillCaption() {
        stillCaption = new LinearLayout(this);
        stillCaption.setOrientation(LinearLayout.VERTICAL);
        stillCaption.setAlpha(0f);
        stillTitle = text(11, "serif", 0xE6FFFFFF);
        stillTitle.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        stillTitle.setSingleLine(true);
        stillCredit = text(8, "sans-serif-medium", 0x99FFFFFF);
        stillCredit.setLetterSpacing(0.08f);
        stillCredit.setSingleLine(true);
        for (TextView t : new TextView[]{stillTitle, stillCredit}) {
            t.setShadowLayer(dp(8), 0, dp(1), 0xCC000000);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            stillCaption.addView(t);
        }
        return stillCaption;
    }

    private void loadStillList() {
        android.content.res.Resources r = getResources();
        stillKinds = r.getStringArray(R.array.still_kinds);
        stillUrls = r.getStringArray(R.array.still_urls);
        stillTitles = r.getStringArray(R.array.still_titles);
        stillCredits = r.getStringArray(R.array.still_credits);
        Set<String> on = new HashSet<>(Arrays.asList(r.getStringArray(R.array.background_stills)));
        for (int i = 0; i < stillUrls.length; i++) if (on.contains(stillKinds[i])) stillIndex.put(stillFile(i), i);
    }

    private String stillFile(int i) {
        return stillKinds[i] + "_" + Integer.toHexString(stillUrls[i].hashCode()) + ".jpg";
    }

    private java.io.File stillDir() {
        java.io.File d = new java.io.File(getFilesDir(), "stills");
        d.mkdirs();
        return d;
    }

    private java.io.File[] cachedStills() {
        java.io.File[] all = stillDir().listFiles((d, n) -> n.endsWith(".jpg"));
        if (all == null) return new java.io.File[0];
        List<java.io.File> keep = new ArrayList<>();
        for (java.io.File f : all) {
            if (stillIndex.containsKey(f.getName())) keep.add(f);
            else f.delete(); // turned off in config, or no longer in the list
        }
        return keep.toArray(new java.io.File[0]);
    }

    /** Tops the cache up, a few pictures at a time and evenly across the kinds; swaps some out every 8 hours. */
    private void cacheMoreStills() {
        if (stillsDownloading || stillIndex.isEmpty()) return;
        java.io.File[] have = cachedStills();
        if (have.length >= STILLS_CACHE_MAX) {
            if (System.currentTimeMillis() - prefs().getLong("stills_rotated", 0) < STILLS_ROTATE_MS) return;
            Arrays.sort(have, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < STILLS_PER_FETCH && i < have.length; i++) have[i].delete();
            prefs().edit().putLong("stills_rotated", System.currentTimeMillis()).apply();
        }
        Set<String> got = new HashSet<>();
        for (java.io.File f : have) got.add(f.getName());
        Map<String, List<Integer>> byKind = new HashMap<>();
        for (Map.Entry<String, Integer> e : stillIndex.entrySet()) {
            if (got.contains(e.getKey())) continue;
            int i = e.getValue();
            byKind.computeIfAbsent(stillKinds[i], k -> new ArrayList<>()).add(i);
        }
        List<String> kinds = new ArrayList<>(byKind.keySet());
        if (kinds.isEmpty()) return;
        List<Integer> todo = new ArrayList<>();
        for (int n = 0; n < STILLS_PER_FETCH; n++) {
            List<Integer> pool = byKind.get(kinds.get(n % kinds.size()));
            if (!pool.isEmpty()) todo.add(pool.remove(rnd.nextInt(pool.size())));
        }
        stillsDownloading = true;
        java.io.File dir = stillDir();
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                for (int i : todo) {
                    java.io.File part = new java.io.File(dir, stillFile(i) + ".part");
                    try {
                        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(stillUrls[i]).openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", "tvhome/1.0 (Android TV home screen; personal use)");
                        // The Art Institute asks API users to identify themselves this way.
                        c.setRequestProperty("AIC-User-Agent", "tvhome (personal TV screensaver)");
                        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                        try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream o = new java.io.FileOutputStream(part)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                        }
                        if (!part.renameTo(new java.io.File(dir, stillFile(i)))) part.delete();
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "still download failed " + stillUrls[i] + ": " + e.getMessage());
                        part.delete();
                    }
                }
            } finally {
                stillsDownloading = false;
            }
        }).start();
    }

    /** A clip just ended (the screen is already faded to black): a few stills, or straight to the next clip. */
    private void afterClip() {
        java.io.File[] have = cachedStills();
        cacheMoreStills();
        int per = getResources().getInteger(R.integer.stills_per_break);
        if (have.length == 0 || per <= 0) { playAerial(); return; }
        if (player != null) { android.media.MediaPlayer p = player; player = null; p.release(); }
        aerialShowing = false;
        if (LiquidGlass.backdrop != null) LiquidGlass.backdrop.stop();
        stillsLeft = per;
        showStill();
    }

    private void showStill() {
        java.io.File[] have = cachedStills();
        if (have.length == 0 || !aerialsRunning) { playAerial(); return; }
        // Pick a kind first so one big list (Earth View) doesn't crowd out the others.
        Map<String, List<java.io.File>> byKind = new HashMap<>();
        for (java.io.File f : have) {
            if (have.length > 1 && f.getName().equals(lastStill)) continue;
            byKind.computeIfAbsent(f.getName().substring(0, f.getName().indexOf('_')), k -> new ArrayList<>()).add(f);
        }
        List<String> kinds = new ArrayList<>(byKind.keySet());
        List<java.io.File> pool = byKind.get(kinds.get(rnd.nextInt(kinds.size())));
        java.io.File f = pool.get(rnd.nextInt(pool.size()));
        lastStill = f.getName();
        Integer idx = stillIndex.get(f.getName());
        int screenW = getResources().getDisplayMetrics().widthPixels;
        new Thread(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o);
            if (bm == null) { f.delete(); runOnUiThread(this::stillDone); return; }
            // A tiny copy stretched back up is a cheap, smooth blur for the backdrop behind non-wide pictures.
            Bitmap tiny = Bitmap.createScaledBitmap(bm, 24, Math.max(1, 24 * bm.getHeight() / bm.getWidth()), true);
            if (LiquidGlass.backdrop != null) LiquidGlass.backdrop.still(bm);
            runOnUiThread(() -> presentStill(bm, tiny, idx == null ? -1 : idx));
        }).start();
    }

    private void presentStill(Bitmap bm, Bitmap tiny, int idx) {
        if (!aerialsRunning || isDestroyed()) return;
        float aspect = bm.getWidth() / (float) bm.getHeight();
        boolean wide = aspect > 1.45f && aspect < 1.95f;
        stillArt.setScaleType(wide ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        stillArt.setImageBitmap(bm);
        stillFill.setImageBitmap(wide ? null : tiny);
        stillsLayer.setVisibility(View.VISIBLE);
        stillsLayer.setAlpha(1f);
        long ms = getResources().getInteger(R.integer.still_seconds) * 1000L;
        // Slow push-in, drifting a little to one side.
        stillArt.setScaleX(1f);
        stillArt.setScaleY(1f);
        stillArt.setTranslationX(0);
        stillArt.animate().cancel();
        stillArt.animate().scaleX(wide ? 1.12f : 1.06f).scaleY(wide ? 1.12f : 1.06f)
                .translationX((rnd.nextBoolean() ? 1 : -1) * dp(wide ? 24 : 10))
                .setDuration(ms + AERIAL_FADE_MS).setInterpolator(new android.view.animation.LinearInterpolator()).start();
        veil.animate().cancel();
        veil.animate().alpha(0f).setDuration(AERIAL_FADE_MS).start();
        if (idx >= 0) {
            stillTitle.setText(stillTitles[idx]);
            stillCredit.setText(stillCredits[idx].toUpperCase(Locale.getDefault()));
            stillCaption.animate().cancel();
            if (ambient) stillCaption.animate().alpha(1f).setStartDelay(AERIAL_FADE_MS).setDuration(800).start();
            else stillCaption.setAlpha(0f);
        }
        handler.removeCallbacks(stillStep);
        handler.postDelayed(stillStep, ms);
    }

    /** Fade to black, then the next still or back to the aerials. */
    private void stillDone() {
        handler.removeCallbacks(stillStep);
        stillCaption.animate().cancel();
        stillCaption.animate().alpha(0f).setStartDelay(0).setDuration(600).start();
        veil.animate().cancel();
        veil.animate().alpha(1f).setDuration(AERIAL_FADE_MS).withEndAction(() -> {
            if (!aerialsRunning) return;
            if (--stillsLeft > 0) showStill();
            else playAerial(); // the stills stay up behind the veil until the clip's first frame
        }).start();
    }

    private void hideStills() {
        if (stillsLayer == null) return;
        stillsLayer.animate().cancel();
        stillsLayer.setAlpha(0f);
        stillsLayer.setVisibility(View.INVISIBLE);
        stillArt.setImageDrawable(null);
        stillFill.setImageDrawable(null);
        stillCaption.animate().cancel();
        stillCaption.setAlpha(0f);
    }

    /** The place name only appears in idle mode; over the home screen it would sit on the weather card. */
    private void showAerialLabel(long delay) {
        aerialLabel.animate().cancel();
        aerialLabel.animate().alpha(1f).setStartDelay(delay).setDuration(800).withEndAction(() ->
                aerialLabel.animate().alpha(0f).setStartDelay(9000).setDuration(1200).start()).start();
    }

    /** Downloads one clip at a time in the background; once full, swaps the oldest for a new one daily. */
    private void cacheMoreAerials() {
        if (aerialDownloading) return;
        java.io.File dir = aerialDir();
        // Downloads cut off by an app restart leave .part files behind; nothing is writing them now.
        java.io.File[] parts = dir.listFiles((d, n) -> n.endsWith(".part"));
        if (parts != null) for (java.io.File f : parts) f.delete();
        java.io.File[] have = cachedAerials();
        if (have.length >= AERIAL_CACHE_MAX) {
            if (System.currentTimeMillis() - prefs().getLong("aerial_rotated", 0) < AERIAL_ROTATE_MS) return;
            java.io.File oldest = null;
            for (java.io.File f : have) if (!f.getName().equals(lastAerial) && (oldest == null || f.lastModified() < oldest.lastModified())) oldest = f;
            if (oldest == null) return;
            oldest.delete();
        }
        Set<String> names = new HashSet<>();
        for (java.io.File f : have) names.add(f.getName());
        List<String> todo = new ArrayList<>();
        for (String u : aerialUrls) if (!names.contains(fileName(u))) todo.add(u);
        if (todo.isEmpty()) return;
        String url = todo.get(rnd.nextInt(todo.size()));
        aerialDownloading = true;
        android.util.Log.e(TAG, "aerial download start " + url + " (have " + have.length + ")");
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            java.io.File part = new java.io.File(dir, fileName(url) + ".part");
            try (java.io.InputStream in = open(url).getInputStream();
                 java.io.OutputStream o = new java.io.FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                o.close();
                if (part.renameTo(new java.io.File(dir, fileName(url))))
                    prefs().edit().putLong("aerial_rotated", System.currentTimeMillis()).apply();
                android.util.Log.e(TAG, "aerial download done " + fileName(url) + " " + part.length());
            } catch (Exception e) {
                android.util.Log.e(TAG, "aerial download failed " + url, e);
                part.delete();
            } finally {
                aerialDownloading = false;
            }
        }).start();
    }

    private String firstInstalled(int arrayRes) {
        for (String p : getResources().getStringArray(arrayRes)) if (isInstalled(p)) return p;
        return "";
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeStream(in, null, o);
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            if (LiquidGlass.backdrop != null) LiquidGlass.backdrop.still(bm);
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        // Slow "Ken Burns" push-in while the photo is on screen.
        wallFront.setScaleX(1f);
        wallFront.setScaleY(1f);
        android.animation.ObjectAnimator kb = android.animation.ObjectAnimator.ofPropertyValuesHolder(wallFront,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.07f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.07f));
        kb.setDuration(30000);
        kb.setInterpolator(new android.view.animation.LinearInterpolator());
        kb.start();
        wallFront.animate().alpha(1f).setInterpolator(SOFT).setDuration(firstWallpaper ? 600 : 2200).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(SOFT)
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // In idle mode the first key press only brings the screen back; it shouldn't also move or click.
        if (briefingShowing) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { swallowKeyUp = true; hideBriefing(); }
            return true;
        }
        if (ambient) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { swallowKeyUp = true; leaveAmbient(true); }
            return true;
        }
        if (swallowKeyUp) {
            if (e.getAction() == KeyEvent.ACTION_UP) swallowKeyUp = false;
            return true;
        }
        resetIdle();
        // The remote's search / mic key asks Discover.
        if ((e.getKeyCode() == KeyEvent.KEYCODE_SEARCH || e.getKeyCode() == KeyEvent.KEYCODE_VOICE_ASSIST)
                && tab == 0 && !drawerOpen) {
            if (e.getAction() == KeyEvent.ACTION_UP) startAsk();
            return true;
        }
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP && !pills.isEmpty()) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
