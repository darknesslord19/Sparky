package com.sparky.tvmesaj;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebView;
import android.content.pm.PackageInfo;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/** TV'de acilan kurulum ekrani: telefondan girilecek adresi ve izin durumunu gosterir. */
public class MainActivity extends Activity {

    private TextView url;
    private ImageView qr;
    private TextView status;
    private TextView help;
    private Button permBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MessageService.start(this);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F2438, 0xFF1C4E7C});
        scroll.setBackground(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.app_art);
        head.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = text("Sparky Mesaj", 36, 0xFFFFFFFF, true);
        title.setPadding(dp(16), 0, 0, 0);
        head.addView(title);
        root.addView(head);

        // QR kod + adres yan yana
        LinearLayout qrRow = new LinearLayout(this);
        qrRow.setGravity(Gravity.CENTER_VERTICAL);
        qrRow.setPadding(0, dp(24), 0, 0);

        qr = new ImageView(this);
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(0xFFFFFFFF);
        qrBg.setCornerRadius(dp(16));
        qr.setBackground(qrBg);
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        qrRow.addView(qr, new LinearLayout.LayoutParams(dp(200), dp(200)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(28), 0, 0, 0);
        TextView step = text("📱 Telefonun kamerasıyla QR kodu okut",
                22, 0xFFFFFFFF, true);
        info.addView(step);
        TextView step2 = text("Telefon bu TV ile aynı Wi-Fi'da olmalı. Okutamazsan tarayıcıya şunu yaz:",
                16, 0xFFCFE3F5, false);
        step2.setPadding(0, dp(8), 0, dp(6));
        info.addView(step2);
        url = text("", 34, 0xFF8FE3FF, true);
        info.addView(url);
        qrRow.addView(info, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(qrRow);

        status = text("", 18, 0xFFFFFFFF, false);
        status.setPadding(0, dp(18), 0, dp(12));
        root.addView(status);

        // Ikon testi: TV'nin web bileseni canli ikonu gosterebiliyor mu?
        LinearLayout test = new LinearLayout(this);
        test.setGravity(Gravity.CENTER_VERTICAL);
        test.setPadding(0, 0, 0, dp(12));
        try {
            WebView wv = new WebView(this);
            wv.getSettings().setJavaScriptEnabled(true);
            wv.getSettings().setAllowFileAccess(true);
            wv.setBackgroundColor(0x00000000);
            wv.setFocusable(false);
            wv.loadUrl("file:///android_asset/web/icon.html#speaky");
            test.addView(wv, new LinearLayout.LayoutParams(dp(96), dp(96)));
        } catch (Throwable t) {
            test.addView(text("(web bileşeni yok)", 14, 0xFFFF9A9A, false));
        }
        TextView testInfo = text("İkon testi: solda Speaky hareket ediyorsa ikonlar TV'de çalışıyor.\n" + webInfo(),
                15, 0xFFCFE3F5, false);
        testInfo.setPadding(dp(16), 0, 0, 0);
        test.addView(testInfo);
        root.addView(test);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        permBtn = button("Üstte gösterme izni ver", v -> openOverlaySettings());
        buttons.addView(permBtn);
        buttons.addView(button("Test mesajı göster", v -> {
            MessageService.sendTest(this);
            Toast.makeText(this, "Ana ekrana dönünce de görünecek", Toast.LENGTH_SHORT).show();
        }));
        buttons.addView(button("Gizle (arka planda çalışır)", v -> moveTaskToBack(true)));
        root.addView(buttons);

        help = text("", 15, 0xFFB7C9DA, false);
        help.setPadding(0, dp(18), 0, 0);
        root.addView(help);

        setContentView(scroll);
        permBtn.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String address = MessageService.serverUrl();
        url.setText(address);
        Bitmap code = makeQr(address, dp(180));
        if (code != null) qr.setImageBitmap(code);
        boolean ok = OverlayManager.canDraw(this);
        if (ok) {
            status.setText("✅ Hazır! Film izlerken de, menüdeyken de mesajlar ekranda görünecek.");
            permBtn.setText("İzin verildi ✓");
        } else {
            status.setText("⚠️ Mesajların diğer uygulamaların üstünde görünmesi için izin gerekiyor.");
            permBtn.setText("Üstte gösterme izni ver");
        }
        help.setText(
                "• TV yeniden başladığında uygulama kendiliğinden açılır.\n"
                + "• İzin ekranı TV'nizde yoksa bilgisayardan bir kere şu komutu çalıştırın:\n"
                + "   adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n"
                + "• Adres değişirse (modem yeniden başlarsa) bu ekranı açıp yeni adresi görebilirsiniz.\n"
                + "• Speaky maskot animasyonları: github.com/bedriyan/speaky (MIT lisansı).");
    }

    private String webInfo() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null) return "Web bileşeni: " + pi.packageName + " " + pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        return "Android " + Build.VERSION.RELEASE;
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "Bu TV'de izin gerekmiyor", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Bu TV'de izin ekranı yok, aşağıdaki adb komutunu kullanın",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static Bitmap makeQr(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x, y) ? 0xFF0B2540 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        btn.setPadding(dp(22), dp(12), dp(22), dp(12));
        btn.setFocusable(true);

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(dp(28));
        normal.setColor(0x33FFFFFF);
        normal.setStroke(dp(1), 0x66FFFFFF);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(28));
        focused.setColor(0xFF2E8BD6);
        focused.setStroke(dp(3), 0xFFFFFFFF);

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{android.R.attr.state_pressed}, focused);
        sl.addState(new int[]{}, normal);
        btn.setBackground(sl);

        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), dp(4), dp(8), dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
