-- User-created folders in the materials catalogue. Mirrors `dev.studyflow.core.model.Folder`.
create table public.material_folders (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  parent_id uuid references public.material_folders (id) on delete cascade,
  name text not null,
  device_id text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint material_folders_name_not_blank check (btrim(name) <> ''),
  constraint material_folders_not_own_parent check (id <> parent_id)
);

create index material_folders_user_id_updated_at_idx on public.material_folders (user_id, updated_at);

create trigger set_material_folders_updated_at
  before update on public.material_folders
  for each row execute function public.set_updated_at();

alter table public.material_folders enable row level security;

create policy "material_folders_select_own" on public.material_folders
  for select using (auth.uid() = user_id);

create policy "material_folders_insert_own" on public.material_folders
  for insert with check (auth.uid() = user_id);

create policy "material_folders_update_own" on public.material_folders
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "material_folders_delete_own" on public.material_folders
  for delete using (auth.uid() = user_id);

-- Uploaded file metadata. Mirrors `dev.studyflow.core.model.Material`. The object itself lives in
-- Storage under `storage_key`, which is the SHA-256 content hash (ADR 0005) — content addressing
-- means the row-level policy below is the *entire* authorisation model for materials: a user who
-- cannot select a row can never learn, let alone request a presigned URL for, another user's
-- storage key.
create table public.materials (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  folder_id uuid references public.material_folders (id) on delete set null,
  display_name text not null,
  mime_type text not null,
  size_bytes bigint not null check (size_bytes >= 0),
  content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
  storage_key text not null,
  sync_state text not null default 'PENDING' check (sync_state in ('PENDING', 'UPLOADING', 'SYNCED', 'FAILED')),
  pinned_for_offline boolean not null default false,
  encrypted boolean not null default false,
  device_id text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  constraint materials_display_name_not_blank check (btrim(display_name) <> ''),
  -- The object store key is scoped under the owner's id, so even a bug elsewhere cannot mint a
  -- presigned URL that resolves to another user's prefix (defence in depth alongside RLS).
  constraint materials_storage_key_scoped_to_owner check (storage_key = (user_id::text || '/' || content_hash))
);

create index materials_user_id_updated_at_idx on public.materials (user_id, updated_at);
create index materials_user_id_folder_id_idx on public.materials (user_id, folder_id);

create trigger set_materials_updated_at
  before update on public.materials
  for each row execute function public.set_updated_at();

alter table public.materials enable row level security;

create policy "materials_select_own" on public.materials
  for select using (auth.uid() = user_id);

create policy "materials_insert_own" on public.materials
  for insert with check (auth.uid() = user_id);

create policy "materials_update_own" on public.materials
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

create policy "materials_delete_own" on public.materials
  for delete using (auth.uid() = user_id);
