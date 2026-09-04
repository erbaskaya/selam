# Selam Android

Selam, bağımsız çalışan mavi temalı Android mesajlaşma uygulamasıdır. WebView kullanmaz ve ChatGPT oturumu istemez.

## Sürüm 1.3 özellikleri

- E-posta, şifre ve ücretli SMS olmadan cihaz hesabı
- Ad ve telefon numarasıyla tek adımlı kurulum
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

## Gizlilik ve güvenlik

Rehberdeki kişiler veritabanında saklanmaz. Telefon numaraları yalnızca eşleştirme isteği sırasında sunucuya gönderilir; kullanıcı profillerinde açık telefon numarası yerine sunucu sırrıyla oluşturulan özet ve son dört hane tutulur.

SMS doğrulaması kullanılmadığı için kullanıcıların yazdığı telefon numarası operatör tarafından doğrulanmış sayılmaz. QR/güvenlik kodu, konuşulan kişinin kimliğini karşılıklı kontrol etmek için kullanılır.

Cihaz hesabının oturumu uygulama verilerinde tutulur. Uygulama verileri silinirse veya telefon değiştirilirse mevcut hesaba erişim kaybolabilir; hesap taşıma/kurtarma sonraki sürüm kapsamındadır.

## Supabase bağlantısı

Uygulama Selam Supabase projesine önceden bağlanmıştır. supabase/schema.sql şeması projeye uygulanmıştır. Supabase Auth içinde **Anonymous Sign-Ins** etkin olmalıdır.

Bağlantıyı başka bir Supabase projesine taşımak gerekirse GitHub reposunda Settings > Secrets and variables > Actions bölümüne SUPABASE_URL ve SUPABASE_ANON_KEY değerleri eklenebilir.

APK oluşturmak için Actions içindeki **Selam APK Oluştur** iş akışını çalıştırın.

Servis rolü anahtarını APK'ya veya GitHub secret'ına eklemeyin. Yalnızca istemciler için hazırlanmış publishable anahtar kullanılmalıdır.

## Sonraki sürümler

Hesap taşıma/kurtarma, bildirimler, fotoğraf önizleme, çevrimiçi durumu, sesli mesaj ve sesli/görüntülü arama özellikleri sonraki sürümlere eklenebilir.
