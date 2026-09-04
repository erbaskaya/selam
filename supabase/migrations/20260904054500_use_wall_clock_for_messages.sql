-- Sohbet temizlendikten hemen sonra gönderilen mesajın görünür kalmasını sağlar.
alter table public.messages
  alter column created_at set default clock_timestamp();
