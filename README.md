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

## Supabase kurulumu

1. `supabase/schema.sql` dosyasını Supabase projesinde çalıştırın.
2. GitHub reposunda `Settings > Secrets and variables > Actions` bölümüne şu secret'ları ekleyin:
   - `SUPABASE_URL`
   - `SUPABASE_ANON_KEY`
3. Actions içindeki **Selam APK Oluştur** iş akışını çalıştırın.

Servis rolü anahtarını APK'ya veya GitHub secret'ına eklemeyin. Yalnızca istemciler için hazırlanmış anon/publishable anahtar kullanılmalıdır.

## Sonraki sürümler

Bildirimler, fotoğraf/dosya gönderimi, grup sohbeti, çevrimiçi durumu, sesli mesaj ve arama özellikleri çekirdek mesajlaşma doğrulandıktan sonra eklenecektir.
