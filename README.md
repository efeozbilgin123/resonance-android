# Resonance Android

**Bağımsız, yerel ve çevrimiçi müzik oynatıcı — Kotlin/Jetpack Compose + Rust + Media3.**

Resonance, Spotify uygulaması yüklü olmadan ses çalabilir. Ancak **Spotify'ın tüm
ticari müzik kataloğuna erişim sağlamaz**. Spotify'ın resmî Web API'si üçüncü taraflara
tüm şarkıların ham ses akışlarını sunmaz; Spotify Free için böyle bir çözüm vaat edilmez.

## Kullanılabilir müzik kaynakları

- **Müzik (Audius):** Audius'un halka açık içeriklerinde gerçek parça arama, türlere
  göre keşif, tam parça akışı, sıra halinde oynatma, beğenilenler.
- **Radyo (Radio Browser):** Türkiye ve uluslararası canlı istasyon araması,
  pop, rock, elektronik, caz kategorileri, favoriler.
- **Yerel:** Android sistem dosya seçicisinden MP3/FLAC vb. ses dosyaları
  ekleme, metadata ve yerel favoriler.
- **İzinli URL:** HTTPS ses adresi girerek doğrudan oynatma.
- **Spotify hesabı (opsiyonel):** Spotify Web API aracılığıyla OAuth PKCE giriş,
  kütüphane ve arama. Spotify Connect kontrolleri bağımsız oynatma değildir;
  ayrıca başka bir Spotify oynatma cihazı ve yetkili hesap gerektirir.

## Mimari

- **Kotlin ve Jetpack Compose:** Android ekranları, hesap ve cihaz entegrasyonu.
- **Media3 ExoPlayer + MediaSessionService:** AAC/MP3/HLS akışları, çalma sırası,
  kilit ekranı/medya bildirimi ve Bluetooth üzerinden kontrol.
- **Rust JNI kütüphanesi:** Sıra/shuffle/repeat kararlarını hesaplayan çekirdek.
  Ses codec/çıkışı şu anda Media3 içindedir; Rust kütüphanesi oynatma
  servisinin yerine geçtiği iddiasında bulunulmaz.
- **API'ler:** https://docs.audius.co/api/ ve https://docs.radio-browser.info/

Üçüncü taraf katalogların ve yayınların sağlıklı çalışması internet bağlantısına,
servislerin erişilebilirliğine ve kaynak sahiplerinin izinlerine bağlıdır.
Bazı radyo yayınları HTTP üzerinden hizmet verir; bu sebeple canlı radyo için
Android uygulamasında cleartext trafiğine izin verilmiştir. HTTP şifreli değildir.

## APK derleme

[Bağımsız Android iş akışı](../../actions/workflows/build-independent-android.yml)
GitHub Actions üzerinde Android NDK ile Rust JNI'yi derler, Rust testlerini çalıştırır
ve Android APK'yı üretir. Başarılı çalışmanın **Artifacts** bölümündeki
`Resonance-Independent-Android-v0.4` ZIP'i açın ve `app-debug.apk` dosyasını kurun.
Bu bir **debug APK**'dır; Google Play için imzalı yayın paketi değildir.

## Yasal ve ürün sınırları

Bu ürün Spotify markasının resmî istemcisi değildir. Spotify'daki metadata
ile Audius'ta bulunan bir eseri birebir eşlemek, eşleşen içeriğin aynı yayın ya da
yasal olarak oynatılabilir olduğunu garanti etmez. Spotify Free veya Premium
hesaplarının bütün Spotify kataloğunu doğrudan bu APK'da dinleyebildiği iddia edilmez.
