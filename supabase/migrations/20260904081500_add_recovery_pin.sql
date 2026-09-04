-- Selam 1.4.1 - SMS'siz 6 haneli kurtarma PIN'i ve yeniden kurulum kurtarma

alter table public.profiles
  add column if not exists recovery_pin_hash text;

create table if not exists private.account_recovery_attempts (
  requester_id uuid not null references auth.users(id) on delete cascade,
  phone_hash bytea not null,
  window_started_at timestamptz not null default clock_timestamp(),
  failed_attempts integer not null default 0 check (failed_attempts >= 0),
  primary key (requester_id, phone_hash)
);

create table if not exists private.account_recovery_target_limits (
  phone_hash bytea primary key,
  window_started_at timestamptz not null default clock_timestamp(),
  failed_attempts integer not null default 0 check (failed_attempts >= 0)
);

revoke all on private.account_recovery_attempts from public, anon, authenticated;
revoke all on private.account_recovery_target_limits from public, anon, authenticated;

insert into private.app_secrets (name, secret)
values (
  'recovery_dummy_hash',
  extensions.crypt('000000', extensions.gen_salt('bf', 12))
)
on conflict (name) do nothing;

create or replace function private.validate_recovery_pin(recovery_pin text)
returns text
language plpgsql
security definer
set search_path = ''
immutable
as $$
declare
  clean_pin text := trim(recovery_pin);
begin
  if clean_pin !~ '^[0-9]{6}$' then
    raise exception 'PIN tam 6 rakam olmalı';
  end if;
  if clean_pin in ('000000', '111111', '123456', '654321') then
    raise exception 'Daha zor bir 6 haneli PIN seçin';
  end if;
  return clean_pin;
end;
$$;
revoke all on function private.validate_recovery_pin(text) from public, anon, authenticated;

