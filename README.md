# Selam Android ve iPhone

Selam, bağımsız çalışan mavi temalı Android ve iPhone mesajlaşma uygulamasıdır. WebView kullanmaz ve ChatGPT oturumu istemez.

## Sürüm 1.5.4 özellikleri

- E-posta, şifre ve ücretli SMS olmadan cihaz hesabı
- Ad, telefon numarası ve 6 haneli kurtarma PIN'iyle kurulum
- Uygulama silinip yeniden kurulduğunda numara ve PIN ile sohbetleri geri yükleme
- PIN'leri açık metin saklamayan bcrypt özeti ve deneme sınırı
- Android rehberini tarayıp Selam kullanan kişileri gösterme
- Telefon numaralarını açık biçimde saklamayan anahtarlı özet eşleştirmesi
- QR kod ve güvenlik koduyla arkadaş ekleme
- Kullanıcı adıyla arama
- Birebir sohbet başlatma
- Rehberdeki Selam kullanıcılarıyla grup oluşturma
- Sohbet geçmişini yalnızca kendi hesabınızdan temizleme
- Özel depolamada 10 MB'a kadar dosya gönderme ve süreli güvenli bağlantıyla açma
- Mesaj gönderme ve otomatik yenileme
- Android durum çubuğu, çentik, sistem navigasyonu ve klavyeye uyumlu yerleşim
- Cihazda güvenli oturum saklama
- Veritabanı seviyesinde sohbet erişim kontrolü
- Mavi tasarım ve tokalaşma ikonu
- WhatsApp tarzı alt menü: Sohbetler, Güncellemeler, Topluluklar ve Aramalar
- Sohbet arama, sabitleme, arşivleme ve sessize alma
- Sağ üst üç nokta menüsü ve ayrıntılı kullanıcı ayarları
- 24 saat sonra kaybolan metin durumları
- Topluluk oluşturma ve üyelik görünümü
- Ana ekrandan kamera ile fotoğraf çekip sohbet seçerek gönderme
- Sohbet listesinin sağ altında her zaman görünen mavi “＋” yeni sohbet düğmesi
- Aynı Supabase hesabını kullanan SwiftUI iPhone istemcisi
- Harici Jitsi görüşmesi ve yanıltıcı telefonla katılma bilgileri kaldırıldı
- Uygulama açılışında yeni sürüm kontrolü, uygulama içinden indirme ve Android kurulum ekranı
- Yeni mesaj geldiğinde sesli Android bildirimi
- Jitsi kullanmadan, Selam'a özel eşler arası WebRTC internet araması
- Tek bir arama kaydını veya tüm arama geçmişini yalnızca kendi hesabından temizleme
- WebRTC bağlantı adaylarını güvenli sıraya alan ve SDP içinde yedekleyen daha sağlam internet araması
- Uygulama açıkken gelen aramalar için doğrudan Yanıtla/Reddet penceresi

## Gizlilik ve güvenlik

Rehberdeki kişiler veritabanında saklanmaz. Telefon numaraları yalnızca eşleştirme isteği sırasında sunucuya gönderilir; kullanıcı profillerinde açık telefon numarası yerine sunucu sırrıyla oluşturulan özet ve son dört hane tutulur.

SMS doğrulaması kullanılmadığı için kullanıcıların yazdığı telefon numarası operatör tarafından doğrulanmış sayılmaz. QR/güvenlik kodu, konuşulan kişinin kimliğini karşılıklı kontrol etmek için kullanılır. Kurtarma PIN'i bcrypt ile özetlenir; düz PIN veritabanına yazılmaz. Yanlış kurtarma denemeleri cihaz ve hedef numara bazında sınırlandırılır.

Cihaz hesabının oturumu uygulama verilerinde tutulur. Uygulama verileri silinir veya telefon değiştirilirse giriş ekranındaki **Hesabımı geri yükle** seçeneğiyle telefon numarası ve 6 haneli PIN kullanılır. Sürüm 1.4.0'dan yükselten mevcut kullanıcıların uygulamayı silmeden önce Ayarlar'dan kurtarma PIN'i belirlemesi gerekir.

## Supabase bağlantısı

Uygulama Selam Supabase projesine önceden bağlanmıştır. supabase/schema.sql şeması projeye uygulanmıştır. Supabase Auth içinde **Anonymous Sign-Ins** etkin olmalıdır.

Bağlantıyı başka bir Supabase projesine taşımak gerekirse GitHub reposunda Settings > Secrets and variables > Actions bölümüne SUPABASE_URL ve SUPABASE_ANON_KEY değerleri eklenebilir.

APK oluşturmak için Actions içindeki **Selam APK Oluştur** iş akışını çalıştırın.

## iPhone derlemesi

iPhone kaynakları `ios/Selam` klasöründedir. GitHub Actions içindeki **Selam iPhone Oluştur** iş akışı macOS üzerinde projeyi oluşturur, derler ve iPhone Simulator paketini indirilebilir yapar. Gerçek iPhone'a kurulabilen imzalı IPA için Apple Developer hesabına ait dağıtım sertifikası ve provisioning profile gerekir; bunlar repoya yüklenmemelidir.

Servis rolü anahtarını APK'ya veya GitHub secret'ına eklemeyin. Yalnızca istemciler için hazırlanmış publishable anahtar kullanılmalıdır.

## Sonraki sürümler

Uygulama tamamen kapalıyken anlık bildirim, tam çözünürlüklü kamera,
sesli mesaj ve mesaj yanıtlama/düzenleme/tepki sonraki geliştirme paketidir.
