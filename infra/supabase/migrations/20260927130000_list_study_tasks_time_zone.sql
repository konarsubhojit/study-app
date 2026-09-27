-- `list_study_tasks` resolved the stored wall-clock `due_at` against its `time_zone` and returned
-- only the resulting instant, which discards the IANA zone label the client needs to recover that a
-- task is due "09:00 Europe/London". An instant alone pins the due time to whichever offset applied
-- when it was read, so a task read before a DST transition and written back after it shifts by an
-- hour — the exact drift the naive `due_at`/`time_zone` pair exists to prevent.
--
-- The projection therefore returns both: the resolved instant for sorting and display, and the zone
-- it was resolved in so the floating wall-clock meaning survives the wire.
drop function public.list_study_tasks(uuid, uuid);

create function public.list_study_tasks(p_user_id uuid, p_subject_id uuid default null)
returns table (
  id uuid,
  subject_id uuid,
  title text,
  completed_at timestamptz,
  due_at timestamptz,
  time_zone text
)
language sql
stable
set search_path = public
as $$
  select
    study_tasks.id,
    study_tasks.subject_id,
    study_tasks.title,
    study_tasks.completed_at,
    study_tasks.due_at at time zone study_tasks.time_zone,
    study_tasks.time_zone
  from public.study_tasks
  where study_tasks.user_id = p_user_id
    and study_tasks.deleted_at is null
    and study_tasks.subject_id is not null
    and (p_subject_id is null or study_tasks.subject_id = p_subject_id)
  order by study_tasks.updated_at asc, study_tasks.id asc
$$;

revoke all on function public.list_study_tasks(uuid, uuid) from public, anon, authenticated;
grant execute on function public.list_study_tasks(uuid, uuid) to service_role;
