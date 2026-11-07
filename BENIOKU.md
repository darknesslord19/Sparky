# Sparky Mesaj — Android TV için ekran üstü mesaj uygulaması

Film izlerken, YouTube açıkken ya da menüdeyken telefondan TV ekranına
"Sparky ile Mesaj: ..." balonu gönderir. Yazı, fotoğraf ve sesli mesaj destekler.

## 1) APK'yı oluşturma (bilgisayara bir şey kurmadan)
1. github.com'da ücretsiz hesap aç → sağ üstte **+** → **New repository** (adı: SparkyMesaj).
2. "uploading an existing file" bağlantısına tıkla, bu klasörün **içindekileri**
   (gizli `.github` klasörü dahil) sürükle bırak → **Commit changes**.
   Not: `.github` klasörü sürüklenmezse: **Add file → Create new file**, adı
   `.github/workflows/build.yml` yaz ve içine bu projedeki aynı dosyanın içeriğini yapıştır.
3. Üstteki **Actions** sekmesi → "APK Derle" çalışır (~3-4 dk).
4. Yeşil tik çıkınca çalışmaya tıkla → altta **SparkyMesaj-APK** → indir, zipten `app-debug.apk` çıkar.

Alternatif: Android Studio ile klasörü aç → Build → Build APK(s).

## 2) TV'ye kurma
- APK'yı USB belleğe at → TV'de dosya yöneticisiyle aç (bilinmeyen kaynaklara izin ver),
  ya da "Send Files to TV" / "Downloader" uygulamasıyla gönder.
- Uygulamayı aç → **Üstte gösterme izni ver** → Sparky Mesaj'ı aç.
- İzin ekranı TV'de yoksa bilgisayardan bir kere:
  `adb connect TV-IP` sonra
  `adb shell appops set com.sparky.tvmesaj SYSTEM_ALERT_WINDOW allow`

## 3) Mesaj gönderme
- Telefon TV ile aynı Wi-Fi'da olsun.
- TV ekranındaki QR kodu telefon kamerasıyla okut ya da ekranda yazan adresi (ör. `http://192.168.1.34:8080`) telefonda Chrome'da aç,
  ana ekrana ekle → artık uygulama gibi kullanırsın.
- Yazı / Resim / Sesli sekmelerinden gönder; ekranda kalma süresi ve konum (alt/orta/üst) seçilebilir.

TV yeniden açıldığında servis kendiliğinden başlar.
İpucu: Modemden TV'ye sabit IP verirsen adres hiç değişmez.
