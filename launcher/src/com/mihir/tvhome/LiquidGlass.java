package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple-style liquid glass panel background. Android 12 can't blur what's behind a view, and the aerial video
 * isn't even drawn by the GPU (it's on the hardware video plane), so {@link Backdrop} copies a tiny frame of the
 * video ~10 times a second and blurs it on a worker thread; each panel draws its slice of that frame slightly
 * magnified (the lens look), then a frosted tint, a top sheen and a bright specular rim. With no video playing
 * the panels fall back to plain frosted glass.
 */
final class LiquidGlass extends Drawable {
    static Backdrop backdrop;

    private final View host;
    private final float radius, density;
    private boolean lit;
    private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final Rect src = new Rect();
    private final int[] loc = new int[2];

    LiquidGlass(View host, float radius) {
        this.host = host;
        this.radius = radius;
        this.density = host.getResources().getDisplayMetrics().density;
        // Dark-mode glass: richer colour than what's behind it, but dimmed so white text stays readable
        // (the backdrop is sampled without the screen's dark scrim).
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.4f);
        ColorMatrix dim = new ColorMatrix(new float[]{
                0.6f, 0, 0, 0, 6, 0, 0.6f, 0, 0, 6, 0, 0, 0.6f, 0, 6, 0, 0, 0, 1, 0});
        cm.postConcat(dim);
        img.setColorFilter(new ColorMatrixColorFilter(cm));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.3f * density);
        if (backdrop != null) backdrop.panels.add(host);
    }

    /** Focused pills turn into bright, nearly solid glass. */
    void setLit(boolean lit) {
        if (this.lit == lit) return;
        this.lit = lit;
        invalidateSelf();
    }

    @Override protected void onBoundsChange(Rect b) {
        box.set(b);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xB3FFFFFF, 0x26FFFFFF, 0x14FFFFFF, 0x59FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas c) {
        Bitmap frame = backdrop == null ? null : backdrop.frame;
        c.save();
        c.clipPath(clip);
        if (lit) {
            fill.setColor(0xF5F4F4F8);
            c.drawRect(box, fill);
        } else {
            if (frame != null) {
                host.getLocationOnScreen(loc);
                float sx = frame.getWidth() / (float) host.getRootView().getWidth();
                float sy = frame.getHeight() / (float) host.getRootView().getHeight();
                // Sample a slightly smaller area than the panel covers: a gentle magnification, like a lens.
                float inset = 0.04f;
                float w = box.width(), h = box.height();
                src.set(Math.round((loc[0] + w * inset) * sx), Math.round((loc[1] + h * inset) * sy),
                        Math.round((loc[0] + w * (1 - inset)) * sx), Math.round((loc[1] + h * (1 - inset)) * sy));
                c.drawBitmap(frame, src, box, img);
                fill.setColor(0x14FFFFFF);
            } else {
                fill.setColor(0x40FFFFFF); // nothing to sample yet: plain frosted glass
            }
            c.drawRect(box, fill);
            c.drawRect(box, sheen);
        }
        c.restore();
        float half = rim.getStrokeWidth() / 2f;
        box.inset(half, half);
        c.drawRoundRect(box, radius - half, radius - half, rim);
        box.inset(-half, -half);
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    /** Keeps a small, blurred copy of the video's current frame for the glass panels to sample. */
    static final class Backdrop {
        private static final int W = 192, H = 108, PASSES = 3, R = 4;
        private static final long EVERY_MS = 100;

        final List<View> panels = new ArrayList<>();
        volatile Bitmap frame;

        private final SurfaceView video;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final HandlerThread thread = new HandlerThread("glass");
        private final Handler work;
        private final Bitmap grab = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        // Three output buffers so the one on screen (and the one before it) is never rewritten mid-draw.
        private final Bitmap[] out = new Bitmap[3];
        private final int[] px = new int[W * H], tmp = new int[W * H];
        private int next, failures;
        private boolean running, busy;

        Backdrop(SurfaceView video) {
            this.video = video;
            for (int i = 0; i < out.length; i++) out[i] = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            thread.start();
            work = new Handler(thread.getLooper());
        }

        void start() {
            if (running || video == null || failures > 20) return;
            running = true;
            main.post(tick);
        }

        /** Stops sampling the video; panels go back to the blurred photo (or plain frost if there is none). */
        void stop() {
            running = false;
            main.removeCallbacks(tick);
            if (frame != still) { frame = still; redraw(); }
        }

        private volatile Bitmap still;

        /** The background photo shown while no video plays: blurred once, used whenever sampling is stopped. */
        void still(Bitmap photo) {
            if (photo == null) return;
            work.post(() -> {
                Bitmap small = Bitmap.createScaledBitmap(photo, W, H, true);
                int[] p = new int[W * H], t = new int[W * H];
                small.getPixels(p, 0, W, 0, 0, W, H);
                for (int i = 0; i < PASSES; i++) {
                    box(p, t, W, H, R);
                    box(t, p, H, W, R);
                }
                Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                b.setPixels(p, 0, W, 0, 0, W, H);
                main.post(() -> {
                    still = b;
                    if (!running) { frame = b; redraw(); }
                });
            });
        }

        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                main.postDelayed(this, EVERY_MS);
                if (busy || !video.getHolder().getSurface().isValid()) return;
                busy = true;
                try {
                    PixelCopy.request(video, grab, result -> {
                        if (result == PixelCopy.SUCCESS) { failures = 0; blur(); }
                        else {
                            busy = false;
                            // Some video paths can't be copied; give up quietly and stay frosted.
                            if (++failures > 20) main.post(Backdrop.this::stop);
                        }
                    }, work);
                } catch (IllegalArgumentException e) {
                    busy = false;
                }
            }
        };

        /** Runs on the worker thread: three box-blur passes ≈ a gaussian, at 1/10 scale this costs ~1 ms. */
        private void blur() {
            grab.getPixels(px, 0, W, 0, 0, W, H);
            for (int i = 0; i < PASSES; i++) {
                box(px, tmp, W, H, R);
                box(tmp, px, H, W, R);
            }
            Bitmap b = out[next];
            next = (next + 1) % out.length;
            b.setPixels(px, 0, W, 0, 0, W, H);
            main.post(() -> {
                busy = false;
                if (!running) return;
                frame = b;
                redraw();
            });
        }

        private void redraw() {
            for (View v : panels) if (v.isShown()) v.invalidate();
        }

        /** Horizontal box blur of in (w×h) written transposed into out (h×w), so two calls blur both ways. */
        private static void box(int[] in, int[] out, int w, int h, int r) {
            float div = 2 * r + 1;
            for (int y = 0; y < h; y++) {
                int row = y * w, tr = 0, tg = 0, tb = 0;
                for (int i = -r; i <= r; i++) {
                    int p = in[row + Math.min(w - 1, Math.max(0, i))];
                    tr += (p >> 16) & 0xFF; tg += (p >> 8) & 0xFF; tb += p & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    out[x * h + y] = 0xFF000000 | (Math.round(tr / div) << 16) | (Math.round(tg / div) << 8) | Math.round(tb / div);
                    int a = in[row + Math.min(w - 1, x + r + 1)], d = in[row + Math.max(0, x - r)];
                    tr += ((a >> 16) & 0xFF) - ((d >> 16) & 0xFF);
                    tg += ((a >> 8) & 0xFF) - ((d >> 8) & 0xFF);
                    tb += (a & 0xFF) - (d & 0xFF);
                }
            }
        }
    }
}
