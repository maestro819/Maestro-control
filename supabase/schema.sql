-- Maestro Control — Supabase schema (semua fitur konsep)
-- Jalankan SELURUH isi file ini sekali di Supabase SQL Editor. Aman dijalankan ulang.

create extension if not exists pgcrypto;

-- ---------- Tabel devices ----------
create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_name text not null,
  device_token text unique,
  is_online boolean not null default false,
  location_permission boolean not null default false,
  camera_permission boolean not null default false,
  microphone_permission boolean not null default false,
  notification_permission boolean not null default false,
  last_lat double precision,
  last_lng double precision,
  location_updated_at timestamptz,
  last_seen_at timestamptz,
  created_at timestamptz not null default now()
);

alter table public.devices add column if not exists last_lat double precision;
alter table public.devices add column if not exists last_lng double precision;
alter table public.devices add column if not exists location_updated_at timestamptz;
alter table public.devices alter column owner_id set default auth.uid();

-- ---------- Tabel device_commands ----------
create table if not exists public.device_commands (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  requested_by uuid not null default auth.uid()
    references auth.users(id) on delete cascade,
  command text not null
    check (command in ('location', 'camera', 'microphone', 'take_photo')),
  status text not null default 'pending'
    check (status in ('pending', 'approved', 'denied', 'processing', 'completed', 'failed')),
  params jsonb,
  result jsonb,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);

alter table public.device_commands add column if not exists params jsonb;
alter table public.device_commands add column if not exists result jsonb;

alter table public.device_commands drop constraint if exists device_commands_command_check;
alter table public.device_commands add constraint device_commands_command_check
  check (command in ('location', 'camera', 'microphone', 'take_photo'));

alter table public.device_commands drop constraint if exists device_commands_status_check;
alter table public.device_commands add constraint device_commands_status_check
  check (status in ('pending', 'approved', 'denied', 'processing', 'completed', 'failed'));

alter table public.device_commands alter column requested_by set default auth.uid();

-- ---------- Tabel device_notifications (metadata saja) ----------
create table if not exists public.device_notifications (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  package_name text,
  app_label text,
  posted_at timestamptz,
  created_at timestamptz not null default now()
);

alter table public.device_notifications enable row level security;

drop policy if exists "owners can read notifications" on public.device_notifications;
drop policy if exists "owners can insert notifications" on public.device_notifications;

create policy "owners can read notifications"
on public.device_notifications for select
using (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

create policy "owners can insert notifications"
on public.device_notifications for insert
with check (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

-- ---------- Row Level Security (devices & commands) ----------
alter table public.devices enable row level security;
alter table public.device_commands enable row level security;

drop policy if exists "owners can read devices" on public.devices;
drop policy if exists "owners can insert devices" on public.devices;
drop policy if exists "owners can update devices" on public.devices;
drop policy if exists "owners can read commands" on public.device_commands;
drop policy if exists "owners can create commands" on public.device_commands;
drop policy if exists "owners can update commands" on public.device_commands;

create policy "owners can read devices"
on public.devices for select
using (auth.uid() = owner_id);

create policy "owners can insert devices"
on public.devices for insert
with check (auth.uid() = owner_id);

create policy "owners can update devices"
on public.devices for update
using (auth.uid() = owner_id)
with check (auth.uid() = owner_id);

create policy "owners can read commands"
on public.device_commands for select
using (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

create policy "owners can create commands"
on public.device_commands for insert
with check (
  requested_by = auth.uid()
  and exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid())
);

create policy "owners can update commands"
on public.device_commands for update
using (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()))
with check (exists (select 1 from public.devices d where d.id = device_id and d.owner_id = auth.uid()));

-- ---------- Storage bucket: foto & klip suara ----------
insert into storage.buckets (id, name, public)
values ('device-photos', 'device-photos', false)
on conflict (id) do nothing;

insert into storage.buckets (id, name, public)
values ('device-clips', 'device-clips', false)
on conflict (id) do nothing;

drop policy if exists "owner upload media" on storage.objects;
create policy "owner upload media"
on storage.objects for insert to authenticated
with check (
  bucket_id in ('device-photos', 'device-clips')
  and (storage.foldername(name))[1] = auth.uid()::text
);

drop policy if exists "owner read media" on storage.objects;
create policy "owner read media"
on storage.objects for select to authenticated
using (
  bucket_id in ('device-photos', 'device-clips')
  and (storage.foldername(name))[1] = auth.uid()::text
);

-- ---------- Realtime ----------
do $$
begin
  begin alter publication supabase_realtime add table public.devices; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.device_commands; exception when duplicate_object then null; end;
  begin alter publication supabase_realtime add table public.device_notifications; exception when duplicate_object then null; end;
end $$;
