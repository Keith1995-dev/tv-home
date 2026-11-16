package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;
import java.util.Random;

/**
 * The GTA VI countdown. On Home it's a card: a neon beach (retro sun, swaying palms, a shimmering sea) with the
 * days, hours, minutes and seconds to the unlock, a wanted level that climbs as the day gets closer, and a rotating
 * one-liner. The sun sinks a little every day, and by launch day it's night over a lit skyline. At zero it throws
 * confetti for a few days, then hides itself. All drawn here, no game artwork. The moment comes from config.xml (gta6_unlock, an ISO instant).
 */
final class GtaCountdown extends View {
    private static final String[] LINES = {
            "Clear your schedule.",
            "Charge the controller. Both of them.",
            "Tell work you're sick on the 19th.",
            "Pre-load ready? Check the PS5.",
            "Sunglasses on. Sun's not down yet.",
            "Snacks stocked? Asking for a friend.",
            "Twelve years of waiting. Almost there.",
            "Your social life has been placed on hold.",
            "Plants, pets, people: water them all now.",
            "Last chance to touch grass.",
            "Hydrate now. You won't remember to later.",
            "Practising the speed limit. Ironically.",
            "Booked sick leave in pencil. And pen.",
            "Mom, I'll eat dinner later. Much later.",
            "Your bed will miss you. Briefly.",
            "Uninstalling anything that isn't this.",
            "Pizza place has you on speed dial.",
            "Estimated sleep next week: optional.",
            "Somewhere, a trailer is being rewatched.",
            "The couch has been informed.",
            "Group chat status: going dark soon.",
            "Free up 150 GB. Sorry, old games.",
            "Delivery drivers, stand by.",
            "Practising your innocent face for the cops.",
            "Five stars is a goal, not a warning.",
            "Brace yourself, bank account. Again.",
            "The wait is almost over. Unlike the trailer views.",
            "Remember daylight? Enjoy it while it lasts.",
    };
    private static final long DAY = 86_400_000L;
    private static final float W = 500, H = 104; // the card's design canvas, in dp
    private static final int[] SKY_DUSK = {0xFF1B0B3A, 0xFF5A1470, 0xFFE0337E, 0xFFFF8A45},
            SKY_NIGHT = {0xFF040212, 0xFF0E0830, 0xFF2E1260, 0xFF7A1C78},
            NEON = {0xFFFF3D9A, 0xFF34E1FF, 0xFFFFD84D, 0xFF7CFF6B};

    /** The unlock moment from config.xml, or 0 when there's none. */
    static long unlockAt(Context c) {
        try {
            String s = c.getString(R.string.gta6_unlock).trim();
            return s.isEmpty() ? 0 : java.time.Instant.parse(s).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Shown until three days after the unlock. */
    static boolean relevant(long unlock) {
        return unlock > 0 && System.currentTimeMillis() < unlock + 3 * DAY;
    }

    /** "3 days 20 hours", "5 hours 12 minutes" or "40 minutes". */
    static String left(long ms) {
        long m = Math.max(0, ms) / 60_000, d = m / 1440, h = m / 60 % 24;
        if (d > 0) return d + (d == 1 ? " day " : " days ") + h + (h == 1 ? " hour" : " hours");
        if (h > 0) return h + (h == 1 ? " hour " : " hours ") + m % 60 + " minutes";
        return m + (m == 1 ? " minute" : " minutes");
    }

    static String line() { return LINES[new Random().nextInt(LINES.length)]; }

    /** For the morning briefing on launch day: "Today's the day. GTA VI unlocks at 1:30 PM."; otherwise null. */
    static String todayLine(Context c) {
        long at = unlockAt(c), now = System.currentTimeMillis();
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US);
        if (at == 0 || now >= at || !day.format(now).equals(day.format(at))) return null;
        return "Today's the day. GTA VI unlocks at " + new java.text.SimpleDateFormat("h:mm a", Locale.US).format(at) + ".";
    }

    private final long unlock;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Typeface black = Typeface.create("sans-serif-black", Typeface.ITALIC);
    private final Typeface digits = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface label = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final float[][] confetti = new float[46][5], stars = new float[60][3], towers = new float[26][4];
    private Shader sky, sun, sea, rim;
    private float rimW;
    private float shadedNight = -1, shadedH;
    private boolean running;

    GtaCountdown(Context c, long unlock) {
        super(c);
        this.unlock = unlock;
        d = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        Random rnd = new Random(19);
        for (float[] f : confetti) { f[0] = rnd.nextFloat(); f[1] = rnd.nextFloat(); f[2] = 0.4f + rnd.nextFloat(); f[3] = rnd.nextInt(4); f[4] = rnd.nextFloat() * 6.28f; }
        for (float[] s : stars) { s[0] = rnd.nextFloat(); s[1] = rnd.nextFloat(); s[2] = rnd.nextFloat() * 6.28f; }
        float x = 0.08f;
        for (float[] b : towers) { // x, width, height (fractions of the view), window colour
            b[0] = x; b[1] = 0.018f + rnd.nextFloat() * 0.022f; b[2] = 0.07f + rnd.nextFloat() * 0.20f; b[3] = rnd.nextInt(3);
            x += b[1] + 0.004f;
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); running = true; invalidate(); }
    @Override protected void onDetachedFromWindow() { running = false; super.onDetachedFromWindow(); }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        running = visible;
        if (visible) invalidate();
    }