create or replace function public.setup_profile_with_pin(
  new_display_name text,
  phone_e164 text,
  recovery_pin text
)
returns table (
  username text,
  display_name text,
  phone_last4 text,
  safety_code text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  clean_name text := trim(new_display_name);
  clean_phone text := trim(phone_e164);
  clean_pin text;
  wanted_hash bytea;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if char_length(clean_name) not between 2 and 60 then
    raise exception 'Ad 2-60 karakter arasında olmalı';
  end if;
  if clean_phone !~ '^\+[1-9][0-9]{7,14}$' then
    raise exception 'Telefon numarası geçersiz';
  end if;

  clean_pin := private.validate_recovery_pin(recovery_pin);
  wanted_hash := private.phone_digest(clean_phone);

  if exists (
    select 1 from public.profiles p
    where p.phone_hash = wanted_hash and p.id <> current_user_id
  ) then
    raise exception 'Bu numara kayıtlı. Hesabımı kurtar seçeneğini kullanın';
  end if;

  update public.profiles p
  set display_name = clean_name,
      phone_hash = wanted_hash,
      phone_last4 = right(clean_phone, 4),
      recovery_pin_hash = extensions.crypt(clean_pin, extensions.gen_salt('bf', 12))
  where p.id = current_user_id;

  return query
  select p.username, p.display_name, p.phone_last4, p.safety_code
  from public.profiles p
  where p.id = current_user_id;
end;
$$;

create or replace function public.set_recovery_pin(new_pin text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  clean_pin text;
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  clean_pin := private.validate_recovery_pin(new_pin);

  update public.profiles p
  set recovery_pin_hash = extensions.crypt(clean_pin, extensions.gen_salt('bf', 12))
  where p.id = current_user_id and p.phone_hash is not null;

  if not found then raise exception 'Önce profilinizi tamamlayın'; end if;
  return true;
end;
$$;

create or replace function public.recover_profile(
  phone_e164 text,
  recovery_pin text
)
returns table (
  success boolean,
  result_message text,
  username text,
  display_name text,
  phone_last4 text,
  safety_code text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  old_user_id uuid;
  clean_phone text := trim(phone_e164);
  clean_pin text := trim(recovery_pin);
  wanted_hash bytea;
  stored_pin_hash text;
  dummy_pin_hash text;
  requester_attempts integer;
  requester_window timestamptz;
  target_attempts integer;
  target_window timestamptz;
  now_at timestamptz := clock_timestamp();
begin
  if current_user_id is null then raise exception 'Oturum gerekli'; end if;
  if clean_phone !~ '^\+[1-9][0-9]{7,14}$' or clean_pin !~ '^[0-9]{6}$' then
    return query select false, 'Numara veya PIN hatalı', null::text, null::text, null::text, null::text;
    return;
  end if;

  if not exists (
    select 1 from public.profiles p
    where p.id = current_user_id and p.phone_hash is null
  ) then
    return query select false, 'Bu cihazda zaten aktif bir Selam hesabı var', null::text, null::text, null::text, null::text;
    return;
  end if;

  if exists (select 1 from public.conversation_members m where m.user_id = current_user_id)
     or exists (select 1 from public.messages m where m.sender_id = current_user_id)
     or exists (select 1 from public.conversation_user_states s where s.user_id = current_user_id)
     or exists (select 1 from public.user_settings s where s.user_id = current_user_id)
     or exists (select 1 from public.status_updates s where s.user_id = current_user_id)
     or exists (select 1 from public.communities c where c.owner_id = current_user_id)
     or exists (select 1 from public.community_members m where m.user_id = current_user_id)
     or exists (select 1 from public.call_events c where c.caller_id = current_user_id)
     or exists (select 1 from public.conversations c where c.created_by = current_user_id) then
    return query select false, 'Boş olmayan cihaz hesabına kurtarma yapılamaz', null::text, null::text, null::text, null::text;
    return;
  end if;

  wanted_hash := private.phone_digest(clean_phone);

  insert into private.account_recovery_target_limits(phone_hash)
  values (wanted_hash)
  on conflict (phone_hash) do nothing;

  insert into private.account_recovery_attempts(requester_id, phone_hash)
  values (current_user_id, wanted_hash)
  on conflict (requester_id, phone_hash) do nothing;

  select l.failed_attempts, l.window_started_at
  into target_attempts, target_window
  from private.account_recovery_target_limits l
  where l.phone_hash = wanted_hash
  for update;

  select a.failed_attempts, a.window_started_at
  into requester_attempts, requester_window
  from private.account_recovery_attempts a
  where a.requester_id = current_user_id and a.phone_hash = wanted_hash
  for update;

  if target_window < now_at - interval '1 hour' then
    update private.account_recovery_target_limits
    set failed_attempts = 0, window_started_at = now_at
    where phone_hash = wanted_hash;
    target_attempts := 0;
  end if;

  if requester_window < now_at - interval '15 minutes' then
    update private.account_recovery_attempts
    set failed_attempts = 0, window_started_at = now_at
    where requester_id = current_user_id and phone_hash = wanted_hash;
    requester_attempts := 0;
  end if;

  if requester_attempts >= 5 or target_attempts >= 25 then
    return query select false, 'Çok fazla deneme yapıldı. Bir süre sonra yeniden deneyin', null::text, null::text, null::text, null::text;
    return;
  end if;

  select p.id, p.recovery_pin_hash
  into old_user_id, stored_pin_hash
  from public.profiles p
  where p.phone_hash = wanted_hash and p.id <> current_user_id;

  select s.secret into dummy_pin_hash
  from private.app_secrets s
  where s.name = 'recovery_dummy_hash';

  if old_user_id is null
     or stored_pin_hash is null
     or extensions.crypt(clean_pin, coalesce(stored_pin_hash, dummy_pin_hash))
        <> coalesce(stored_pin_hash, dummy_pin_hash) then
    update private.account_recovery_attempts
    set failed_attempts = failed_attempts + 1
    where requester_id = current_user_id and phone_hash = wanted_hash;
    update private.account_recovery_target_limits
    set failed_attempts = failed_attempts + 1
    where phone_hash = wanted_hash;
    return query select false, 'Numara veya PIN hatalı', null::text, null::text, null::text, null::text;
    return;
  end if;

  update public.messages set sender_id = current_user_id where sender_id = old_user_id;
  update public.conversation_members set user_id = current_user_id where user_id = old_user_id;
  update public.conversation_user_states set user_id = current_user_id where user_id = old_user_id;
  update public.user_settings set user_id = current_user_id where user_id = old_user_id;
  update public.status_updates set user_id = current_user_id where user_id = old_user_id;
  update public.communities set owner_id = current_user_id where owner_id = old_user_id;
  update public.community_members set user_id = current_user_id where user_id = old_user_id;
  update public.call_events set caller_id = current_user_id where caller_id = old_user_id;
  update public.conversations set created_by = current_user_id where created_by = old_user_id;
  update storage.objects set owner_id = current_user_id::text where owner_id = old_user_id::text;

  delete from public.profiles where id = current_user_id;
  update public.profiles set id = current_user_id where id = old_user_id;
  delete from auth.users where id = old_user_id;

  delete from private.account_recovery_attempts where requester_id = current_user_id;
  delete from private.account_recovery_target_limits where phone_hash = wanted_hash;

  return query
  select true, 'Hesabınız bu cihaza geri yüklendi', p.username,
         p.display_name, p.phone_last4, p.safety_code
  from public.profiles p
  where p.id = current_user_id;
end;
$$;

revoke all on function public.setup_profile_with_pin(text, text, text) from public, anon, authenticated;
revoke all on function public.set_recovery_pin(text) from public, anon, authenticated;
revoke all on function public.recover_profile(text, text) from public, anon, authenticated;

grant execute on function public.setup_profile_with_pin(text, text, text) to authenticated;
grant execute on function public.set_recovery_pin(text) to authenticated;
grant execute on function public.recover_profile(text, text) to authenticated;
