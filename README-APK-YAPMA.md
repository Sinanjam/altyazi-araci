# Altyazı Aracı APK Projesi

Bu proje, `videocu.html` dosyasını Android WebView içinde açan hazır APK projesidir.

## Ayarlar

- Uygulama adı: `Altyazı Aracı`
- Paket adı: `com.sinanjams.app`
- Ana HTML: `app/src/main/assets/www/videocu.html`
- Dikey ekran: açık
- minSdk: 24
- targetSdk: 35
- compileSdk: 35

## Telefonda GitHub ile APK alma

1. Bu ZIP'i telefonda çıkar.
2. GitHub'da boş bir repo aç.
3. ZIP'ten çıkan bütün klasörleri repoya yükle. `.github` klasörünün de yüklendiğinden emin ol.
4. GitHub > Actions > `Build Android APK` > `Run workflow` çalıştır.
5. Build bitince `Altyazi-Araci-APK` artifact'ını indir.
6. İçinden çıkan `app-debug.apk` dosyasını telefona kur.

## Kurulum notu

Telefonda aynı paket adına sahip eski APK varsa ve imzası farklıysa "Uygulama yüklenmedi" hatası çıkabilir. Bu durumda eski uygulamayı kaldırıp yeni APK'yı kur.

## Dosya seçme / kaydetme

- Video ve görsel seçme WebView dosya seçiciyle çalışır.
- HTML'deki mevcut mantık korunmuştur.
- Mevcut `Videoyu İndir` butonu aslında canvas'taki kareyi PNG olarak kaydeder. APK içinde bu çıktı `Movies/AltyaziAraci` klasörüne kaydedilir.
