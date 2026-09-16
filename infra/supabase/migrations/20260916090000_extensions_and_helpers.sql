-- Shared extensions and helper functions used by every table migration that follows.
--
-- `pgcrypto` gives us `gen_random_uuid()` for server-default identifiers when a client does not
-- supply one. `moddatetime` is not used; instead we roll a tiny explicit trigger function so the
-- "last writer wins" column (`updated_at`) has one obvious owner and is easy to audit.

create extension if not exists pgcrypto with schema extensions;

-- Every syncable table stamps `updated_at` on write. The sync engine's conflict rule (epic 7) is
-- `updated_at` + `device_id` last-write-wins, so this column must always reflect the server's
-- clock, never a client-supplied value that could be forged to win a conflict.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

comment on function public.set_updated_at() is
  'Stamps updated_at with the server clock on every insert/update, independent of client input.';
