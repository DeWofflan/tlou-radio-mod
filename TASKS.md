# TLOU2 Radio Mod — Görev Takibi

## Faz 1: Proje İskeleti ve Bağımlılıklar [Model: Carbon]
- [x] Fabric 26.3 proje iskeletinin ve Gradle Wrapper (`gradlew`) yapısının kurulması.
- [x] `fabric.mod.json` ve `build.gradle` içine Fabric API, Simple Voice Chat API ve GeckoLib bağımlılıklarının eklenmesi.
- [x] Temel `HandheldRadioItem` (El Telsizi) ve `RadioBaseBoxBlock` + `RadioBaseBoxBlockEntity` (Ana Telsiz Kutusu) sınıflarının yer tutucu (placeholder) dokularla kaydedilmesi.
- [x] `gradlew build` ve istemci/sunucu başlangıç testinin hatasız geçmesi.

## Faz 2: Ses Motoru ve Güvenlik Çekirdeği [Model: Carbon -> Neon]
- [x] El telsizi veri yapısının (açık/kapalı, frekans, şifre, bağlı `Network ID`) oluşturulması.
- [x] Sol tıkla telsizi açma/kapatma ve blok kırma iptalinin yazılması.
- [x] Özel PTT (Bas-Konuş) tuş ataması ve istemci-sunucu durum senkronizasyonu.
- [x] Event-Driven `RadioNetworkManager` (RAM haritası) yazılması.
- [x] `VoicechatPlugin` entegrasyonu ve sunucu taraflı güvenli ses yönlendirmesi (SVC grup sistemi olmadan).

## Faz 3: Ana Telsiz Kutusu ve Sabotaj Mekaniği [Model: Neon]
- [x] Ana Kutu'nun benzersiz `Network ID`, frekans ve şifre üretme/saklama mantığı.
- [x] El telsizinin Ana Kutu ile eşleşme mekaniği.
- [x] Ana Kutu kırıldığında veya şifre/frekans değiştiğinde bağlı tüm telsizlerin bağlantısının anında kesilmesi ve bildirim/ses tetiklenmesi.

## Faz 4: 3D Cihaz İnceleme Ekranı [Model: Neon]
- [x] Sağ tıkla oyuncunun hareketinin kilitlenmesi ve özel `RadioScreen` arayüzünün açılması.
- [x] Ekranda 3D telsiz modelinin konumlandırılması.
- [x] `0-9`, `Sil` ve `Enter` tuşlarının eklenmesi, sunucuya güvenli paketlerle frekans/şifre güncellemesi gönderilmesi.

## Faz 5A: 3D GeckoLib Modelleri, 1. Şahıs Animasyonları ve Etkileşimli İnceleme Ekranı [Model: Carbon]
- [x] Blockbench uyumlu GeckoLib 5 `.geo.json` modellerinin (`handheld_radio`, `radio_base_box`) ve 128x128 UV dokularının projeye eklenmesi.
- [x] GeckoLib 1. şahıs animasyonlarının (`.animation.json` + bilek açılış salınımı, PTT ağıza yaklaşma, teleskopik anten yaylanması) ve 1. şahıs oyuncu kolu kavramasının (`FirstPersonHandsAndItemsRendererMixin`) bağlanması.
- [x] `HandheldRadioScreen` üzerinde ±6° fare paralaksı, 3D fiziksel çökebilen tuşlar, sürüklenebilir teleskopik anten (`antennaExtended`), debounce destekli çevrilebilir `dial_knob` ve çakışma korumalı yan `ptt_button` etkileşimlerinin uygulanması.

## Faz 5B: Çoklu Frekanslı Ana Kutu, 3. Şahıs Animasyonu, Opus DSP ve Ses Atmosferi [Model: Neon + Hydrogen]
- [x] Çoklu frekanslı Ana Kutu (en fazla 8 kanal, geriye dönük NBT/SavedData uyumluluğu, O(1) frekans çakışma indeksi, otomatik boş frekans atama, kompakt kanal seçici 3D GUI), otomatik kutu eşleşmesi/şifre koruması ve kanal bazlı granüler sabotaj.
- [x] 3. şahıs dürbün (`Spyglass`) kol kaldırma Mixin'inin (`AvatarRendererMixin`) yazılması ve `SyncRadioTransmitterPayload` S2C ağ senkronizasyonu.
- [x] Sunucu taraflı Opus DSP telsiz ses filtresi (`RadioAudioDspProcessor`: 300–3000 Hz bandpass + hafif cızırtı, güvenli ham ses fallback'i, konuşmacı bazlı önbellek/temizlik).
- [x] Rate-limited `RadioReceivingSignalPayload` (150 ms) ile gelen seste mikro-titreşim ve LED/LCD RX nabız parlaması.
- [x] `ModSounds` + `sounds.json` (`radio_power_on`, `radio_power_off`, `radio_ptt_start`, `radio_ptt_end`, `radio_sabotage`) ve `tr_tr.json` / `en_us.json` yerelleştirmeleri.
- [x] Ana Kutu 3D GUI iyileştirmeleri (büyütülmüş 3D model ve kontrol paneli, tam Türkçe metinler, `BaseBoxSaveResultPayload` ile ekranı kapatmayan inline çakışma/hata uyarısı, 3D ön paneldeki 2 büyük kanal tuşu ve simgeli Kaydet + genişletilmiş `FRK/ŞFR`/`Sil` yerleşimi).
- [x] Yere atılan açık telsizlerden (`ItemEntity`) sıfır-tick `ENTITY_LOAD`/`ENTITY_UNLOAD` takibi ile güncel konumdan 16 blokluk 3D konumsal ses yayını (`LocationalSoundPacket`), yankı koruması ve 250 ms rate-limited partikül geri bildirimi.