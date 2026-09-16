-- Per-device sync progress. One row per (user, device, entity) tracks how far that device's last
-- delta pull got, so the next pull can ask for "everything after this cursor" instead of the whole
-- table (epic 7: "delta pull with cursor").
create table public.sync_cursors (
  user_id uuid not null references public.profiles (id) on delete cascade,
  device_id text not null,
  entity_type text not null check (
    entity_type in ('subject', 'study_session', 'study_task', 'reminder', 'material', 'material_folder')
  ),
  cursor timestamptz not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, device_id, entity_type)
);

comment on table public.sync_cursors is
  'A cursor is the updated_at of the last row this device has already pulled for that entity type.';

create trigger set_sync_cursors_updated_at
  before update on public.sync_cursors
  for each row execute function public.set_updated_at();

alter table public.sync_cursors enable row level security;

create policy "sync_cursors_select_own" on public.sync_cursors
  for select using (auth.uid() = user_id);

create policy "sync_cursors_insert_own" on public.sync_cursors
  for insert with check (auth.uid() = user_id);

create policy "sync_cursors_update_own" on public.sync_cursors
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "sync_cursors_delete_own" on public.sync_cursors
  for delete using (auth.uid() = user_id);
