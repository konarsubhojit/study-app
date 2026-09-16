-- Subjects/courses the user tracks time against. Mirrors `dev.studyflow.core.model.Subject`.
create table public.subjects (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  name text not null,
  color_argb integer not null,
  archived boolean not null default false,
  device_id text not null,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint subjects_name_not_blank check (btrim(name) <> '')
);

comment on column public.subjects.device_id is
  'The device that produced the last write. Paired with updated_at for last-write-wins conflicts.';
comment on column public.subjects.deleted_at is
  'Tombstone: non-null means deleted. Rows are never hard-deleted so peer devices can sync the delete.';

create index subjects_user_id_updated_at_idx on public.subjects (user_id, updated_at);

create trigger set_subjects_updated_at
  before update on public.subjects
  for each row execute function public.set_updated_at();

alter table public.subjects enable row level security;

create policy "subjects_select_own" on public.subjects
  for select using (auth.uid() = user_id);

create policy "subjects_insert_own" on public.subjects
  for insert with check (auth.uid() = user_id);

create policy "subjects_update_own" on public.subjects
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "subjects_delete_own" on public.subjects
  for delete using (auth.uid() = user_id);
