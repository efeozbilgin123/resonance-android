# Resonance Android

Kotlin + Jetpack Compose ile oluşturulmuş **yerel Android müzik oynatıcı MVP'si**.

## Özellikler
- Telefonda bulunan ses dosyalarını Android dosya seçicisiyle kütüphaneye ekleme
- Yerel dosyaları MediaPlayer ile çalma/duraklatma
- Şarkı arama, beğenilenleri işaretleme
- Koyu temalı Android arayüz

**Bu GitHub sürümü temel MVP'dir; önceki sohbetin ZIP dosyasındaki daha kapsamlı tasarımla birebir aynı değildir.** Spotify hesabına giriş/Spotify kataloğunu oynatma bu sürümde yoktur.

## APK oluşturma

[GitHub Actions](../../actions/workflows/build-android.yml) sayfasına girin. **Build Android APK** çalışması tamamlandığında, çalışma ayrıntısının en altındaki **Artifacts > resonance-android-debug-apk** arşivini indirin. Zip içindeki `app-debug.apk` dosyasını Android telefonunuza yükleyebilirsiniz.

Henüz başarılı Android derlemesi doğrulanmadı. Çalışma hata verirse Actions günlüğünü kullanarak düzeltme gerekir.
