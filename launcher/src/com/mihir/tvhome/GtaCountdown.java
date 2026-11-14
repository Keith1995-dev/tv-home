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

import java.util.Random;

/**
 * The home screen's GTA VI countdown: a neon beach sunset (retro sun, swaying palms, a shimmering sea) with the
 * days, hours, minutes and seconds to the unlock, a wanted level that climbs as the day gets closer, and a rotating
 * one-liner. At zero it throws confetti for a few days, then hides itself. All drawn here, no game artwork.
 * The moment comes from config.xml (gta6_unlock, an ISO instant).
 */
final class GtaCountdown extends View {
    private static final String[] LINES = {
            "Clear your schedule.",
            "Charge the controller. Both of them.",
            "Tell work you're sick on the 19th.",
            "Pre-load ready? Check the PS5.",
            "Sunglasses on. Sun's not down yet.",
            "The beach is calling.",
            "Snacks stocked? Asking for a friend.",
            "Twelve years of waiting. Almost there.",
    };
    private static final long DAY = 86_400_000L;
    private static final float W = 500, H = 104, HORIZON = 0.78f;

    private final long unlock;
    private final float d;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Typeface black = Typeface.create("sans-serif-black", Typeface.ITALIC);
    private final Typeface digits = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface label = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final float[][] confetti = new float[46][5];
    private Shader sky, sun, sea;
    private boolean running;

    GtaCountdown(Context c, long unlock) {
        super(c);
        this.unlock = unlock;
        d = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        Random rnd = new Random(19);
        for (float[] f : confetti) { f[0] = rnd.nextFloat(); f[1] = rnd.nextFloat(); f[2] = 0.4f + rnd.nextFloat(); f[3] = rnd.nextInt(4); f[4] = rnd.nextFloat() * 6.28f; }
    }

