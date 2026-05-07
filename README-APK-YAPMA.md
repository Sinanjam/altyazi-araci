# Altyazı Aracı APK / Release Notları

Bu repo doğrudan Android proje köküdür. GitHub Actions çalışınca imzalı release APK üretir.

## Build

GitHub'da:

Actions > Build APK > Run workflow

Çıktı artifact adı:

Altyazi-Araci-Release-APK

İçindeki APK:

Altyazi_Araci.apk

## Güncelleme mantığı

Uygulamanın paket adı değişmemelidir:

com.sinanjams.app

Kullanıcıların eski uygulamayı kaldırmadan yeni APK'yı üstüne kurabilmesi için şunlar korunmalıdır:

- Aynı package/applicationId: com.sinanjams.app
- Aynı imza dosyası: keystore/altyazi-araci-release.jks
- Her yeni sürümde daha yüksek versionCode

Bu sürümde versionCode 10, versionName 2.1.0.

Yeni sürüm yayınlarken GitHub Releases'te tag'i şu formatta kullan:

v2.1.0
v2.1.1
v2.2.0

Uygulamadaki "Güncelleme Kontrol Et" butonu şu repodaki son release'i kontrol eder:

https://github.com/Sinanjam/altyazi-araci/releases/latest

Repo adı değişirse MainActivity.java içindeki UPDATE_REPO değeri de değişmelidir.
