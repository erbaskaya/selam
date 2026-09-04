-- Mesaj gönderenine göre yapılan ilişki kontrollerini hızlandırır.
create index if not exists messages_sender_id_idx
  on public.messages (sender_id);
