-- Backend observability, alerting and cost guardrails (issue #58).
create table public.backend_observability_events (
  id bigint generated always as identity primary key,
  request_id text not null check (request_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
  operation text not null
    check (operation in ('initUpload', 'completeUpload', 'getDownloadUrl', 'delete', 'reapOrphans', 'unknown')),
  status integer not null check (status between 100 and 599),
  duration_ms integer not null check (duration_ms >= 0),
  error_code text check (error_code is null or error_code ~ '^[a-z0-9_]{1,64}$'),
  egress_bytes bigint not null default 0 check (egress_bytes >= 0),
  occurred_at timestamptz not null default now()
);

create index backend_observability_events_time_idx
  on public.backend_observability_events (occurred_at desc);
create index backend_observability_events_operation_time_idx
  on public.backend_observability_events (operation, occurred_at desc);

create table public.backend_alert_policies (
  metric text primary key,
  comparison text not null check (comparison in ('>', '>=', '<', '<=')),
  threshold numeric not null,
  window_minutes integer not null check (window_minutes > 0),
  route text not null,
  runbook_path text not null,
  created_at timestamptz not null default now()
);

insert into public.backend_alert_policies
    (metric, comparison, threshold, window_minutes, route, runbook_path)
  values
    ('error_rate', '>=', 0.02, 5, 'primary-on-call', 'docs/backend-observability.md#alert-response'),
    ('p95_latency_ms', '>=', 1000, 5, 'primary-on-call', 'docs/backend-observability.md#alert-response'),
    ('upload_success_rate', '<', 0.98, 15, 'primary-on-call', 'docs/backend-observability.md#alert-response'),
    ('storage_growth_gib_daily', '>=', 10, 1440, 'ops-review', 'docs/backend-observability.md#cost-guardrails'),
    ('egress_gib_daily', '>=', 25, 1440, 'ops-review', 'docs/backend-observability.md#cost-guardrails'),
    ('auth_failures', '>=', 50, 5, 'primary-on-call', 'docs/backend-observability.md#abuse-response-plan')
  on conflict (metric) do update set
    comparison = excluded.comparison,
    threshold = excluded.threshold,
    window_minutes = excluded.window_minutes,
    route = excluded.route,
    runbook_path = excluded.runbook_path;

create table public.backend_cost_assumptions (
  metric text primary key,
  unit text not null,
  usd_per_unit numeric not null check (usd_per_unit >= 0),
  updated_at timestamptz not null default now()
);

insert into public.backend_cost_assumptions (metric, unit, usd_per_unit)
  values
    ('storage', 'GiB-month', 0.021),
    ('egress', 'GiB', 0.09)
  on conflict (metric) do update set
    unit = excluded.unit,
    usd_per_unit = excluded.usd_per_unit,
    updated_at = now();

create table public.storage_lifecycle_rules (
  id text primary key,
  applies_to text not null,
  action text not null,
  after_interval interval not null check (after_interval > interval '0 seconds'),
  owner text not null default 'storage-provider-policy',
  enabled boolean not null default true,
  runbook_path text not null,
  created_at timestamptz not null default now()
);

insert into public.storage_lifecycle_rules
    (id, applies_to, action, after_interval, owner, runbook_path)
  values
    (
      'multipart-orphan-expiry',
      'pending and completing storage_uploads',
      'abort provider multipart upload and mark metadata failed',
      interval '24 hours',
      'reap-storage-orphans.yml',
      'docs/backend-observability.md#cost-guardrails'
    ),
    (
      'cold-tier-ready-materials',
      'ready materials objects',
      'transition storage objects to the provider cold tier',
      interval '30 days',
      'storage-provider-lifecycle',
      'docs/backend-observability.md#cost-guardrails'
    ),
    (
      'failed-or-deleted-metadata-expiry',
      'failed and deleted storage_uploads',
      'expire non-billable metadata after incident review window',
      interval '7 days',
      'database-maintenance',
      'docs/backend-observability.md#cost-guardrails'
    )
  on conflict (id) do update set
    applies_to = excluded.applies_to,
    action = excluded.action,
    after_interval = excluded.after_interval,
    owner = excluded.owner,
    enabled = excluded.enabled,
    runbook_path = excluded.runbook_path;

create or replace view public.backend_health_daily as
select
  date_trunc('day', occurred_at)::date as day,
  count(*)::bigint as request_count,
  coalesce(
    count(*) filter (where status >= 500)::numeric / nullif(count(*), 0),
    0
  ) as error_rate,
  coalesce(percentile_cont(0.95) within group (order by duration_ms), 0)::numeric as p95_latency_ms,
  coalesce(
    count(*) filter (where operation = 'completeUpload' and status between 200 and 299)::numeric /
      nullif(count(*) filter (where operation = 'completeUpload'), 0),
    1
  ) as upload_success_rate,
  coalesce(sum(egress_bytes), 0)::bigint as egress_bytes,
  count(*) filter (where error_code = 'authentication_required')::bigint as auth_failures
from public.backend_observability_events
group by 1;

create or replace view public.backend_storage_growth_daily as
select
  date_trunc('day', created_at)::date as day,
  coalesce(sum(size_bytes) filter (where state = 'ready'), 0)::bigint as ready_bytes_added,
  coalesce(sum(size_bytes) filter (where state in ('pending', 'completing')), 0)::bigint as in_flight_bytes,
  count(*) filter (where state = 'ready')::bigint as ready_object_count
from public.storage_uploads
group by 1;

create or replace view public.backend_cost_projection as
with usage_30d as (
  select
    greatest(count(distinct user_id), 1)::numeric as active_accounts,
    coalesce(sum(size_bytes) filter (where state = 'ready'), 0)::numeric as stored_bytes
  from public.storage_uploads
  where created_at >= now() - interval '30 days'
),
egress_30d as (
  select coalesce(sum(egress_bytes), 0)::numeric as egress_bytes
  from public.backend_observability_events
  where occurred_at >= now() - interval '30 days'
),
unit_costs as (
  select
    coalesce(max(usd_per_unit) filter (where metric = 'storage'), 0)::numeric as storage_usd_per_gib_month,
    coalesce(max(usd_per_unit) filter (where metric = 'egress'), 0)::numeric as egress_usd_per_gib
  from public.backend_cost_assumptions
  where metric in ('storage', 'egress')
)
select
  1000::integer as projected_active_users,
  round((stored_bytes / 1073741824.0) * (1000 / active_accounts), 3) as projected_storage_gib,
  round((egress_bytes / 1073741824.0) * (1000 / active_accounts), 3) as projected_egress_gib,
  round(
    (
      ((stored_bytes / 1073741824.0) * unit_costs.storage_usd_per_gib_month) +
      ((egress_bytes / 1073741824.0) * unit_costs.egress_usd_per_gib)
    ) * (1000 / active_accounts),
    2
  ) as projected_monthly_usd_per_1000_active_users
from usage_30d
cross join egress_30d
cross join unit_costs;

alter table public.backend_observability_events enable row level security;
alter table public.backend_alert_policies enable row level security;
alter table public.backend_cost_assumptions enable row level security;
alter table public.storage_lifecycle_rules enable row level security;

revoke all on public.backend_observability_events from anon, authenticated;
revoke all on public.backend_alert_policies from anon, authenticated;
revoke all on public.backend_cost_assumptions from anon, authenticated;
revoke all on public.storage_lifecycle_rules from anon, authenticated;
revoke all on public.backend_health_daily from anon, authenticated;
revoke all on public.backend_storage_growth_daily from anon, authenticated;
revoke all on public.backend_cost_projection from anon, authenticated;

grant insert on public.backend_observability_events to service_role;
grant select on public.backend_alert_policies to service_role;
grant select, update on public.backend_cost_assumptions to service_role;
grant select on public.storage_lifecycle_rules to service_role;
grant select on public.backend_health_daily to service_role;
grant select on public.backend_storage_growth_daily to service_role;
grant select on public.backend_cost_projection to service_role;