    /** 0 five days out (sunset) to 1 on launch day and after (night). */
    private float night(long left) {
        return left <= 0 ? 1f : Math.max(0f, Math.min(1f, 1f - (left - DAY / 2f) / (4.5f * DAY)));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis(), left = unlock - now;
        float t = (now % 600_000L) / 1000f;
        // Drawn on a design canvas 104 dp tall, scaled to the card's height; a wider card just gets more beach.
        float k = getHeight() / (H * d), w = getWidth() / k, h = H * d, rad = 18 * d;
        c.save();
        c.scale(k, k);
        c.save();
        r.set(0, 0, w, h);
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(path);
        scenery(c, w, h, h * 0.78f, w * 0.89f, night(left), t, d);
        if (left > 0) drawCountdown(c, w, h, left, now);
        else drawParty(c, w, h, now, t, d, 0.62f);
        c.restore();

        // A thin neon rim.
        if (rim == null || rimW != w) rim = new LinearGradient(0, 0, w, h, 0xCCFF4FB0, 0xCC34E1FF, Shader.TileMode.CLAMP);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setColor(Color.WHITE); // a shader still takes the paint's alpha
        rimW = w;
        p.setShader(rim);
        r.set(d, d, w - d, h - d);
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        c.restore();

        if (running) postInvalidateDelayed(left > 0 ? 50 : 33);
    }

    // ---------------------------------------------------------------- the beach

    /** Sky, sun, sea, stars, skyline and palms; u is the size of one "dp" of detail. */
    private void scenery(Canvas c, float w, float h, float horizon, float sunX, float night, float t, float u) {
        float sunR = h * 0.36f;
        if (sky == null || Math.abs(night - shadedNight) > 0.003f || shadedH != h) {
            int[] cols = new int[4];
            for (int i = 0; i < 4; i++) cols[i] = mix(SKY_DUSK[i], SKY_NIGHT[i], night);
            sky = new LinearGradient(0, 0, 0, horizon, cols, new float[]{0f, 0.38f, 0.78f, 1f}, Shader.TileMode.CLAMP);
            sea = new LinearGradient(0, horizon, 0, h, mix(0xFF2A0F55, 0xFF0C0626, night), mix(0xFF0B1630, 0xFF02030A, night), Shader.TileMode.CLAMP);
            shadedNight = night;
            shadedH = h;
        }
        p.setColor(Color.WHITE); // a shader still takes the paint's alpha
        p.setShader(sky);
        c.drawRect(0, 0, w, horizon, p);
        p.setShader(null);

        // Stars come out as the sky darkens.
        if (night > 0.15f) for (float[] s : stars) {
            float a = (night - 0.15f) / 0.85f * (0.55f + 0.45f * (float) Math.sin(t * 1.3 + s[2]));
            p.setColor(Color.argb((int) (230 * a * (1 - s[1] * 0.7f)), 255, 255, 255));
            c.drawCircle(s[0] * w, s[1] * horizon * 0.8f, (0.5f + (s[2] % 1f) * 0.6f) * u, p);
        }

        // The striped retro sun, a little lower every day, gone by launch day.
        float sunY = horizon + sunR * 1.12f * night;
        if (sunY - sunR < horizon) {
            c.save();
            c.clipRect(0, 0, w, horizon);
            if (sun == null) sun = new LinearGradient(0, -sunR, 0, 0, 0xFFFFE27A, 0xFFFF4F8B, Shader.TileMode.CLAMP);
            c.translate(sunX, sunY);
            p.setColor(Color.WHITE); // a shader still takes the paint's alpha
            p.setShader(sun);
            c.drawCircle(0, 0, sunR, p);
            p.setShader(null);
            p.setColor(mix(SKY_DUSK[3], SKY_NIGHT[3], night));
            for (int i = 0; i < 4; i++) { // the classic gaps across the lower sun, drifting down
                float y = -sunR * 0.55f + i * sunR * 0.16f + (t * 4 * u) % (sunR * 0.16f);
                c.drawRect(-sunR, y, sunR, y + (1.5f + i) * u, p);
            }
            c.restore();
        }

        // The city lights up for the last day and a half.
        float city = Math.max(0f, Math.min(1f, (night - 0.72f) / 0.12f));
        if (city > 0) for (int i = 0; i < towers.length; i++) {
            float[] b = towers[i];
            float bx = b[0] * w, bw = b[1] * w, bh = b[2] * h * city;
            p.setColor(0xFF07041A);
            c.drawRect(bx, horizon - bh, bx + bw, horizon, p);
            int col = NEON[(int) b[3]];
            for (float wy = horizon - bh + 3 * u; wy < horizon - 3 * u; wy += 4 * u) {
                for (float wx = bx + 2 * u; wx < bx + bw - 2.5f * u; wx += 3.5f * u) {
                    int seed = (int) (wx * 7 + wy * 13 + i * 31);
                    if (seed % 3 == 0) continue; // an unlit window
                    boolean flick = seed % 11 == 0 && ((long) (t * 2) + seed) % 5 == 0;
                    p.setColor(Color.argb(flick ? 60 : 200, Color.red(col), Color.green(col), Color.blue(col)));
                    c.drawRect(wx, wy, wx + 1.6f * u, wy + 1.8f * u, p);
                }
            }
        }

        p.setColor(Color.WHITE); // a shader still takes the paint's alpha
        p.setShader(sea);
        c.drawRect(0, horizon, w, h, p);
        p.setShader(null);
        int glint = mix(0xFFFFAA78, 0xFFFF5CC0, night);
        for (int i = 0; i < 7; i++) {
            float y = horizon + (h - horizon) * (0.15f + i * 0.13f);
            float len = sunR * (1.3f - i * 0.12f) * (0.8f + 0.2f * (float) Math.sin(t * 1.7 + i));
            p.setColor(Color.argb((int) ((150 - i * 16) * (1 - night * 0.45f)), Color.red(glint), Color.green(glint), Color.blue(glint)));
            c.drawRect(sunX - len / 2, y, sunX + len / 2, y + 1.6f * u, p);
        }

        palm(c, w * 0.035f, h, h * 0.92f, -0.10f, t, 0);
        palm(c, w * 0.975f, h, h * 0.78f, 0.12f, t, 1.3f);
        palm(c, w * 0.93f, h, h * 0.58f, 0.04f, t, 2.1f);
    }

