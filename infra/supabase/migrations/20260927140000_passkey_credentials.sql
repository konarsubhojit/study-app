-- Passkey (WebAuthn) credentials and the challenge bookkeeping that makes them replay-proof.
--
-- A passkey is added to an account that already exists: Google sign-in creates the account, and
-- registration then attaches an authenticator to it. That is why every row here points at a
-- profile rather than carrying its own identity, and why the registration challenge below records
-- which user it was issued to — a challenge minted for one account must never be redeemable by
-- another.
create table public.passkey_credentials (
  credential_id text primary key check (credential_id ~ '^[A-Za-z0-9_-]{1,512}$'),
  user_id uuid not null references public.profiles (id) on delete cascade,
  -- The COSE public key, base64url-encoded. Stored as text rather than bytea because every reader
  -- and writer of this column is the api Edge Function talking JSON over PostgREST, where bytea
  -- round-trips through a hex escape format that is easy to mangle and impossible to spot in a
  -- diff. The value is public key material, so the encoding is a transport concern only.
  public_key text not null check (public_key ~ '^[A-Za-z0-9_-]+$'),
  -- Authenticators that implement a signature counter must never present one that goes backwards;
  -- one that does not implement it reports 0 forever. Both are representable here.
  sign_count bigint not null default 0 check (sign_count >= 0),
  transports text[] not null default '{}',
  created_at timestamptz not null default now(),
  last_used_at timestamptz
);

comment on table public.passkey_credentials is
  'WebAuthn credentials bound to a profile. Only the api Edge Function service role may read or write rows.';

create index passkey_credentials_user_idx on public.passkey_credentials (user_id);

alter table public.passkey_credentials enable row level security;

-- Deliberately no policy: even the owning user must not read their own credential row through
-- PostgREST. The signature counter and public key are inputs to an authentication decision, and a
-- client that can see them learns exactly what to forge; a client that could write them owns every
-- account. All access goes through the Edge Function, which derives the owner from a verified JWT.
revoke all on public.passkey_credentials from anon, authenticated;
grant select, insert, update, delete on public.passkey_credentials to service_role;

-- A sign-in challenge and a registration challenge are both single-use nonces, but redeeming one
-- where the other was expected is an authentication bypass: a registration challenge is issued to
-- a known user, so accepting it at the sign-in endpoint would let a caller pick whose session they
-- mint. Storing the purpose alongside the hash makes the two non-interchangeable by construction.
alter table public.auth_signin_challenges
  add column purpose text not null default 'signin' check (purpose in ('signin', 'registration')),
  add column user_id uuid references public.profiles (id) on delete cascade,
  add constraint auth_signin_challenges_user_matches_purpose
    check ((purpose = 'registration') = (user_id is not null));

comment on column public.auth_signin_challenges.user_id is
  'The account a registration challenge was issued to; null for sign-in challenges, which have no user yet.';

-- Consuming a challenge is a single statement rather than a read followed by a later write: with
-- two statements, two concurrent requests both read the unconsumed row and both proceed, which is
-- precisely the replay this table exists to prevent. The update matches only rows that are still
-- unconsumed and unexpired, so exactly one caller can ever receive a row back.
create function public.consume_auth_challenge(p_challenge_hash text, p_purpose text)
returns table (challenge_hash text, user_id uuid)
language sql
volatile
set search_path = public
as $$
  update public.auth_signin_challenges
    set consumed_at = now()
  where auth_signin_challenges.challenge_hash = p_challenge_hash
    and auth_signin_challenges.purpose = p_purpose
    and auth_signin_challenges.consumed_at is null
    and auth_signin_challenges.expires_at > now()
  returning auth_signin_challenges.challenge_hash, auth_signin_challenges.user_id
$$;

revoke all on function public.consume_auth_challenge(text, text) from public, anon, authenticated;
grant execute on function public.consume_auth_challenge(text, text) to service_role;
