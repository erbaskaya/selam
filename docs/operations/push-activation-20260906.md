# Selam FCM canlı etkinleştirme kaydı — 6 Eylül 2026

Supabase projesi: `czaangjaxdffliwigcbx`. Firebase projesi: `selam-507819`. Android sürümü: **1.6.4 / versionCode 19**. Bu işlem mevcut APK'yı değiştirmeden sunucu gönderimini etkinleştirir.

## Tamamlanan işlemler

- Kullanıcı `FIREBASE_SERVICE_ACCOUNT` değerini doğrudan Supabase Secrets içine kaydetti. Özel anahtar kaynak koduna veya APK'ya alınmadı.
- Canlı Edge Function içinde Google OAuth anahtar değişimi başarılı oldu. FCM HTTP v1 `validate_only: true` isteği **HTTP 200** döndürdü; test hiçbir telefona mesaj göndermedi.
- Doğrulama için kullanılan, süreli ve rastgele anahtarla korunan geçici kontrol kaldırıldı. `selam-push` sürüm **4** özgün `index.ts` ve `worker.mjs` içeriğini çalıştırıyor; iki dosyanın içeriği ayrı ayrı karşılaştırıldı.
- `pg_net` **0.20.4** ve `pg_cron` **1.6.4** etkinleştirildi.
- `private.push_config` gönderici adresi ayarlandı. `selam-push-retry` görevi `* * * * *` takvimiyle etkin.

## Canlı platform SQL kaydı

Canlı migration: **20260906203040 — enable_push_delivery_extensions**. Bu platform eklentilerinin kurulumu standart PostgreSQL CI imajında mevcut değildir; operasyon kaydı uygulama şema testlerinden ayrı tutulur. Uygulanan SQL:

```sql
create extension if not exists pg_net with schema extensions;
create extension if not exists pg_cron with schema pg_catalog;
```

Doğrulama sonrası uygulanan gönderici yapılandırması:

```sql
insert into private.push_config(singleton,url)
values(true,'https://czaangjaxdffliwigcbx.supabase.co/functions/v1/selam-push')
on conflict(singleton) do update set url=excluded.url;

select cron.schedule('selam-push-retry','* * * * *',
                     'select private.selam_retry_push();');
```

Normal mesaj gönderimi veritabanı tetikleyicisiyle hemen başlar. Dakikalık cron yalnız yeniden deneme ve kuyruk temizliği içindir; normal gönderim bu görevin çalışmasını beklemez.

## Doğrulama kanıtı

- Firebase proje eşleşmesi, hizmet hesabı JSON biçimi ve OAuth imzalama: başarılı.
- FCM yetki ve istek kontrolü: `oauth=accepted`, `fcm_status=200`, `validate_only=true`.
- Veritabanından `pg_net` ile gerçek `selam-push` adresine gönderilen, mevcut olmayan iş kimlikli istek **204** döndürdü. Ağ zaman aşımı veya hata yok; kuyruk RPC'si çağrılabildi, geçersiz iş gönderim oluşturmadı.
- `selam_queue_message_push` ve `selam_dispatch_job` tetikleyicileri etkin.
- Yeniden deneme görevinin ilk üç çalışması `succeeded` durumunda.
- Push tabloları için istemci erişimi kapalı, RLS açık. `rls_enabled_no_policy` bilgi kayıtları bu özel tablolar için bilinçli erişim reddidir: [Supabase danışman açıklaması](https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy).

## Kalan gerçek telefon kontrolü

Etkinleştirme kontrolünde **0 kayıtlı FCM cihazı** ve **0 bekleyen bildirim işi** vardı. Henüz fiziksel cihaz teslimatı doğrulanmadı.

1. İki telefonda 1.6.4'ü mevcut uygulamanın üzerine kurun; uygulamayı açıp hesabın ve Android bildirim izninin hazır olduğunu doğrulayın.
2. Ayarlar → Kişiselleştirme → Bildirim durumunu kontrol et alanında telefon kaydını kontrol edin; Bildirim sesini dene ile sesi deneyin.
3. Alıcı telefonu ana ekrana alın, ekranını kilitleyin; diğer telefondan mesaj gönderin. Ayarlar'dan Zorla durdur uygulamayın.
4. Ses açık, ses kapalı ve sohbet sessizde durumlarını ayrı ayrı kontrol edin. Gelen mesaj, uygulama açılmadan görünmelidir; ağ ve cihaz pil kısıtlamaları teslimatı etkileyebilir.

FCM `validate_only` açıklaması: https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send
