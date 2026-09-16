-- One row per authenticated user, mirroring the subset of `auth.users` the app needs to join
-- against. Keeping our own table (rather than querying `auth.users` directly from the API) means
-- application data never depends on Supabase's internal auth schema shape.
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  display_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table public.profiles is
  'One row per auth.users row. id = auth.users.id; every other user-owned table references this.';

create trigger set_profiles_updated_at
  before update on public.profiles
  for each row execute function public.set_updated_at();

alter table public.profiles enable row level security;

-- A signed-in user may only ever see or change their own profile row. There is deliberately no
-- policy that lets one user read another user's profile, even a narrowed public subset — if that
-- is ever needed it should be a conscious, separately reviewed policy, not a side effect of a
-- broad default.
create policy "profiles_select_own" on public.profiles
  for select using (auth.uid() = id);

create policy "profiles_insert_own" on public.profiles
  for insert with check (auth.uid() = id);

create policy "profiles_update_own" on public.profiles
  for update using (auth.uid() = id) with check (auth.uid() = id);

create policy "profiles_delete_own" on public.profiles
  for delete using (auth.uid() = id);

-- Supabase's GoTrue writes new users straight into `auth.users`; this trigger keeps `profiles` in
-- lockstep so application tables always have somewhere to point their foreign keys the moment a
-- user exists, without the client needing a second round trip after sign-up. It also re-fires on
-- update, so a display name changed via an identity provider (or re-synced after a merge) is kept
-- current in `profiles` rather than only being captured once at sign-up.
create or replace function public.handle_new_auth_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, new.raw_user_meta_data ->> 'display_name')
  on conflict (id) do update set display_name = excluded.display_name;
  return new;
end;
$$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_auth_user();

-- GoTrue updates auth.users on routine events too (e.g. last_sign_in_at on every login), which
-- would otherwise fire a needless profiles write on every sign-in; only re-sync when the metadata
-- this trigger actually cares about has changed.
create trigger on_auth_user_updated
  after update on auth.users
  for each row
  when (old.raw_user_meta_data is distinct from new.raw_user_meta_data)
  execute function public.handle_new_auth_user();
