-- The table privileges the service role's RPCs need to touch a row.
--
-- Every `POST /v1/sync/sessions` failed with `permission denied for table study_sessions`. The
-- sync functions are granted `execute` to service_role, but they are not `security definer`, so
-- they run as the invoker — service_role — and that role held `execute` on the function and no
-- privilege at all on the tables underneath it. The function could be called and could not read a
-- row. The same gap is latent in `list_study_tasks` (study_tasks) and in the Edge Function's
-- direct read of `subjects`; both are reached on paths that happen to be exercised less often.
--
-- Fixed by granting the privileges rather than by marking the functions `security definer`:
-- `security definer` moves the security boundary into every function body, where it has to be
-- re-established with a pinned `search_path` for the life of the function, and a later edit that
-- loses the pin is a privilege escalation with no visible diff. Explicit grants keep the boundary
-- in one reviewable place, which is what Postgres's own hint on the failure recommends.
--
-- The trade-off is real: the design intends the vetted RPCs to be the only route to this data, and
-- a blanket grant would hand the service role the whole schema. That is why the set below is
-- minimal — table by table, verb by verb, derived by reading each function body — and why it is
-- declared as data rather than as a list of `grant` statements: the manifest is what the pgTAP
-- suite replays after stripping the service role's privileges, so a missing verb fails in CI
-- instead of in production.

create table if not exists public.service_role_table_grants (
  table_name text not null check (table_name ~ '^[a-z_][a-z0-9_]*$'),
  privilege text not null check (privilege in ('select', 'insert', 'update', 'delete')),
  reason text not null,
  primary key (table_name, privilege)
);

comment on table public.service_role_table_grants is
  'The minimal table privileges the service role needs for the functions it invokes with invoker '
  'rights, and for the tables the api Edge Function reads directly. Declared as data so the '
  'privilege set can be reviewed in one place and replayed by a test; applied by '
  'apply_service_role_table_grants().';

alter table public.service_role_table_grants enable row level security;
revoke all on public.service_role_table_grants from anon, authenticated;

-- Derived by reading each function body:
--
--   sync_push_study_sessions  study_sessions       select .. for update, insert, update
--                             study_session_events insert .. on conflict do nothing
--                             subjects             select (the owner check on a subject reference)
--   sync_pull_study_sessions  study_sessions       select
--                             study_session_events select (the aggregated event log)
--   sync_push_records         sync_records         select .. for update, insert, update
--   sync_pull_records         sync_records         select
--   list_study_tasks          study_tasks          select
--   consume_auth_challenge    auth_signin_challenges update .. returning (select, update)
--
-- No delete: sync tombstones rows rather than removing them, and a row goes only with its owner,
-- by cascade. No sequence usage: none of these tables has a serial or identity column. The
-- per-user change clocks are absent on purpose — they are written only by the `stamp_*` triggers,
-- which are `security definer` and therefore run as their owner.
insert into public.service_role_table_grants (table_name, privilege, reason) values
  ('study_sessions', 'select', 'sync_pull_study_sessions reads; sync_push_study_sessions locks the row it resolves'),
  ('study_sessions', 'insert', 'sync_push_study_sessions stores a session this server has not seen'),
  ('study_sessions', 'update', 'sync_push_study_sessions applies a winning change, and touches a row whose events grew'),
  ('study_session_events', 'select', 'sync_pull_study_sessions aggregates the replicated log'),
  ('study_session_events', 'insert', 'sync_push_study_sessions unions the incoming log in'),
  ('sync_records', 'select', 'sync_pull_records reads; sync_push_records locks the row it resolves'),
  ('sync_records', 'insert', 'sync_push_records stores a record this server has not seen'),
  ('sync_records', 'update', 'sync_push_records replaces a record a later write beat'),
  ('subjects', 'select', 'GET /v1/subjects, and the owner check on a pushed session''s subjectId'),
  ('study_tasks', 'select', 'list_study_tasks projects the caller''s tasks'),
  ('auth_signin_challenges', 'select', 'consume_auth_challenge returns the row it consumed'),
  ('auth_signin_challenges', 'update', 'consume_auth_challenge marks a challenge spent')
on conflict (table_name, privilege) do nothing;

-- Applied from the manifest rather than written out as statements, so the declaration and the
-- grants cannot drift. Invoker rights on purpose: only a role that already owns these tables can
-- grant on them, which keeps this from becoming a way to widen privileges.
create or replace function public.apply_service_role_table_grants()
returns void
language plpgsql
set search_path = public
as $$
declare
  entry record;
begin
  for entry in
    select table_name, privilege from public.service_role_table_grants order by table_name, privilege
  loop
    -- The privilege is constrained to a closed set by the table's check constraint, and the table
    -- name is quoted as an identifier, so neither can carry a statement.
    execute format('grant %s on public.%I to service_role', entry.privilege, entry.table_name);
  end loop;
end;
$$;

comment on function public.apply_service_role_table_grants() is
  'Grants service_role exactly the privileges declared in service_role_table_grants. Idempotent, '
  'and re-run by the pgTAP suite after revoking those privileges so a missing verb fails there.';

revoke all on function public.apply_service_role_table_grants() from public, anon, authenticated, service_role;

select public.apply_service_role_table_grants();
