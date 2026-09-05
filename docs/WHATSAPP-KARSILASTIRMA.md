# Selam 1.6.0 değerlendirmesi

Android sürümü gözden geçirildi. iPhone geliştirmesi kullanıcının isteğiyle beklemede. Bu belge WhatsApp ile tam özellik eşitliği iddiası değildir.

| Alan | Selam 1.6.0 durumu | Kapsam / sınır |
|---|---|---|
| Genel görünüm | Yeni | Sistem, açık ve koyu tema; beş balon rengi; yazı boyutu; kompakt liste |
| Sohbet kişiselleştirmesi | Yeni | Sohbete özel renk, hazır duvar kâğıdı veya galeriden fotoğraf |
| Tercihleri saklama | Yeni | Genel renk, yazı ve bildirim tercihleri hesapta; fotoğraflar ve sohbet bazlı tercihler cihazda |
| Bildirim ayarları | Genişletildi | Önizleme, ses, titreşim, 22.00–08.00 gece sessizliği, Android kanal ayarları |
| Sohbet listesi | Genişletildi | Tümü / Okunmamış / Favoriler / Gruplar, okunmamış sayısı, düzenli yenileme |
| Sabitleme / arşiv / sessize alma | Mevcut + düzeltme | Sabitle menüsündeki metin eşleştirme hatası düzeltildi |
| Mesaj yanıtlama | Yeni | Alıntı önizlemesi; asıl mesaj silinince eski içerik alıntıda gösterilmez |
| Mesaj düzenleme | Yeni | Gönderenin metin mesajları, ilk 15 dakika; düzenlendi işareti |
| Mesaj silme | Yeni | Benden sil ve gönderen için herkesten sil; karşı tarafta silindi işareti |
| Kopyalama / iletme | Yeni | Metin mesajları; hedef sohbet seçilip onaylanır |
| Yıldız / emoji tepki | Yeni | Kişiye özel yıldızlar; altı tepki, aynı tepkiye tekrar dokunarak kaldırma |
| Sohbet içi arama | Yeni | Sunucu geçmişinin tamamında; sayfalar 100 mesaj |
| Okundu / son görülme | Genişletildi | Karşılıklı gizlilik tercihleri uygulanır; grupta okundu, en az bir kişinin okuduğu anlamına gelir |
| Taslak | Yeni | Gönderilemeyen metin ve yarım bırakılan taslak aynı cihazda korunur |
| Sesli mesaj | Yeni | En fazla iki dakika kayıt, göndermeden dinleme, 1×/1,5×/2× oynatma |
| Dosya paylaşımı | Mevcut | Özel depolama, 10 MB sınırı; dosyalar cihazın görüntüleyicisinde açılır |
| Grup yönetimi | Genişletildi | Üye listesi, ad değiştirme, yönetici tarafından ekleme/çıkarma; ayrılan kurucudan sahiplik devri; 50 kişi |
| Sohbete özel not | Yeni ek kolaylık | Karşıya gönderilmeyen, sadece cihazdaki özel not |
| Metin dışa aktarma | Yeni | O anda yüklenmiş mesajları kullanıcının seçtiği dosyaya kaydeder |
| Durum / topluluk | Mevcut, sınırlı | 24 saatlik metin durumu; topluluk oluşturma ve üyelik görünümü |
| Sesli internet araması | Mevcut, geliştirme gerekiyor | Yerel WebRTC; TURN sunucusu olmadığı için bazı ağlarda bağlantı kuramayabilir |
| Uygulama tamamen kapalıyken bildirim | Eksik | Mevcut akış uygulama sürecinde sorgulama yapar; FCM/APNs tabanlı push gerekir |
| Görüntülü ve grup araması | Eksik | Medya sunucusu/TURN, yaşam döngüsü ve cihaz testleri gerekir |
| Uçtan uca şifreleme | Eksik | Mevcut HTTPS ve erişim kontrolü, uçtan uca şifreleme değildir |
| Engelleme, kaybolan mesaj, anket, konum | Eksik | Sonraki ürün ve sunucu geliştirmeleri |
| Çoklu cihaz, bulut medya yedeği | Eksik | PIN ile hesap kurtarma, eşzamanlı çoklu cihaz sistemi değildir |
| Telefon numarası sahipliği | Farklı | SMS ücreti istenmediği için operatör doğrulaması yok; yazılan numaranın sahipliği doğrulanmış sayılmaz |

## Kullanım

- **⋮ → Ayarlar → Kişiselleştirme**: genel tema ve bildirim tercihleri. Hesaba eşitlemek için Kaydet.
- **Sohbet → ⋮ → Sohbet teması ve duvar kâğıdı**: sadece o sohbetin bu cihazdaki görünümü.
- **Mesaja uzun basma**: yanıt, kopya, metin iletme, yıldız, tepki ve uygun mesajlarda düzenleme/silme.
- **Sohbet başlığına dokunma**: kişi veya grup bilgisi. Grup yöneticisinde Yönet seçeneği.
- **Yazı alanının yanındaki mikrofon**: kayıt, Durdur, Dinle veya Gönder.
- **Sohbet → ⋮ → Bu sohbete özel notum**: paylaşılmayan kişisel not.

Duvar kâğıdı fotoğrafları, özel notlar, taslaklar ve sohbete özel renkler cihazda saklanır; uygulama kaldırılırsa bunlar silinir. Hesapta eşitlenmiş genel tercihler, yıldızlar ve tepkiler PIN ile kurtarmada korunur. İndirilen bir dosya, mesajı herkesten sildikten sonra karşı cihazdan geri alınamaz.

## Karşılaştırmada kullanılan resmî kaynaklar

WhatsApp'ın [sohbet temaları açıklaması](https://blog.whatsapp.com/chat-themes-to-reflect-your-style), genel ve sohbete özel renk/duvar kâğıdı tercihlerini ve bunların yalnızca kullanıcının görünümünü değiştirdiğini anlatır. Selam'ın kişiselleştirme akışı bu kullanım biçimini izler.

WhatsApp'ın [mesaj düzenleme açıklaması](https://blog.whatsapp.com/now-you-can-edit-your-whatsapp-messages), 15 dakikalık düzenleme penceresi ve düzenlendi işaretini tanımlar. Selam'da bu sınır cihaz saatine ek olarak sunucu tarafından da uygulanır.

## Doğrulama kapsamı

GitHub Actions, üretim bağlantı bilgisi kullanmayan geçici PostgreSQL üzerinde şemayı kurar ve mesaj/grup yetkilerini, gizlilik, sayfalama, yıldız/tepki, silinen alıntı ve PIN kurtarma senaryolarını sınar. Android tarafında Robolectric, ağ yerine sahte istemci kullanarak taslak, başarısız gönderim, mesaj menüsü, koyu görünüm ve sistem çubukları yerleşimini sınar. Bu kontroller, gerçek iki telefonda ses kaydı ve arama testi yerine geçmez.