    /** A palm silhouette: a curved trunk from the bottom, and seven fronds that sway. */
    private void palm(Canvas c, float x, float bottom, float height, float lean, float t, float seed) {
        float sway = (float) Math.sin(t * 0.9 + seed) * 0.06f;
        float topX = x + height * (lean + sway * 0.3f), topY = bottom - height;
        p.setColor(0xF2120620);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(height * 0.05f);
        path.reset();
        path.moveTo(x, bottom + 4 * d);
        path.quadTo(x + height * lean * 0.2f, bottom - height * 0.5f, topX, topY);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 7; i++) {
            double a = Math.PI * (0.92 + i * 0.19) + sway * (i % 2 == 0 ? 1.4 : -1.1);
            float len = height * (0.42f + 0.05f * (i % 3));
            float ex = topX + (float) Math.cos(a) * len, ey = topY + (float) Math.sin(a) * len * 0.55f + len * 0.25f;
            float mx = topX + (float) Math.cos(a) * len * 0.5f, my = topY + (float) Math.sin(a) * len * 0.5f - len * 0.12f;
            float nx = (float) -Math.sin(a) * len * 0.09f, ny = (float) Math.cos(a) * len * 0.09f;
            path.reset();
            path.moveTo(topX, topY);
            path.quadTo(mx + nx, my + ny, ex, ey);
            path.quadTo(mx - nx, my - ny, topX, topY);
            c.drawPath(path, p);
        }
        c.drawCircle(topX, topY, height * 0.035f, p);
    }

    // ---------------------------------------------------------------- the card

    private void drawCountdown(Canvas c, float w, float h, long left, long now) {
        float x0 = 26 * d;
        text(c, "GTA VI", x0, 38 * d, 28 * d, black, Color.WHITE, 0xFFFF3D9A, 10 * d);

        // Wanted level: a star a day for the last five days, the next one blinking.
        int lit = (int) Math.max(0, Math.min(5, 5 - Math.ceil(left / (double) DAY) + 1));
        boolean blink = (now / 500) % 2 == 0;
        for (int i = 0; i < 5; i++) {
            boolean on = i < lit || (i == lit && blink);
            star(c, x0 + 6 * d + i * 15 * d, 60 * d, 5.5f * d, on ? 0xFFFFD84D : 0x55FFFFFF, on && i < lit);
        }

        // DD : HH : MM : SS
        long s = left / 1000;
        String[] v = {String.valueOf(s / 86400), two(s / 3600 % 24), two(s / 60 % 60), two(s % 60)};
        String[] lab = {"DAYS", "HRS", "MIN", "SEC"};
        float cell = 46 * d, gx = w * 0.34f;
        for (int i = 0; i < 4; i++) {
            float cx = gx + i * (cell + 8 * d);
            r.set(cx, 16 * d, cx + cell, 78 * d); // tall enough to keep the label clear of the skyline
            p.setColor(0x9914072E);
            c.drawRoundRect(r, 10 * d, 10 * d, p);
            p.setColor(0x3329E8FF);
            c.drawRect(cx + 4 * d, 39 * d, cx + cell - 4 * d, 39 * d + Math.max(1, d), p); // the flip line
            float pulse = i == 3 ? 0.6f + 0.4f * (float) Math.cos((now % 1000) / 1000f * 6.28f) : 1f;
            centred(c, v[i], cx + cell / 2, 49 * d, 30 * d, digits, Color.WHITE, Color.argb((int) (255 * pulse), 0x34, 0xE1, 0xFF), 9 * d);
            centred(c, lab[i], cx + cell / 2, 73 * d, 7.5f * d, label, 0xCCFFFFFF, 0, 0);
            if (i < 3) centred(c, ":", cx + cell + 4 * d, 45 * d, 20 * d, digits, 0x99FFFFFF, 0, 0);
        }

        // A rotating one-liner along the sea.
        int idx = (int) ((now / 7000) % LINES.length);
        float phase = (now % 7000) / 7000f;
        int alpha = (int) (255 * Math.min(1, Math.min(phase, 1 - phase) * 8));
        text(c, LINES[idx], x0 + 2 * d, h - 9 * d, 9.5f * d, label, Color.argb(alpha, 255, 255, 255), 0xAA000000, 4 * d);
    }

    /** Confetti, blinking stars and "GTA VI IS OUT"; u scales it, mid is where the headline sits (fraction of h). */
    private void drawParty(Canvas c, float w, float h, long now, float t, float u, float mid) {
        for (float[] f : confetti) {
            float y = ((f[1] + t * 0.12f * f[2]) % 1.1f) * h - 6 * u;
            float x = f[0] * w + (float) Math.sin(t * 2 * f[2] + f[4]) * 8 * u;
            c.save();
            c.rotate(t * 120 * f[2] + f[4] * 57, x, y);
            p.setColor(NEON[(int) f[3]]);
            c.drawRect(x - 3 * u, y - 1.5f * u, x + 3 * u, y + 1.5f * u, p);
            c.restore();
        }
        boolean blink = (now / 400) % 2 == 0;
        float cy = h * mid;
        for (int i = 0; i < 5; i++) star(c, w / 2f - 30 * u + i * 15 * u, cy - 42 * u, 6 * u, blink ? 0xFFFFD84D : 0xFFFF3D9A, true);
        centred(c, "GTA VI IS OUT", w / 2f, cy, 30 * u, black, Color.WHITE, 0xFFFF3D9A, 12 * u);
        centred(c, "Grab the controller. Go.", w / 2f, cy + 22 * u, 11 * u, label, Color.WHITE, 0xAA000000, 4 * u);
    }

    // ---------------------------------------------------------------- drawing helpers

    private void star(Canvas c, float cx, float cy, float rr, int color, boolean glowing) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float k = i % 2 == 0 ? rr : rr * 0.45f;
            float x = cx + (float) Math.cos(a) * k, y = cy + (float) Math.sin(a) * k;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        p.setColor(color);
        if (glowing) p.setShadowLayer(rr, 0, 0, color);
        c.drawPath(path, p);
        p.clearShadowLayer();
    }

    private void text(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int glowColor, float glowR) {
        p.setTextAlign(Paint.Align.LEFT);
        draw(c, s, x, y, size, tf, color, glowColor, glowR);
    }

    private void centred(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int glowColor, float glowR) {
        p.setTextAlign(Paint.Align.CENTER);
        draw(c, s, x, y, size, tf, color, glowColor, glowR);
        p.setTextAlign(Paint.Align.LEFT);
    }

    private void draw(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int glowColor, float glowR) {
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        if (glowColor != 0) p.setShadowLayer(glowR, 0, 0, glowColor);
        c.drawText(s, x, y, p);
        p.clearShadowLayer();
    }

    private static int mix(int a, int b, float t) {
        return Color.argb(255, (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static String two(long v) { return v < 10 ? "0" + v : String.valueOf(v); }
}
