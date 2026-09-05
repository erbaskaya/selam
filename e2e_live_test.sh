#!/usr/bin/env bash
set -euo pipefail

base_url="https://czaangjaxdffliwigcbx.supabase.co"
publishable_key="$(sed -n 's/.*sb_publishable_\([A-Za-z0-9_-]*\).*/sb_publishable_\1/p' app/build.gradle | head -1)"
tag="$(date +%s)"

fail() {
  printf '{"ok":false,"step":"%s","response":%s}\n' "$1" "$(jq -Rs . < "$2")"
  exit 1
}

signup() {
  local output_file="$1"
  local status
  status="$(curl --max-time 25 -sS -o "$output_file" -w '%{http_code}' \
    -X POST "$base_url/auth/v1/signup" \
    -H "apikey: $publishable_key" \
    -H "Authorization: Bearer $publishable_key" \
    -H 'Content-Type: application/json' \
    --data '{"data":{"e2e":"selam-live-test"},"gotrue_meta_security":{}}')"
  [[ "$status" == 2* ]] || fail signup "$output_file"
}

rpc() {
  local token="$1" function_name="$2" json_body="$3" output_file="$4"
  local status
  status="$(curl --max-time 25 -sS -o "$output_file" -w '%{http_code}' \
    -X POST "$base_url/rest/v1/rpc/$function_name" \
    -H "apikey: $publishable_key" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    --data "$json_body")"
  [[ "$status" == 2* ]] || fail "$function_name" "$output_file"
}

tmp_dir="$(mktemp -d)"
signup "$tmp_dir/a.json"
signup "$tmp_dir/b.json"
signup "$tmp_dir/c.json"

token_a="$(jq -r .access_token "$tmp_dir/a.json")"
token_b="$(jq -r .access_token "$tmp_dir/b.json")"
token_c="$(jq -r .access_token "$tmp_dir/c.json")"
user_a="$(jq -r .user.id "$tmp_dir/a.json")"
user_b="$(jq -r .user.id "$tmp_dir/b.json")"
user_c="$(jq -r .user.id "$tmp_dir/c.json")"

phone_a="+90555${tag: -7}"
rpc "$token_a" setup_profile_with_pin "$(jq -cn --arg n "E2E A $tag" --arg p "$phone_a" '{new_display_name:$n,phone_e164:$p,recovery_pin:"382749"}')" "$tmp_dir/profile_a.json"
rpc "$token_b" setup_profile_with_pin "$(jq -cn --arg n "E2E B $tag" --arg p "+90556${tag: -7}" '{new_display_name:$n,phone_e164:$p,recovery_pin:"493850"}')" "$tmp_dir/profile_b.json"

rpc "$token_a" start_direct_chat "$(jq -cn --arg other "$user_b" '{other_user_id:$other}')" "$tmp_dir/direct_chat.json"
direct_chat_id="$(jq -r . "$tmp_dir/direct_chat.json")"
rpc "$token_a" start_audio_call "$(jq -cn --arg chat "$direct_chat_id" --arg offer "v=0 s=Selam-E2E-$tag audio" '{chat_id:$chat,session_offer:$offer}')" "$tmp_dir/call_id.json"
call_id="$(jq -r . "$tmp_dir/call_id.json")"
rpc "$token_a" list_audio_call_history '{}' "$tmp_dir/calls_a_before.json"
[[ "$(jq --arg call "$call_id" '[.[] | select(.call_id == $call)] | length' "$tmp_dir/calls_a_before.json")" -eq 1 ]] || fail call_history_visible "$tmp_dir/calls_a_before.json"
rpc "$token_a" hide_audio_call_history_entry "$(jq -cn --arg call "$call_id" '{selected_call_id:$call}')" "$tmp_dir/hide_call.json"
rpc "$token_a" list_audio_call_history '{}' "$tmp_dir/calls_a_after.json"
[[ "$(jq --arg call "$call_id" '[.[] | select(.call_id == $call)] | length' "$tmp_dir/calls_a_after.json")" -eq 0 ]] || fail single_call_hidden "$tmp_dir/calls_a_after.json"
rpc "$token_b" list_audio_call_history '{}' "$tmp_dir/calls_b_before.json"
[[ "$(jq --arg call "$call_id" '[.[] | select(.call_id == $call)] | length' "$tmp_dir/calls_b_before.json")" -eq 1 ]] || fail other_party_call_preserved "$tmp_dir/calls_b_before.json"
rpc "$token_b" clear_audio_call_history '{}' "$tmp_dir/clear_calls.json"
rpc "$token_b" list_audio_call_history '{}' "$tmp_dir/calls_b_after.json"
[[ "$(jq --arg call "$call_id" '[.[] | select(.call_id == $call)] | length' "$tmp_dir/calls_b_after.json")" -eq 0 ]] || fail all_calls_cleared "$tmp_dir/calls_b_after.json"

rpc "$token_a" create_group "$(jq -cn --arg name "E2E Grup $tag" --arg member "$user_b" '{group_name:$name,member_user_ids:[$member]}')" "$tmp_dir/group.json"
group_id="$(jq -r . "$tmp_dir/group.json")"
file_path="$group_id/$user_a/${tag}_test.txt"
printf 'Selam canlı dosya testi %s' "$tag" > "$tmp_dir/payload.txt"
file_size="$(wc -c < "$tmp_dir/payload.txt" | tr -d ' ')"

