# TLOU2 Radio Mod — Mimari ve Geliştirme Kuralları

## 1. Teknik Yığın ve Ortam
- **Minecraft Sürümü:** 26.3
- **Mod Yükleyici:** Fabric (Fabric API)
- **Ses Altyapısı:** Simple Voice Chat API (Sunucu taraflı paket yönlendirme)
- **Animasyon & Model:** GeckoLib (`.geo.json` ve `.animation.json`)
- **Mod ID:** `tlouradio`
- **Hedef:** Sunucu entegreli, düşük TPS/CPU tüketimli, hileye kapalı taktiksel telsiz sistemi.

## 2. Temel Mekanikler
- **`X` Tuşu (Telsiz Ana Eldeyken):** Telsizin güç durumu (Açık/Kapalı) tek basışta değişir (`ToggleRadioPowerPayload`). Kapalıyken yayın yapılmaz ve kanal sesi duyulmaz.
- **Sol Tık Basılı Tutma (Bas-Konuş / PTT — Telsiz Eldeyken):** Blok kırma/vurma iptal edilir; telsiz ve anten açıkken sol tıka basılı tutulduğu sürece yayın (`RadioPttPayload(true)`) ve 1. şahıs ağıza yaklaştırma animasyonu aktif olur, sol tık bırakıldığında kapanır.
- **Sağ Tık (Telsiz Eldeyken):** Oyuncu olduğu yere sabitlenir ve istemcide 3D Cihaz İnceleme Ekranı açılır. Ekrandaki 3D telsiz modeli üzerinde `0-9`, `.`, `Sil`, `FRQ/PIN` ve `Enter` tuşları, sürüklenebilir anten, dönen frekans düğmesi ve yan PTT mandalı bulunur.
- **Ana Telsiz Kutusu (BlockEntity):** Haritaya yerleştirilen merkez ünitedir. Benzersiz `Network ID`, `Frekans` ve `Şifre` tutar. El telsizleri bu kutuya bağlanır. Kutu kırılırsa veya frekans/şifre değiştirilirse bağlı tüm telsizlerin bağlantısı anında kesilir.

## 3. Siber Güvenlik ve Ses Yönlendirme Kuralları (KRİTİK)
- **SVC Grup/Oda Sistemi YASAKTIR:** Oyuncuların SVC menüsünden veya kısayollardan kanalları görmesini/katılmasını engellemek için SVC'nin kendi Group API'si kullanılmayacak.
- **Sunucu Taraflı Otorite:** Ses yönlendirmesi yalnızca sunucuda `VoicechatServerApi` ve `MicrophonePacketEvent` üzerinden yapılacaktır.
- **Veri İzolasyonu:** Sunucu, konuşan oyuncunun telsizinin açık olduğunu, frekansını, şifresini ve bağlı olduğu Ana Kutu'nun aktifliğini doğrular. Ses paketleri yalnızca yetkili alıcılara (veya ileride eklenecek oyun içi dinleme cihazıyla yetki kazanmış casuslara) gönderilir. Yetkisiz istemcilere ses paketi asla iletilmez.
- **İstemciye Güvenme:** İstemciden gelen "frekans değiştir" veya "konuşmaya başla" paketleri sunucuda mutlaka envanter, eşya durumu ve mesafe/yetki kontrolünden geçer.

## 4. Performans Kuralları
- **Tick Döngüsü YASAKTIR:** Sunucu her tick'te tüm oyuncuları veya dünyadaki tüm Ana Telsiz Kutularını taramayacaktır.
- **Olay Tabanlı (Event-Driven) Yapı:** Aktif frekanslar ve dinleyici listeleri sunucu RAM'inde hafif bir `Map` yapısında tutulur. Harita yalnızca telsiz açılıp kapandığında, frekans değiştiğinde, eşya bırakıldığında/alındığında veya Ana Kutu kırıldığında/güncellendiğinde değişir.

## 5. Model ve Animasyon Standartları
- **3. Şahıs (Dış Görünüm):** Harici oyuncu animasyon kütüphanesi kullanılmayacak. Oyuncu PTT tuşuna bastığında hafif bir Mixin ile dürbün (`Spyglass`) tutma kol pozu uygulanacak ve ağ üzerinden yakındaki oyunculara senkronize edilecek.
- **1. Şahıs (İç Görünüm):** GeckoLib üzerinden yönetilecek (ağıza yaklaştırma, mandal basma, anten esnemesi).
- **El Telsizi Kemik İsimleri:** `root`, `body`, `antenna`, `ptt_button`, `led_indicator`, `dial_knob`
- **Ana Telsiz Kutusu Kemik İsimleri:** `root`, `base_box`, `antenna_main`, `control_panel`

## 6. Antigravity Çalışma Kuralları
- Kod yazmaya başlamadan önce değiştirilecek dosyaları ve yapılacak adımları maddeler halinde sun, kullanıcı onayından sonra uygula.
- `TASKS.md` dosyasındaki mevcut görevin kapsamı dışına çıkma.
- Her faz tamamlandığında `./gradlew build` ile derleme doğrulaması yap.