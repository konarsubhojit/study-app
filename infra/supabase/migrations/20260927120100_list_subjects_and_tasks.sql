-- The Edge Function uses its service role for this projection, so this predicate is deliberately
-- part of the function rather than relying on RLS. The caller's user id is verified by the Edge
-- Function before it is passed here.
create function public.list_study_tasks(p_user_id uuid, p_subject_id uuid default null)
returns table (
  id uuid,
  subject_id uuid,
  title text,
  completed_at timestamptz,
  due_at timestamptz
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
    study_tasks.due_at at time zone study_tasks.time_zone
  from public.study_tasks
  where study_tasks.user_id = p_user_id
    and study_tasks.deleted_at is null
    and study_tasks.subject_id is not null
    and (p_subject_id is null or study_tasks.subject_id = p_subject_id)
  order by study_tasks.updated_at asc, study_tasks.id asc
$$;

revoke all on function public.list_study_tasks(uuid, uuid) from public, anon, authenticated;
grant execute on function public.list_study_tasks(uuid, uuid) to service_role;

alter table public.backend_observability_events
  drop constraint backend_observability_events_operation_check;
alter table public.backend_observability_events
  add constraint backend_observability_events_operation_check
  check (operation in (
    'initUpload', 'completeUpload', 'getDownloadUrl', 'delete', 'reapOrphans',
    'beginSignIn', 'signIn', 'refreshTokens', 'listSubjects', 'listTasks',
    'deleteAccount', 'unknown'
  ));
