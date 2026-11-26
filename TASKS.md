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

## Faz 5B: 3. Şahıs Animasyonu ve Ses Atmosferi [Model: Neon + Hydrogen]
- [ ] 3. şahıs dürbün (`Spyglass`) kol kaldırma Mixin'inin yazılması ve ağ senkronizasyonu.
- [ ] Telsiz ses filtresi (bandpass + hafif cızırtı) ve açılış/kapanış bip seslerinin eklenmesi.