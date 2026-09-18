create extension if not exists pgcrypto;

create table if not exists public.devices (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  device_name text not null,
  device_token text unique,
  is_online boolean not null default false,
  location_permission boolean not null default false,
  camera_permission boolean not null default false,
  microphone_permission boolean not null default false,
  notification_permission boolean not null default false,
  last_seen_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.device_commands (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references public.devices(id) on delete cascade,
  requested_by uuid not null default auth.uid()
    references auth.users(id) on delete cascade,
  command text not null
    check (command in ('location', 'camera', 'microphone')),
  status text not null default 'pending'
    check (status in ('pending', 'approved', 'denied', 'completed', 'failed')),
  created_at timestamptz not null default now(),
  completed_at timestamptz
);

alter table public.device_commands
  alter column requested_by set default auth.uid();

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
using (
  exists (
    select 1 from public.devices d
    where d.id = device_id
      and d.owner_id = auth.uid()
  )
);

create policy "owners can create commands"
on public.device_commands for insert
with check (
  requested_by = auth.uid()
  and exists (
    select 1 from public.devices d
    where d.id = device_id
      and d.owner_id = auth.uid()
  )
);

create policy "owners can update commands"
on public.device_commands for update
using (
  exists (
    select 1 from public.devices d
    where d.id = device_id
      and d.owner_id = auth.uid()
  )
)
with check (
  exists (
    select 1 from public.devices d
    where d.id = device_id
      and d.owner_id = auth.uid()
  )
);