    /** Shown until three days after the unlock. */
    static boolean relevant(long unlock) {
        return unlock > 0 && System.currentTimeMillis() < unlock + 3 * DAY;
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); running = true; invalidate(); }
    @Override protected void onDetachedFromWindow() { running = false; super.onDetachedFromWindow(); }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        running = visible;
        if (visible) invalidate();
    }

    @Override
    protected void onSizeChanged(int vw, int vh, int ow, int oh) {
        float w = W * d, h = H * d, horizon = h * HORIZON;
        sky = new LinearGradient(0, 0, 0, horizon, new int[]{0xFF1B0B3A, 0xFF5A1470, 0xFFE0337E, 0xFFFF8A45},
                new float[]{0f, 0.38f, 0.78f, 1f}, Shader.TileMode.CLAMP);
        float sunR = h * 0.36f;
        sun = new LinearGradient(0, horizon - sunR, 0, horizon, 0xFFFFE27A, 0xFFFF4F8B, Shader.TileMode.CLAMP);
        sea = new LinearGradient(0, horizon, 0, h, 0xFF2A0F55, 0xFF0B1630, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        // Drawn on a 500 x 104 dp design canvas, scaled to whatever size the card is given.
        float k = getWidth() / (W * d);
        int w = (int) (W * d), h = (int) (H * d);
        long now = System.currentTimeMillis();
        float t = (now % 600_000L) / 1000f;
        float rad = 18 * d, horizon = h * HORIZON;

        c.save();
        c.scale(k, k);
        c.save();
        r.set(0, 0, w, h);
        path.reset();
        path.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(path);

        // Sky, a striped retro sun sinking into the sea, and the sea's shimmer.
        p.setShader(sky);
        c.drawRect(0, 0, w, horizon, p);
        float sunR = h * 0.36f, sunX = w * 0.89f;
        p.setShader(sun);
        c.drawCircle(sunX, horizon, sunR, p);
        p.setShader(null);
        p.setColor(0xFFFF8A45);
        for (int i = 0; i < 4; i++) { // the classic gaps across the lower sun, drifting down
            float y = horizon - sunR * 0.55f + i * sunR * 0.16f + (t * 4 * d) % (sunR * 0.16f);
            c.drawRect(sunX - sunR, y, sunX + sunR, y + (1.5f + i) * d, p);
        }
        p.setShader(sea);
        c.drawRect(0, horizon, w, h, p);
        p.setShader(null);
        for (int i = 0; i < 7; i++) {
            float y = horizon + (h - horizon) * (0.15f + i * 0.13f);
            float len = sunR * (1.3f - i * 0.12f) * (0.8f + 0.2f * (float) Math.sin(t * 1.7 + i));
            p.setColor(Color.argb(150 - i * 16, 255, 170, 120));
            c.drawRect(sunX - len / 2, y, sunX + len / 2, y + 1.6f * d, p);
        }

        // Palms on both edges, fronds swaying.
        palm(c, w * 0.035f, h, h * 0.92f, -0.10f, t, 0);
        palm(c, w * 0.975f, h, h * 0.78f, 0.12f, t, 1.3f);
        palm(c, w * 0.93f, h, h * 0.58f, 0.04f, t, 2.1f);

        long left = unlock - now;
        if (left > 0) drawCountdown(c, w, h, left, t);
        else drawParty(c, w, h, now, t);
        c.restore();

        // A thin neon rim.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * d);
        p.setShader(new LinearGradient(0, 0, w, h, 0xCCFF4FB0, 0xCC34E1FF, Shader.TileMode.CLAMP));
        r.set(d, d, w - d, h - d);
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        c.restore();

        if (running) postInvalidateDelayed(left > 0 ? 50 : 33);
    }

    private void drawCountdown(Canvas c, int w, int h, long left, float t) {
        float x0 = 26 * d;
        // Title: GTA VI with a pink neon glow, and when.
        text(c, "GTA VI", x0, 38 * d, 28 * d, black, Color.WHITE, 0xFFFF3D9A, 10 * d);
        text(c, "UNLOCKS IN INDIA  ·  THU 19 NOV", x0 + 2 * d, 54 * d, 8.5f * d, label, 0xE6FFFFFF, 0, 0);

        // Wanted level: a star a day for the last five days, the next one blinking.
        int lit = (int) Math.max(0, Math.min(5, 5 - Math.ceil(left / (double) DAY) + 1));
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        for (int i = 0; i < 5; i++) {
            boolean on = i < lit || (i == lit && blink);
            star(c, x0 + 6 * d + i * 15 * d, 70 * d, 5.5f * d, on ? 0xFFFFD84D : 0x55FFFFFF, on && i < lit);
        }

        // DD : HH : MM : SS
        long s = left / 1000;
        String[] v = {String.valueOf(s / 86400), two(s / 3600 % 24), two(s / 60 % 60), two(s % 60)};
        String[] lab = {"DAYS", "HRS", "MIN", "SEC"};
        float cell = 46 * d, gx = w * 0.34f, base = 49 * d, size = 30 * d;
        for (int i = 0; i < 4; i++) {
            float cx = gx + i * (cell + 8 * d);
            r.set(cx, 16 * d, cx + cell, 62 * d);
            p.setColor(0x6614072E);
            c.drawRoundRect(r, 10 * d, 10 * d, p);
            p.setColor(0x3329E8FF);
            c.drawRect(cx + 4 * d, 39 * d, cx + cell - 4 * d, 39 * d + Math.max(1, d), p); // the flip line
            float pulse = i == 3 ? 0.6f + 0.4f * (float) Math.cos((System.currentTimeMillis() % 1000) / 1000f * 6.28f) : 1f;
            centred(c, v[i], cx + cell / 2, base, size, digits, Color.WHITE, Color.argb((int) (255 * pulse), 0x34, 0xE1, 0xFF), 9 * d);
            centred(c, lab[i], cx + cell / 2, 74 * d, 7.5f * d, label, 0xCCFFFFFF, 0, 0);
            if (i < 3) centred(c, ":", cx + cell + 4 * d, 45 * d, 20 * d, digits, 0x99FFFFFF, 0, 0);
        }

        // A rotating one-liner along the bottom.
        int idx = (int) ((System.currentTimeMillis() / 7000) % LINES.length);
        float phase = (System.currentTimeMillis() % 7000) / 7000f;
        int alpha = (int) (255 * Math.min(1, Math.min(phase, 1 - phase) * 8));
        text(c, LINES[idx], x0 + 2 * d, h - 9 * d, 9.5f * d, label, Color.argb(alpha, 255, 255, 255), 0xAA000000, 4 * d);
    }

    private void drawParty(Canvas c, int w, int h, long now, float t) {
        int[] cols = {0xFFFF3D9A, 0xFF34E1FF, 0xFFFFD84D, 0xFF7CFF6B};
        for (float[] f : confetti) {
            float y = ((f[1] + t * 0.12f * f[2]) % 1.1f) * h - 6 * d;
            float x = f[0] * w + (float) Math.sin(t * 2 * f[2] + f[4]) * 8 * d;
            c.save();
            c.rotate(t * 120 * f[2] + f[4] * 57, x, y);
            p.setColor(cols[(int) f[3]]);
            c.drawRect(x - 3 * d, y - 1.5f * d, x + 3 * d, y + 1.5f * d, p);
            c.restore();
        }
        boolean blink = (now / 400) % 2 == 0;
        for (int i = 0; i < 5; i++) star(c, w / 2f - 30 * d + i * 15 * d, 22 * d, 6 * d, blink ? 0xFFFFD84D : 0xFFFF3D9A, true);
        centred(c, "GTA VI IS OUT", w / 2f, 64 * d, 30 * d, black, Color.WHITE, 0xFFFF3D9A, 12 * d);
        centred(c, "Grab the controller. Go.", w / 2f, 86 * d, 11 * d, label, Color.WHITE, 0xAA000000, 4 * d);
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
        if (glowing) p.setShadowLayer(6 * d, 0, 0, color);
        c.drawPath(path, p);
        p.clearShadowLayer();
    }

    private void text(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int glowColor, float glowR) {
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(color);
        if (glowColor != 0) p.setShadowLayer(glowR, 0, 0, glowColor);
        c.drawText(s, x, y, p);
        p.clearShadowLayer();
    }

    private void centred(Canvas c, String s, float x, float y, float size, Typeface tf, int color, int glowColor, float glowR) {
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        if (glowColor != 0) p.setShadowLayer(glowR, 0, 0, glowColor);
        c.drawText(s, x, y, p);
        p.clearShadowLayer();
        p.setTextAlign(Paint.Align.LEFT);
    }

    private static String two(long v) { return v < 10 ? "0" + v : String.valueOf(v); }
}
