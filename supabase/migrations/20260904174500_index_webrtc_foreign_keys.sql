-- Selam 1.5 WebRTC tablolarının yabancı anahtar sorgularını hızlandırır.
create index if not exists webrtc_calls_conversation_id_idx
  on public.webrtc_calls (conversation_id);

create index if not exists webrtc_ice_candidates_user_id_idx
  on public.webrtc_ice_candidates (user_id);
