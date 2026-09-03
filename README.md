# Selam Android

Selam, bağımsız çalışan mavi temalı Android mesajlaşma uygulamasıdır. Uygulama artık WebView kullanmaz ve ChatGPT oturumu istemez.

## Çalışan özellikler

- E-posta ve şifreyle kayıt/giriş
- Benzersiz kullanıcı adı
- Kullanıcı arama
- Birebir sohbet başlatma
- Mesaj gönderme ve otomatik yenileme
- Güvenli oturum saklama
- Veritabanı seviyesinde sohbet erişim kontrolü
- Mavi tasarım ve tokalaşma ikonu

## Supabase bağlantısı

Uygulama `Selam` Supabase projesine önceden bağlanmıştır. `supabase/schema.sql` şeması projeye uygulanmıştır.

Bağlantıyı başka bir Supabase projesine taşımak gerekirse GitHub reposunda `Settings > Secrets and variables > Actions` bölümüne `SUPABASE_URL` ve `SUPABASE_ANON_KEY` değerleri eklenebilir. Bunlar kaynak kodundaki varsayılan bağlantıyı geçersiz kılar.

APK oluşturmak için Actions içindeki **Selam APK Oluştur** iş akışını çalıştırın.

Servis rolü anahtarını APK'ya veya GitHub secret'ına eklemeyin. Yalnızca istemciler için hazırlanmış anon/publishable anahtar kullanılmalıdır.

## Sonraki sürümler

Bildirimler, fotoğraf/dosya gönderimi, grup sohbeti, çevrimiçi durumu, sesli mesaj ve arama özellikleri çekirdek mesajlaşma doğrulandıktan sonra eklenecektir.
