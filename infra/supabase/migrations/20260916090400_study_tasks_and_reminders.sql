-- To-do items. Mirrors `dev.studyflow.core.model.StudyTask`.
--
-- `due_at`/`time_zone` are stored separately, matching the client model: a task due "09:00" means
-- 09:00 where the user is, not a fixed instant that drifts across a DST boundary. `due_at` is
-- deliberately `timestamp` (no time zone, unlike every other date column in this schema): it is
-- the naive local wall-clock reading from `StudyTask.dueAt` (a `kotlinx.datetime.LocalDateTime`),
-- and `time_zone` is the only thing that says which zone it means. Combining the two — rather than
-- collapsing them into one `timestamptz` up front — is what keeps the due time pinned to "09:00"
-- across a DST boundary instead of drifting by an hour.
create table public.study_tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  subject_id uuid references public.subjects (id) on delete set null,
  title text not null,
  notes text,
  due_at timestamp,
  time_zone text not null default 'UTC',
  completed_at timestamptz,
  device_id text not null,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint study_tasks_title_not_blank check (btrim(title) <> '')
);

create index study_tasks_user_id_updated_at_idx on public.study_tasks (user_id, updated_at);

create trigger set_study_tasks_updated_at
  before update on public.study_tasks
  for each row execute function public.set_updated_at();

alter table public.study_tasks enable row level security;

create policy "study_tasks_select_own" on public.study_tasks
  for select using (auth.uid() = user_id);

create policy "study_tasks_insert_own" on public.study_tasks
  for insert with check (auth.uid() = user_id);

create policy "study_tasks_update_own" on public.study_tasks
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "study_tasks_delete_own" on public.study_tasks
  for delete using (auth.uid() = user_id);

-- When and how insistently to nudge about a task. Mirrors `dev.studyflow.core.model.Reminder`;
-- `recurrence` is stored as the small JSON shape of `RecurrenceRule` rather than expanded rows,
-- for the same reason the client only ever materialises the next occurrence (see ADR 0004).
create table public.reminders (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  task_id uuid not null references public.study_tasks (id) on delete cascade,
  lead_time_seconds bigint not null default 0 check (lead_time_seconds >= 0),
  precision text not null default 'GENTLE' check (precision in ('GENTLE', 'EXACT', 'ALARM')),
  recurrence jsonb,
  device_id text not null,
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint reminders_one_per_task unique (task_id)
);

create index reminders_user_id_updated_at_idx on public.reminders (user_id, updated_at);

create trigger set_reminders_updated_at
  before update on public.reminders
  for each row execute function public.set_updated_at();

alter table public.reminders enable row level security;

create policy "reminders_select_own" on public.reminders
  for select using (auth.uid() = user_id);

create policy "reminders_insert_own" on public.reminders
  for insert with check (auth.uid() = user_id);

create policy "reminders_update_own" on public.reminders
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "reminders_delete_own" on public.reminders
  for delete using (auth.uid() = user_id);
