-- call_id yabancı anahtarının silme/temizleme sorgularını hızlandırır.
create index if not exists webrtc_call_history_hidden_call_id_idx
  on public.webrtc_call_history_hidden (call_id);
