package com.sparky.tvmesaj;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;

/**
 * Mesajlari diger tum uygulamalarin (film, menu, YouTube...) ustunde gosterir.
 * Pencere odak almaz ve dokunmaya kapali oldugu icin kumandayi / filmi etkilemez.
 */
public class OverlayManager {

    private static final String TAG = "SparkyOverlay";

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Msg> queue = new ArrayDeque<>();
    private final SharedPreferences prefs;

    private View current;
    private MediaPlayer player;
    private boolean busy;

    private static class Msg {
        int kind; // 0 yazi, 1 resim, 2 ses
        String name, text, mime;
        byte[] data;
        int seconds;
        boolean sound;
    }

    public OverlayManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = this.ctx.getSharedPreferences("sparky", Context.MODE_PRIVATE);
    }

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    // ---------------- kuyruk ----------------

    public void enqueueText(String name, String text, int seconds, boolean sound) {
        Msg m = new Msg();
        m.kind = 0; m.name = name; m.text = text; m.seconds = seconds; m.sound = sound;
        post(m);
    }

    public void enqueueImage(String name, String text, byte[] data, int seconds, boolean sound) {
        Msg m = new Msg();
        m.kind = 1; m.name = name; m.text = text; m.data = data; m.seconds = seconds; m.sound = sound;
        post(m);
    }

    public void enqueueAudio(String name, String text, byte[] data, String mime, boolean sound) {
        Msg m = new Msg();
        m.kind = 2; m.name = name; m.text = text; m.data = data; m.mime = mime; m.sound = sound;
        post(m);
    }

    private void post(Msg m) {
        main.post(() -> {
            queue.add(m);
            if (!busy) next();
        });
    }

    private void next() {
        Msg m = queue.poll();
        if (m == null) {
            busy = false;
            return;
        }
        busy = true;
        if (!canDraw(ctx)) {
            Log.w(TAG, "Ustte gosterme izni yok");
            busy = false;
            queue.clear();
            return;
        }
        try {
            if (m.sound && m.kind != 2) chime();
            switch (m.kind) {
                case 1: showImage(m); break;
                case 2: showAudio(m); break;
                default: showText(m);
            }
        } catch (Exception e) {
            Log.e(TAG, "Gosterim hatasi", e);
            removeCurrent(true);
        }
    }

    public void release() {
        main.removeCallbacksAndMessages(null);
        queue.clear();
        stopPlayer();
        if (current != null) {
            try { wm.removeView(current); } catch (Exception ignored) { }
            current = null;
        }
    }

    // ---------------- gorunumler ----------------

    private void showText(Msg m) {
        View banner = buildBanner(m.name, m.text);
        addWindow(banner, false);
        long typing = typeIn(banner, banner, 450);
        hideLater(banner, typing + m.seconds * 1000L);
    }

    private void showImage(Msg m) {
        Bitmap bmp = decode(m.data, 1280);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        if (bmp != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageBitmap(bmp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setMaxHeight((int) (screenH() * 0.45f));
            iv.setMaxWidth((int) (screenW() * 0.5f));
            rounded(iv, dp(16));
            GradientDrawable frame = glass(dp(18));
            FrameLayout card = new FrameLayout(ctx);
            card.setBackground(frame);
            card.setPadding(dp(6), dp(6), dp(6), dp(6));
            card.addView(iv);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            col.addView(card, lp);
        }
        String t = TextUtils.isEmpty(m.text) ? "📷 Bir fotoğraf gönderdi" : m.text;
        View banner = buildBanner(m.name, t);
        col.addView(banner);
        addWindow(col, false);
        long typing = typeIn(col, banner, 450);
        hideLater(col, typing + m.seconds * 1000L);
    }

    private void showAudio(Msg m) {
        File f = new File(ctx.getCacheDir(), "voice_" + System.currentTimeMillis() + ext(m.mime));
        try {
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(m.data);
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Ses kaydedilemedi", e);
            removeCurrent(true);
            return;
        }

        int size = dp(300);
        FrameLayout root = new FrameLayout(ctx);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xB0405A70);
        circle.setStroke(dp(1), 0x80FFFFFF);
        root.setBackground(circle);

        final RingView ring = new RingView(ctx);
        root.addView(ring, new FrameLayout.LayoutParams(size, size));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView icon = sparkyIcon(dp(54));
        col.addView(icon);

        TextView name = label(m.name + ":", 20, true);
        name.setPadding(0, dp(6), 0, 0);
        col.addView(name);

        String sub = TextUtils.isEmpty(m.text) ? "Sesli Mesaj Oynatılıyor" : m.text;
        TextView subtitle = label(sub, 15, false);
        subtitle.setMaxWidth(dp(220));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setMaxLines(2);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(subtitle);

        BarsView bars = new BarsView(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(180), dp(70));
        blp.topMargin = dp(10);
        col.addView(bars, blp);

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(col, clp);

        addWindow(root, true, size, size);

        stopPlayer();
        player = new MediaPlayer();
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            }
            player.setDataSource(ctx, Uri.fromFile(f));
            player.setOnPreparedListener(mp -> {
                mp.start();
                tickProgress(ring);
            });
            player.setOnCompletionListener(mp -> {
                ring.setProgress(1f);
                f.delete();
                hideLater(root, 700);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                f.delete();
                removeCurrent(false);
                enqueueFront(m.name, "🔇 Sesli mesaj bu TV'de oynatılamadı");
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Oynatma hatasi", e);
            f.delete();
            removeCurrent(false);
            enqueueFront(m.name, "🔇 Sesli mesaj oynatılamadı");
        }
        // guvenlik: 3 dakikadan uzun asla kalmasin
        hideLater(root, 180000);
    }

    private void enqueueFront(String name, String text) {
        Msg e = new Msg();
        e.kind = 0; e.name = name; e.text = text; e.seconds = 6;
        queue.addFirst(e);
        busy = false;
        next();
    }

    private void tickProgress(final RingView ring) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                MediaPlayer p = player;
                if (p == null) return;
                try {
                    int d = p.getDuration();
                    if (d > 0) ring.setProgress(p.getCurrentPosition() / (float) d);
                    if (p.isPlaying()) main.postDelayed(this, 50);
                } catch (Exception ignored) {
                }
            }
        }, 50);
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
    }

    /**
     * "Sparky ile Mesaj: ...." cam gorunumlu balon.
     * Once sadece ikon gorunur, yazi typeIn() ile daktilo gibi yazilir.
     */
    private View buildBanner(String name, String text) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(glass(dp(18)));
        row.setPadding(dp(9), dp(9), dp(9), dp(9));

        TextView tv = new TextView(ctx);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        String head = name + " ile Mesaj: ";
        sb.append(head);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(text);
        tv.setTag(sb);
        tv.setText("");
        tv.setVisibility(View.GONE);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setShadowLayer(3, 0, 1, 0x55000000);
        tv.setMaxWidth((int) (screenW() * 0.70f));
        tv.setMaxLines(4);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(14);
        tlp.rightMargin = dp(14);
        row.addView(tv, tlp);
        row.setTag(tv);

        ImageView icon = sparkyIcon(dp(44));
        GradientDrawable ibg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF4F8DCC, 0xFF2F68A8});
        ibg.setCornerRadius(dp(12));
        ibg.setStroke(dp(1), 0xB3FFFFFF);
        icon.setBackground(ibg);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return row;
    }

    /**
     * Balondaki yaziyi harf harf (daktilo gibi) yazar. Balon yazi uzadikca
     * ortadan iki yana genisler. Toplam animasyon suresini ms olarak dondurur.
     */
    private long typeIn(final View owner, View banner, long startDelay) {
        final TextView tv = (TextView) banner.getTag();
        final CharSequence full = (CharSequence) tv.getTag();
        final String s = full.toString();
        final int count = s.codePointCount(0, s.length());
        final long charMs = count > 90 ? 22 : (count > 50 ? 32 : 45);
        final int[] idx = {0};

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (current != owner || idx[0] >= s.length()) return;
                idx[0] += Character.charCount(s.codePointAt(idx[0]));
                // emoji parcalarini (renk/ZWJ) tek seferde yaz
                while (idx[0] < s.length()) {
                    int cp = s.codePointAt(idx[0]);
                    if (cp == 0xFE0F || (cp >= 0x1F3FB && cp <= 0x1F3FF)) {
                        idx[0] += Character.charCount(cp);
                    } else if (cp == 0x200D) {
                        idx[0] += Character.charCount(cp);
                        if (idx[0] < s.length()) idx[0] += Character.charCount(s.codePointAt(idx[0]));
                    } else {
                        break;
                    }
                }
                tv.setVisibility(View.VISIBLE);
                tv.setText(full.subSequence(0, idx[0]));
                if (idx[0] < s.length()) main.postDelayed(this, charMs);
            }
        }, startDelay);
        return startDelay + count * charMs + 200;
    }

    private ImageView sparkyIcon(int size) {
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(R.drawable.ic_sparky);
        iv.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0x33FFFFFF);
        iv.setBackground(bg);
        iv.setPadding(dp(3), dp(3), dp(3), dp(3));
        return iv;
    }

    private TextView label(String s, int sp, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setShadowLayer(4, 0, 1, 0x66000000);
        return t;
    }

    private GradientDrawable glass(int radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD99CC0E2, 0xD96F9CCB});
        g.setCornerRadius(radius);
        g.setStroke(dp(1), 0xCCFFFFFF);
        return g;
    }

    private void rounded(View v, final int r) {
        if (Build.VERSION.SDK_INT >= 21) {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
            v.setClipToOutline(true);
        }
    }

    // ---------------- pencere ----------------

    private void addWindow(View v, boolean center) {
        addWindow(v, center, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @SuppressWarnings("deprecation")
    private void addWindow(View v, boolean center, int w, int h) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        String pos = prefs.getString("pos", "center");
        if (center || "center".equals(pos)) {
            lp.gravity = Gravity.CENTER;
        } else if ("top".equals(pos)) {
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(48);
        } else {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(56);
        }

        if (current != null) {
            try { wm.removeView(current); } catch (Exception ignored) { }
        }
        current = v;
        // once kucuk ikon kutusu "pop" diye belirir
        v.setAlpha(0f);
        v.setScaleX(0.5f);
        v.setScaleY(0.5f);
        wm.addView(v, lp);
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .setDuration(380).start();
    }

    private void removeCurrent(final boolean thenNext) {
        stopPlayer();
        final View v = current;
        current = null;
        if (v == null) {
            busy = false;
            if (thenNext) next();
            return;
        }
        if (!thenNext) {
            v.animate().cancel();
            try { wm.removeView(v); } catch (Exception ignored) { }
            busy = false;
            return;
        }
        v.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
                .setInterpolator(new AccelerateInterpolator()).setDuration(280).withEndAction(() -> {
            try { wm.removeView(v); } catch (Exception ignored) { }
            busy = false;
            next();
        }).start();
    }

    private void hideLater(final View v, long ms) {
        main.postDelayed(() -> {
            if (current == v) removeCurrent(true);
        }, ms);
    }

    // ---------------- ses efekti ----------------

    private void chime() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = u == null ? null : RingtoneManager.getRingtone(ctx, u);
            if (r != null) {
                r.play();
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 55);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180);
            main.postDelayed(tg::release, 400);
        } catch (Exception ignored) {
        }
    }

    // ---------------- yardimcilar ----------------

    private static String ext(String mime) {
        if (mime == null) return ".m4a";
        String m = mime.toLowerCase();
        if (m.contains("mpeg") || m.contains("mp3")) return ".mp3";
        if (m.contains("ogg") || m.contains("opus")) return ".ogg";
        if (m.contains("wav")) return ".wav";
        if (m.contains("webm")) return ".webm";
        if (m.contains("aac")) return ".aac";
        if (m.contains("amr")) return ".amr";
        if (m.contains("3gp")) return ".3gp";
        return ".m4a";
    }

    private static Bitmap decode(byte[] data, int max) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int s = 1;
        while (o.outWidth / s > max || o.outHeight / s > max) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private int screenW() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.widthPixels;
    }

    private int screenH() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm.heightPixels;
    }

    // ---------------- ozel ciziciler ----------------

    /** Sesli mesajda dairenin kenarindaki ilerleme halkasi */
    static class RingView extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float progress;

        RingView(Context c) {
            super(c);
            float w = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5,
                    c.getResources().getDisplayMetrics());
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(w);
            track.setColor(0x33FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(w);
            arc.setStrokeCap(Paint.Cap.ROUND);
            arc.setColor(0xFFB9D4FF);
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float pad = arc.getStrokeWidth() * 2.2f;
            r.set(pad, pad, getWidth() - pad, getHeight() - pad);
            c.drawOval(r, track);
            c.drawArc(r, -90, 360f * Math.max(progress, 0.01f), false, arc);
        }
    }

    /** Hareketli ses dalgasi cubuklari */
    static class BarsView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final long start = System.currentTimeMillis();
        private static final int N = 10;

        BarsView(Context c) {
            super(c);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            p.setShader(new LinearGradient(0, 0, w, 0, 0xFF8FE3FF, 0xFFC7A6FF, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float t = (System.currentTimeMillis() - start) / 1000f;
            float w = getWidth(), h = getHeight();
            float slot = w / N;
            float bw = slot * 0.55f;
            for (int i = 0; i < N; i++) {
                float k = (float) (0.35 + 0.65 * Math.abs(Math.sin(t * 5.2 + i * 0.75)
                        * Math.cos(t * 2.1 + i * 0.33)));
                float bh = Math.max(bw, h * k);
                float x = i * slot + (slot - bw) / 2f;
                r.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f);
                c.drawRoundRect(r, bw / 2f, bw / 2f, p);
            }
            postInvalidateOnAnimation();
        }
    }
}
