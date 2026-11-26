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

## Faz 5: Model, Animasyon ve Atmosfer [Model: Neon + Hydrogen]
- [ ] Blockbench `.geo.json` modellerinin ve dokularının projeye eklenmesi.
- [ ] GeckoLib 1. şahıs animasyonlarının (`.animation.json`) bağlanması.
- [ ] 3. şahıs dürbün (`Spyglass`) kol kaldırma Mixin'inin yazılması ve ağ senkronizasyonu.
- [ ] Telsiz ses filtresi (bandpass + hafif cızırtı) ve açılış/kapanış bip seslerinin eklenmesi.