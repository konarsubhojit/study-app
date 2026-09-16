-- Completed study sessions. Mirrors `dev.studyflow.core.model.StudySession`, flattened from the
-- device's local event log — the log itself never leaves the device (ADR 0003). Per epic 7, only
-- finished sessions are ever written here: the running timer is device-scoped, so there is no
-- "in progress" row for another device to see and no conflict to resolve while a session is live.
create table public.study_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  subject_id uuid references public.subjects (id) on delete set null,
  note text,
  started_at timestamptz not null,
  ended_at timestamptz not null,
  counted_seconds numeric(12, 3) not null check (counted_seconds >= 0),
  unverified_seconds numeric(12, 3) not null default 0 check (unverified_seconds >= 0),
  device_id text not null,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint study_sessions_ended_after_started check (ended_at >= started_at)
);

comment on table public.study_sessions is
  'Only completed sessions sync (epic 7); a running or paused timer never leaves the device.';

create index study_sessions_user_id_updated_at_idx on public.study_sessions (user_id, updated_at);
create index study_sessions_user_id_subject_id_idx on public.study_sessions (user_id, subject_id);

create trigger set_study_sessions_updated_at
  before update on public.study_sessions
  for each row execute function public.set_updated_at();

alter table public.study_sessions enable row level security;

create policy "study_sessions_select_own" on public.study_sessions
  for select using (auth.uid() = user_id);

create policy "study_sessions_insert_own" on public.study_sessions
  for insert with check (auth.uid() = user_id);

create policy "study_sessions_update_own" on public.study_sessions
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "study_sessions_delete_own" on public.study_sessions
  for delete using (auth.uid() = user_id);