upload_status="$(curl --max-time 25 -sS -o "$tmp_dir/upload.json" -w '%{http_code}' \
  -X POST "$base_url/storage/v1/object/chat-files/$file_path" \
  -H "apikey: $publishable_key" \
  -H "Authorization: Bearer $token_a" \
  -H 'Content-Type: text/plain' \
  --data-binary "@$tmp_dir/payload.txt")"
[[ "$upload_status" == 2* ]] || fail upload "$tmp_dir/upload.json"

rpc "$token_a" send_chat_file "$(jq -cn --arg chat "$group_id" --arg path "$file_path" --arg name "test.txt" --argjson size "$file_size" '{chat_id:$chat,uploaded_file_path:$path,uploaded_file_name:$name,uploaded_mime_type:"text/plain",uploaded_size_bytes:$size}')" "$tmp_dir/file_message.json"
rpc "$token_b" list_chat_messages "$(jq -cn --arg chat "$group_id" '{chat_id:$chat}')" "$tmp_dir/messages_before.json"

[[ "$(jq 'length' "$tmp_dir/messages_before.json")" -eq 1 ]] || fail file_visibility "$tmp_dir/messages_before.json"
[[ "$(jq -r '.[0].message_type' "$tmp_dir/messages_before.json")" == "file" ]] || fail file_type "$tmp_dir/messages_before.json"

sign_status="$(curl --max-time 25 -sS -o "$tmp_dir/signed.json" -w '%{http_code}' \
  -X POST "$base_url/storage/v1/object/sign/chat-files/$file_path" \
  -H "apikey: $publishable_key" \
  -H "Authorization: Bearer $token_b" \
  -H 'Content-Type: application/json' \
  --data '{"expiresIn":300}')"
[[ "$sign_status" == 2* ]] || fail signed_url "$tmp_dir/signed.json"
[[ -n "$(jq -r .signedURL "$tmp_dir/signed.json")" ]] || fail signed_url_empty "$tmp_dir/signed.json"

rpc "$token_b" delete_chat "$(jq -cn --arg chat "$group_id" '{chat_id:$chat}')" "$tmp_dir/clear.json"
rpc "$token_b" list_chat_messages "$(jq -cn --arg chat "$group_id" '{chat_id:$chat}')" "$tmp_dir/messages_cleared.json"
[[ "$(jq 'length' "$tmp_dir/messages_cleared.json")" -eq 0 ]] || fail clear_history "$tmp_dir/messages_cleared.json"

rpc "$token_a" send_chat_message "$(jq -cn --arg chat "$group_id" '{chat_id:$chat,message_body:"Temizlemeden sonraki mesaj"}')" "$tmp_dir/new_message.json"
rpc "$token_b" list_chat_messages "$(jq -cn --arg chat "$group_id" '{chat_id:$chat}')" "$tmp_dir/messages_after.json"
rpc "$token_b" list_my_chats '{}' "$tmp_dir/chats_after.json"

[[ "$(jq 'length' "$tmp_dir/messages_after.json")" -eq 1 ]] || fail new_message_visibility "$tmp_dir/messages_after.json"
[[ "$(jq -r '.[0].message_body' "$tmp_dir/messages_after.json")" == "Temizlemeden sonraki mesaj" ]] || fail new_message_body "$tmp_dir/messages_after.json"
[[ "$(jq --arg chat "$group_id" '[.[] | select(.conversation_id == $chat)] | length' "$tmp_dir/chats_after.json")" -eq 1 ]] || fail chat_reappears "$tmp_dir/chats_after.json"

delete_status="$(curl --max-time 25 -sS -o "$tmp_dir/delete_file.json" -w '%{http_code}' \
  -X DELETE "$base_url/storage/v1/object/chat-files/$file_path" \
  -H "apikey: $publishable_key" \
  -H "Authorization: Bearer $token_a")"
[[ "$delete_status" == 2* ]] || fail delete_file "$tmp_dir/delete_file.json"

rpc "$token_c" recover_profile "$(jq -cn --arg p "$phone_a" '{phone_e164:$p,recovery_pin:"999999"}')" "$tmp_dir/recover_wrong.json"
[[ "$(jq -r '.[0].success' "$tmp_dir/recover_wrong.json")" == "false" ]] || fail recovery_wrong_pin "$tmp_dir/recover_wrong.json"

rpc "$token_c" recover_profile "$(jq -cn --arg p "$phone_a" '{phone_e164:$p,recovery_pin:"382749"}')" "$tmp_dir/recover_ok.json"
[[ "$(jq -r '.[0].success' "$tmp_dir/recover_ok.json")" == "true" ]] || fail recovery_correct_pin "$tmp_dir/recover_ok.json"
rpc "$token_c" list_my_chats '{}' "$tmp_dir/recovered_chats.json"
[[ "$(jq --arg chat "$group_id" '[.[] | select(.conversation_id == $chat)] | length' "$tmp_dir/recovered_chats.json")" -eq 1 ]] || fail recovery_preserves_chats "$tmp_dir/recovered_chats.json"

jq -cn --arg old_a "$user_a" --arg recovered_a "$user_c" --arg b "$user_b" --arg group "$group_id" \
  '{ok:true,old_user_a:$old_a,recovered_user_a:$recovered_a,user_b:$b,group_id:$group,checks:["anonymous_sessions","profiles_with_pin","direct_call_history","single_call_hidden","other_party_call_preserved","all_calls_cleared","group","private_file_upload","file_message","member_signed_url","clear_for_me","new_message_reappears","wrong_pin_rejected","correct_pin_recovery","recovery_preserves_chats"]}'
