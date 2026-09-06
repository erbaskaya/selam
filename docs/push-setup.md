# Selam Android push kurulumu

FCM, uygulamanın ekranı veya kalıcı bağlantısı çalışmasa da Google Play hizmetleri üzerinden mesaj bildirimi ulaştırır. FCM ücretsizdir; mevcut Supabase planının Edge Function/veritabanı kotaları geçerlidir. Android bildirim izni gereklidir. Ayarlardan **Zorla durdur** ile kapatılan bir uygulama yeniden açılana kadar bildirim alamayabilir. İnternet, Google Play hizmetleri ve üretici pil politikaları da teslimatı etkiler.

## Hazır olan kod

- Firebase Messaging 25.1.2, soğuk başlangıçta Application kurulumu, token yenileme ve oturum açan cihaz kaydı.
- Sunucudan türetilen alıcılar; istemci başka hesabın cihaz tokenlarını listeleyemez veya gönderen işlevini çağıramaz.
- Her mesajda yüksek öncelikli FCM data bildirimi. Telefon, ek bir ağ isteği beklemeden bildirim gösterir.
- FCM'ye mesaj içeriği ve telefon numarası gönderilmez; bildirim “Selam / Yeni mesajınız var” der. Dokununca ilgili sohbet açılır.
- Aynı mesaj Realtime ve FCM ile gelse bile ortak, kalıcı kimlik kontrolü ikinci ses/bildirimi engeller. Sıra dışı teslim edilen ayrı mesajlar kaybolmaz.
- Ses/titreşim, gece sessizliği, Android kanalı ve bildirim izni dikkate alınır. Kullanıcının Android'de kapattığı kanal yeniden açılmaz.
- Açık sohbetin yeni mesaj sesi, mesaj okunmuş olsa bile sohbetin güncel sessiz ayarını sunucudan kontrol eder.
- Bildirim sesini dene, bildirim durumu ve doğrudan mesaj kanalı/ses seçimi ekranı.
- Bildirim iş kuyruğu, yeniden deneme, 90 saniyelik işlem kilidi ve kullanılamayan token temizliği.
- Çağrılan kişideki pencere arama sona erdiğinde kapanır. Kapatma isteği ekranın kapanması yüzünden iptal edilmez.

## Etkinleştirmek için gerekenler

1. Firebase Console'da bir proje ve **com.erbaskaya.selam** paket adlı Android uygulaması oluşturun. Analytics/telefonla giriş/SMS hizmeti gerekli değildir.
2. Android uygulamasının **google-services.json** dosyasını indirin. İçeriğini GitHub `erbaskaya/selam` → Settings → Secrets and variables → Actions altında **GOOGLE_SERVICES_JSON** secret'ına ekleyin. Bu dosya mobil uygulama tanımlarıdır; sunucu özel anahtarı değildir.
3. Google Cloud IAM'de yalnız Firebase Cloud Messaging API gönderme yetkili bir service account oluşturun; FCM API etkin olmalı. JSON anahtarını **yalnız Supabase Edge Functions Secrets** alanında **FIREBASE_SERVICE_ACCOUNT** adıyla saklayın. Özel anahtar GitHub kaynak dosyalarına veya APK'ya girmez.
4. `supabase/functions/selam-push/index.ts` ve `worker.mjs` dosyalarını `selam-push` Edge Function olarak dağıtın. `verify_jwt=false`: istek gövdesindeki 256 bit, işe özel ve bir gün süreli rastgele anahtar, service-role-only RPC'de doğrulanır. Anahtar olmadan alıcı/cihaz seçilemez. Worker API'leri `anon` ve `authenticated` rollerine kapalıdır.
5. Migration'ı uygulayın; pg_net ve pg_cron eklentilerini etkinleştirin. Gerçek Firebase gönderimi test edildikten sonra SQL Editor'da etkinleştirin:

```sql
insert into private.push_config(singleton,url)
values(true,'https://czaangjaxdffliwigcbx.supabase.co/functions/v1/selam-push')
on conflict(singleton) do update set url=excluded.url;
select cron.schedule('selam-push-retry','* * * * *','select private.selam_retry_push();');
```

Normal gönderim mesaj kaydedilince pg_net ile hemen başlar; dakikalık görev yalnız başarısız/kesilen gönderimleri tekrar dener. Etkinleştirme öncesinde `private.push_config` boştur: push gönderimi yapılmaz. Aynı adlı cron zaten varsa yeniden oluşturmayın. Ağ kesintilerinde anlık teslimat garantisi yoktur.

6. Yeni APK'yı mevcut imzayla derleyip iki telefona kaldırmadan kurun. Bir kez açıp oturumun ve bildirim izninin hazır olduğunu doğrulayın. Ekran kapalı/uygulama son uygulamalar listesinden kaldırılmış/Doze senaryolarında Wi-Fi ve mobil veriyle gerçek FCM testi yapın. Ses açıkken, ses kapalıyken ve sohbet sessizdeyken ayrı ayrı doğrulayın.

## Doğrulama sınırı

CI: izole Postgres yetki/teslimat testleri, HTTP gönderici mock testleri, Android ses kanalı/dedup/arama kapanma testleri ve native WebRTC bağlantı testi. Gerçek Firebase projesi/anahtarı ve fiziksel cihaz tokenı olmadan FCM'nin telefona ulaştığı doğrulanamaz. Yapılandırma eksikken bu sürüm için “uygulama kapalıyken bildirim düzeldi” denmemelidir.

Kaynaklar: https://firebase.google.com/docs/cloud-messaging/android/receive-messages , https://developer.android.com/training/monitoring-device-state/doze-standby , https://firebase.google.com/docs/cloud-messaging/send/v1-api
