import {
    AbortMultipartUploadCommand,
    DeleteObjectCommand,
    ListMultipartUploadsCommand,
    ListObjectsV2Command,
    S3Client,
} from "npm:@aws-sdk/client-s3@3.1135.0";
// Signature, attestation and flag checking is delegated rather than hand-rolled: a subtly wrong
// COSE decoder or a missed flag bit is an authentication bypass that no amount of local review
// reliably catches. 13.3.2 is the first release carrying the fix for GHSA-6hxq-p678-4hr2, which
// let a forged attestation chain terminate at an attacker-supplied self-signed root.
import { verifyAuthenticationResponse, verifyRegistrationResponse } from "npm:@simplewebauthn/server@13.3.2";
import type {
    AuthenticationResponseJSON,
    AuthenticatorTransportFuture,
    RegistrationResponseJSON,
} from "npm:@simplewebauthn/server@13.3.2";

const TRACE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,64}$/;
const ROUTE_PREFIX = ["functions", "v1", "api"];
const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const RELYING_PARTY_NAME = "StudyFlow";
// Android reports the calling app, not a web page, as the WebAuthn origin: the string is derived
// from the APK signing certificate. Configuring anything web-shaped here would mean accepting an
// assertion produced by a browser against our RP ID, which is not the client we ship.
const ANDROID_ORIGIN_PREFIX = "android:apk-key-hash:";
const ES256 = -7;
const RS256 = -257;

type Json = Record<string, unknown>;
type AuthMode = "none" | "jwt";
type Route = {
    method: "GET" | "POST" | "DELETE";
    path: string;
    operation:
        | "beginSignIn"
        | "signIn"
        | "refreshTokens"
        | "listSubjects"
        | "listTasks"
        | "pullSessionChanges"
        | "pushSessionChanges"
        | "pullRecordChanges"
        | "pushRecordChanges"
        | "deleteAccount"
        | "beginPasskeyRegistration"
        | "completePasskeyRegistration";
    auth: AuthMode;
    handler: (request: Request, owner?: string) => Promise<Response>;
};
type ObservabilityEvent = {
    requestId: string;
    operation: string;
    status: number;
    durationMs: number;
    errorCode?: string;
    egressBytes?: number;
};
type GotrueTokens = {
    access_token?: unknown;
    refresh_token?: unknown;
    expires_in?: unknown;
};
type GoogleClaims = {
    iss?: unknown;
    aud?: unknown;
    exp?: unknown;
    iat?: unknown;
    sub?: unknown;
};
type GoogleJwk = Json & { kid?: string; kty?: string; alg?: string; use?: string; n?: string; e?: string };
type SubjectRow = { id: unknown; name: unknown; color_argb: unknown };
type PasskeyCredentialRow = {
    credential_id: string;
    user_id: string;
    public_key: string;
    sign_count: number;
    transports: string[] | null;
};
type ChallengeRow = { challenge_hash: string; user_id: string | null };
type ClientData = { type?: unknown; challenge?: unknown; origin?: unknown };
type TaskRow = {
    id: unknown;
    subject_id: unknown;
    title: unknown;
    completed_at: unknown;
    due_at: unknown;
    time_zone: unknown;
};
type SyncPullRow = { change_seq: unknown; session: unknown };
type RecordPullRow = { change_seq: unknown; record: unknown };

class ApiError extends Error {
    constructor(
        readonly status: number,
        readonly code: string,
        message: string,
        readonly details?: Json,
    ) {
        super(message);
    }
}

const env = (name: string): string => {
    const value = Deno.env.get(name);
    if (!value) throw new Error(`Missing required server setting: ${name}`);
    return value;
};

const supabaseUrl = env("SUPABASE_URL");
const serviceKey = env("SUPABASE_SERVICE_ROLE_KEY");
const googleServerClientId = env("GOOGLE_SERVER_CLIENT_ID");
const minimumClientVersion = env("API_MINIMUM_CLIENT_VERSION");
const backupRetentionDays = Number(env("API_BACKUP_RETENTION_DAYS"));
if (!Number.isSafeInteger(backupRetentionDays) || backupRetentionDays < 0) {
    throw new Error("API_BACKUP_RETENTION_DAYS must be a non-negative integer");
}
const storageBucket = env("STORAGE_S3_BUCKET");
const passkeyRpId = env("PASSKEY_RP_ID");
// A list, because a build signed with the upload key and one signed with the Play release key
// produce different origins for the same app, and both must be accepted during a rollout.
const passkeyOrigins = env("PASSKEY_ANDROID_ORIGIN").split(",").map((origin) => origin.trim()).filter(Boolean);
if (passkeyOrigins.length === 0 || !passkeyOrigins.every((origin) => origin.startsWith(ANDROID_ORIGIN_PREFIX))) {
    // Failing at boot rather than per request: a web-shaped origin configured here would be
    // rejected on every passkey sign-in, which reads as "passkeys are broken" long before anyone
    // suspects the secret. A refused deployment names the cause.
    throw new Error(`PASSKEY_ANDROID_ORIGIN must be a comma-separated list of ${ANDROID_ORIGIN_PREFIX}... origins`);
}
const s3 = new S3Client({
    endpoint: env("STORAGE_S3_ENDPOINT"),
    region: Deno.env.get("STORAGE_S3_REGION") ?? "us-east-1",
    forcePathStyle: true,
    credentials: {
        accessKeyId: env("STORAGE_S3_ACCESS_KEY_ID"),
        secretAccessKey: env("STORAGE_S3_SECRET_ACCESS_KEY"),
    },
});
let googleKeys: GoogleJwk[] | undefined;

const json = (status: number, body: Json | Json[], headers: HeadersInit = {}): Response =>
    new Response(JSON.stringify(body), {
        status,
        headers: {
            "content-type": "application/json",
            "x-minimum-client-version": minimumClientVersion,
            ...headers,
        },
    });


export function requestTraceId(request: Request): string {
    const provided = request.headers.get("x-request-id") ?? request.headers.get("traceparent");
    return provided && TRACE_ID_PATTERN.test(provided) ? provided : crypto.randomUUID();
}

export function routedPath(pathname: string): string {
    const segments = pathname.split("/").filter(Boolean);
    const prefixStart = segments.findIndex((segment, index) =>
        ROUTE_PREFIX.every((prefix, offset) => segments[index + offset] === prefix)
    );
    if (prefixStart >= 0) return "/" + segments.slice(prefixStart + ROUTE_PREFIX.length).join("/");
    if (segments[0] === "api" && segments[1] === "v1") return "/" + segments.slice(1).join("/");
    return "/" + segments.join("/");
}

function routeKey(request: Request): { method: string; path: string } {
    return { method: request.method, path: routedPath(new URL(request.url).pathname) };
}

function observabilityPayload(event: ObservabilityEvent): Json {
    return {
        request_id: event.requestId,
        operation: event.operation,
        status: event.status,
        duration_ms: event.durationMs,
        error_code: event.errorCode ?? null,
        egress_bytes: event.egressBytes ?? 0,
    };
}

export function observabilityLogLine(event: ObservabilityEvent): string {
    return JSON.stringify({
        event: "api_request",
        ...observabilityPayload(event),
    });
}

async function recordObservability(event: ObservabilityEvent): Promise<void> {
    console.info(observabilityLogLine(event));
    await database("backend_observability_events", {
        method: "POST",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify(observabilityPayload(event)),
    }).catch((error) => {
        console.warn(error instanceof Error ? `observability_write_failed:${error.name}` : "observability_write_failed");
    });
}

function recordObservabilityAfterResponse(event: ObservabilityEvent): void {
    const promise = recordObservability(event);
    const runtime = (globalThis as { EdgeRuntime?: { waitUntil: (promise: Promise<unknown>) => void } }).EdgeRuntime;
    if (runtime) {
        runtime.waitUntil(promise);
    } else {
        void promise;
    }
}

function withTrace(response: Response, requestId: string): Response {
    response.headers.set("x-request-id", requestId);
    response.headers.set("x-minimum-client-version", minimumClientVersion);
    return response;
}

async function database(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("apikey", serviceKey);
    headers.set("authorization", "Bearer " + serviceKey);
    if (init.body) headers.set("content-type", "application/json");
    return await fetch(`${supabaseUrl}/rest/v1/${path}`, { ...init, headers });
}

async function databaseJson<T>(path: string, init: RequestInit = {}): Promise<T> {
    const response = await database(path, init);
    if (!response.ok) throw new Error(`Database request failed (${response.status})`);
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
}

async function userId(request: Request): Promise<string> {
    const authorization = request.headers.get("authorization");
    if (!authorization?.startsWith("Bearer ")) {
        throw new ApiError(401, "authentication_required", "Sign in before calling this endpoint.");
    }
    const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
        headers: { apikey: serviceKey, authorization },
    });
    if (!response.ok) {
        if (response.status === 401 || response.status === 404) {
            throw new ApiError(401, "authentication_required", "Your session has expired.");
        }
        throw new Error(`Auth verification failed (${response.status})`);
    }
    const user = await response.json();
    if (typeof user.id !== "string") throw new ApiError(401, "authentication_required", "Invalid session.");
    return user.id;
}

async function tokenHash(request: Request): Promise<string | undefined> {
    const authorization = request.headers.get("authorization");
    if (!authorization?.startsWith("Bearer ") || authorization.length <= 7) return undefined;
    return await sha256Hex(authorization.slice(7));
}

async function deletedToken(request: Request): Promise<boolean> {
    const hash = await tokenHash(request);
    if (!hash) return false;
    const rows = await databaseJson<{ user_id: string }[]>(
        `account_deletion_receipts?token_hash=eq.${hash}&select=user_id&limit=1`,
    );
    if (rows.length === 0) return false;
    const response = await fetch(`${supabaseUrl}/auth/v1/admin/users/${encodeURIComponent(rows[0].user_id)}`, {
        headers: { apikey: serviceKey, authorization: "Bearer " + serviceKey },
    });
    if (response.status === 404) return true;
    if (!response.ok) throw new Error(`Auth lookup failed (${response.status})`);
    return false;
}

async function purgeObjects(owner: string): Promise<void> {
    const prefix = `${owner}/`;
    let keyMarker: string | undefined;
    let uploadIdMarker: string | undefined;
    do {
        const page = await s3.send(new ListMultipartUploadsCommand({
            Bucket: storageBucket,
            Prefix: prefix,
            KeyMarker: keyMarker,
            UploadIdMarker: uploadIdMarker,
        }));
        for (const upload of page.Uploads ?? []) {
            if (upload.Key && upload.UploadId) {
                await s3.send(new AbortMultipartUploadCommand({
                    Bucket: storageBucket,
                    Key: upload.Key,
                    UploadId: upload.UploadId,
                }));
            }
        }
        if (!page.IsTruncated) break;
        if (!page.NextKeyMarker) throw new Error("Incomplete multipart listing");
        keyMarker = page.NextKeyMarker;
        uploadIdMarker = page.NextUploadIdMarker;
    } while (true);

    // Always list from the start: deleting objects invalidates continuation tokens on some providers.
    do {
        const page = await s3.send(new ListObjectsV2Command({ Bucket: storageBucket, Prefix: prefix }));
        for (const object of page.Contents ?? []) {
            if (object.Key) await s3.send(new DeleteObjectCommand({ Bucket: storageBucket, Key: object.Key }));
        }
        if (!page.IsTruncated) break;
        if (!page.Contents?.length) throw new Error("Incomplete object listing");
    } while (true);
}

async function deleteAccount(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    await purgeObjects(owner);
    const hash = await tokenHash(request);
    if (!hash) throw new Error("Authenticated token missing");
    await databaseJson("account_deletion_receipts?on_conflict=token_hash", {
        method: "POST",
        headers: { prefer: "resolution=ignore-duplicates,return=minimal" },
        body: JSON.stringify({ token_hash: hash, user_id: owner }),
    });
    const response = await fetch(`${supabaseUrl}/auth/v1/admin/users/${encodeURIComponent(owner)}`, {
        method: "DELETE",
        headers: { apikey: serviceKey, authorization: "Bearer " + serviceKey },
    });
    if (response.status === 404) return json(404, { code: "account_not_found", message: "Account already deleted." });
    if (!response.ok) throw new Error(`Auth deletion failed (${response.status})`);
    return json(200, { acceptedAt: new Date().toISOString(), retentionWindowDays: backupRetentionDays });
}

function stringField(value: unknown, field: string): string {
    if (typeof value !== "string") throw new Error(`Database response was missing ${field}`);
    return value;
}

function colorHex(colorArgb: unknown): string {
    if (!Number.isInteger(colorArgb)) throw new Error("Database response had invalid color_argb");
    return "#" + ((colorArgb as number) >>> 0 & 0xFFFFFF).toString(16).padStart(6, "0").toUpperCase();
}

function subjectDto(row: SubjectRow): Json {
    return {
        id: stringField(row.id, "subject id"),
        name: stringField(row.name, "subject name"),
        colorHex: colorHex(row.color_argb),
    };
}

// `dueAt` is the stored wall-clock time resolved against the task's zone, and `dueAtTimeZone` is
// that zone. Both are sent: the instant is what clients sort and display by, and the zone label is
// what lets them recover "09:00 Europe/London" rather than an offset frozen on the day they read
// it. Dropping the zone here would silently turn a floating due time into a fixed instant the
// moment a write path echoed it back.
function taskDto(row: TaskRow): Json | undefined {
    if (typeof row.subject_id !== "string") return undefined;
    const dueAt = row.due_at === null ? null : new Date(stringField(row.due_at, "due_at")).toISOString();
    if (dueAt !== null && Number.isNaN(Date.parse(dueAt))) throw new Error("Database response had invalid due_at");
    return {
        id: stringField(row.id, "task id"),
        subjectId: row.subject_id,
        title: stringField(row.title, "task title"),
        completed: row.completed_at !== null,
        dueAt,
        dueAtTimeZone: dueAt === null ? null : stringField(row.time_zone, "task time_zone"),
    };
}

async function listSubjects(_request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const rows = await databaseJson<SubjectRow[]>(
        `subjects?user_id=eq.${encodeURIComponent(owner)}&deleted_at=is.null&select=id,name,color_argb&order=updated_at.asc,id.asc`,
    );
    return json(200, rows.map(subjectDto));
}

async function listTasks(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const subjectId = new URL(request.url).searchParams.get("subjectId");
    const rows = await databaseJson<TaskRow[]>("rpc/list_study_tasks", {
        method: "POST",
        body: JSON.stringify({
            p_user_id: owner,
            ...(subjectId ? { p_subject_id: subjectId } : {}),
        }),
    });
    return json(200, rows.map(taskDto).filter((task): task is Json => task !== undefined));
}

// ADR 0012 session sync. Conflict resolution lives in the `sync_push_study_sessions` RPC, where it
// runs under a row lock; this layer only authenticates, validates the wire shape and pages.
const SYNC_DEFAULT_LIMIT = 50;
const SYNC_MAX_LIMIT = 200;
const SYNC_MAX_CHANGES = 200;
const SYNC_MAX_EVENTS = 5000;
const SYNC_MAX_ID_LENGTH = 128;
const SYNC_MAX_NOTE_LENGTH = 10_000;
const SYNC_MAX_MILLIS = 999_999_999_999;
const SYNC_CURSOR_PREFIX = "v1:";
// The record stream has its own sequence, so its cursors are distinguishable: a session cursor
// replayed against /v1/sync/records (or the reverse) is refused rather than silently skipping.
const RECORD_CURSOR_PREFIX = "r1:";
const RECORD_ENTITY_TYPES = new Set(["task", "material"]);
const RECORD_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const RECORD_MAX_PAYLOAD_BYTES = 64 * 1024;
const RECORD_MAX_SCHEMA_VERSION = 1_000_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/;
const SESSION_EVENT_TYPES = new Set([
    "STARTED",
    "PAUSED",
    "RESUMED",
    "BREAK_STARTED",
    "FOCUS_RESUMED",
    "ACTIVITY_CONFIRMED",
    "STOPPED",
]);

// The cursor is a position in the caller's own commit-ordered change sequence. It is opaque so the
// encoding can change without an app release, and it cannot reach another user's rows because the
// pull is always filtered by the JWT's owner, never by anything the cursor says.
export function encodeSyncCursor(changeSeq: number, prefix = SYNC_CURSOR_PREFIX): string {
    return btoa(prefix + changeSeq).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function decodeSyncCursor(cursor: string, prefix = SYNC_CURSOR_PREFIX): number {
    const invalid = () => new ApiError(400, "invalid_cursor", "The sync cursor is not recognised; restart from no cursor.");
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(cursor)) throw invalid();
    let decoded: string;
    try {
        decoded = atob(cursor.replace(/-/g, "+").replace(/_/g, "/"));
    } catch {
        throw invalid();
    }
    const match = decoded.startsWith(prefix) ? /^(0|[1-9][0-9]{0,15})$/.exec(decoded.slice(prefix.length)) : null;
    const changeSeq = match ? Number(match[1]) : NaN;
    if (!Number.isSafeInteger(changeSeq)) throw invalid();
    return changeSeq;
}

function syncLimit(value: string | null): number {
    if (value === null) return SYNC_DEFAULT_LIMIT;
    const limit = /^[0-9]{1,6}$/.test(value) ? Number(value) : NaN;
    if (!Number.isSafeInteger(limit) || limit < 1) {
        throw new ApiError(400, "invalid_request", "limit must be a positive integer.");
    }
    return Math.min(limit, SYNC_MAX_LIMIT);
}

// Rebuilds an event from an allow-list in both directions, so the device-local anchors
// (`uptimeMillis`, `bootId`) are never persisted or returned (ADR 0017).
function portableEvent(event: Json): Json {
    return {
        id: event.id,
        sessionId: event.sessionId,
        type: event.type,
        sequence: event.sequence,
        wallClock: event.wallClock,
    };
}

function syncSessionDto(value: unknown): Json {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
        throw new Error("Database response had an invalid session");
    }
    const session = value as Json;
    const events = Array.isArray(session.events) ? session.events as Json[] : [];
    return { ...session, events: events.map(portableEvent) };
}

async function pullSessionChanges(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const params = new URL(request.url).searchParams;
    const cursor = params.get("cursor");
    const after = cursor === null || cursor === "" ? 0 : decodeSyncCursor(cursor);
    const limit = syncLimit(params.get("limit"));
    const rows = await databaseJson<SyncPullRow[]>("rpc/sync_pull_study_sessions", {
        method: "POST",
        body: JSON.stringify({ p_user_id: owner, p_after: after, p_limit: limit + 1 }),
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    let nextCursor: string | null = cursor || null;
    if (last) {
        if (!Number.isSafeInteger(last.change_seq)) throw new Error("Database response had invalid change_seq");
        nextCursor = encodeSyncCursor(last.change_seq as number);
    }
    // An unchanged pull echoes the request's cursor verbatim: that, not an ETag, is how the client
    // recognises "nothing changed" and skips writing (SyncEngine, ADR 0012).
    return json(200, {
        changes: page.map((row) => syncSessionDto(row.session)),
        nextCursor,
        hasMore: rows.length > limit,
    });
}

function invalidChange(message: string): ApiError {
    return new ApiError(400, "invalid_request", message);
}

function boundedString(value: unknown, field: string, maxLength = SYNC_MAX_ID_LENGTH): string {
    if (typeof value !== "string" || value.trim().length === 0 || value.length > maxLength) {
        throw invalidChange(`${field} must be a non-empty string of at most ${maxLength} characters.`);
    }
    return value;
}

function instant(value: unknown, field: string): string {
    if (typeof value !== "string" || !ISO_INSTANT_PATTERN.test(value) || Number.isNaN(Date.parse(value))) {
        throw invalidChange(`${field} must be an ISO-8601 instant.`);
    }
    return value;
}

function millis(value: unknown, field: string): number {
    const amount = value ?? 0;
    if (!Number.isSafeInteger(amount) || (amount as number) < 0 || (amount as number) > SYNC_MAX_MILLIS) {
        throw invalidChange(`${field} must be a non-negative integer.`);
    }
    return amount as number;
}

function optionalString(value: unknown, field: string, maxLength = SYNC_MAX_ID_LENGTH): string | null {
    if (value === undefined || value === null) return null;
    if (typeof value !== "string" || value.length > maxLength) {
        throw invalidChange(`${field} must be a string of at most ${maxLength} characters.`);
    }
    return value;
}

function optionalBoolean(value: unknown, field: string): boolean {
    if (value === undefined || value === null) return false;
    if (typeof value !== "boolean") throw invalidChange(`${field} must be a boolean.`);
    return value;
}

function syncEvent(value: unknown, sessionId: string): Json {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw invalidChange("Each event must be an object.");
    const event = value as Json;
    if (typeof event.sessionId !== "string" || event.sessionId.toLowerCase() !== sessionId.toLowerCase()) {
        throw invalidChange("An event's sessionId must match its session.");
    }
    if (typeof event.type !== "string" || !SESSION_EVENT_TYPES.has(event.type)) {
        throw invalidChange("An event has an unknown type.");
    }
    if (!Number.isSafeInteger(event.sequence) || (event.sequence as number) < 0) {
        throw invalidChange("An event's sequence must be a non-negative integer.");
    }
    return portableEvent({
        id: boundedString(event.id, "event id"),
        sessionId,
        type: event.type,
        sequence: event.sequence,
        wallClock: instant(event.wallClock, "event wallClock"),
    });
}

function syncChange(value: unknown): Json {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw invalidChange("Each change must be an object.");
    const change = value as Json;
    if (typeof change.id !== "string" || !UUID_PATTERN.test(change.id)) {
        throw invalidChange("A session id must be a UUID.");
    }
    const id = change.id.toLowerCase();
    if (change.status !== "STOPPED") {
        if (typeof change.status !== "string" || change.status.length === 0) {
            throw invalidChange("A session status is required.");
        }
        throw new ApiError(409, "active_session_not_syncable", "Only stopped sessions sync.", { sessionId: id });
    }
    const startedAt = instant(change.startedAt, "startedAt");
    const endedAt = instant(change.endedAt, "endedAt");
    if (Date.parse(endedAt) < Date.parse(startedAt)) throw invalidChange("endedAt must not precede startedAt.");
    const events = change.events ?? [];
    if (!Array.isArray(events) || events.length > SYNC_MAX_EVENTS) {
        throw invalidChange(`events must be an array of at most ${SYNC_MAX_EVENTS} entries.`);
    }
    return {
        id,
        deviceId: boundedString(change.deviceId, "deviceId"),
        updatedAt: instant(change.updatedAt, "updatedAt"),
        startedAt,
        endedAt,
        status: "STOPPED",
        subjectId: optionalString(change.subjectId, "subjectId"),
        taskId: optionalString(change.taskId, "taskId"),
        note: optionalString(change.note, "note", SYNC_MAX_NOTE_LENGTH),
        deleted: optionalBoolean(change.deleted, "deleted"),
        manualOverride: optionalBoolean(change.manualOverride, "manualOverride"),
        countedMillis: millis(change.countedMillis, "countedMillis"),
        unverifiedMillis: millis(change.unverifiedMillis, "unverifiedMillis"),
        events: events.map((event) => syncEvent(event, id)),
    };
}

// Validation completes for the whole batch before anything is written, so a malformed entry never
// leaves half a batch applied. A stale entry is not malformed: the RPC reports it in rejectedIds.
async function pushSessionChanges(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const value = await body(request);
    boundedString(value.deviceId, "deviceId");
    if (!Array.isArray(value.changes) || value.changes.length > SYNC_MAX_CHANGES) {
        throw invalidChange(`changes must be an array of at most ${SYNC_MAX_CHANGES} sessions.`);
    }
    const changes = value.changes.map(syncChange);
    if (changes.length === 0) return json(200, { acceptedIds: [], rejectedIds: [] });
    const outcome = await databaseJson<{ acceptedIds?: unknown; rejectedIds?: unknown }>("rpc/sync_push_study_sessions", {
        method: "POST",
        body: JSON.stringify({ p_user_id: owner, p_changes: changes }),
    });
    if (!Array.isArray(outcome?.acceptedIds) || !Array.isArray(outcome?.rejectedIds)) {
        throw new Error("Database response had an invalid push outcome");
    }
    return json(200, { acceptedIds: outcome.acceptedIds, rejectedIds: outcome.rejectedIds });
}

async function pullRecordChanges(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const params = new URL(request.url).searchParams;
    const cursor = params.get("cursor");
    const after = cursor === null || cursor === "" ? 0 : decodeSyncCursor(cursor, RECORD_CURSOR_PREFIX);
    const limit = syncLimit(params.get("limit"));
    const rows = await databaseJson<RecordPullRow[]>("rpc/sync_pull_records", {
        method: "POST",
        body: JSON.stringify({ p_user_id: owner, p_after: after, p_limit: limit + 1 }),
    });
    const page = rows.slice(0, limit);
    const last = page.at(-1);
    let nextCursor: string | null = cursor || null;
    if (last) {
        if (!Number.isSafeInteger(last.change_seq)) throw new Error("Database response had invalid change_seq");
        nextCursor = encodeSyncCursor(last.change_seq as number, RECORD_CURSOR_PREFIX);
    }
    return json(200, {
        changes: page.map((row) => {
            if (!row.record || typeof row.record !== "object" || Array.isArray(row.record)) {
                throw new Error("Database response had an invalid record");
            }
            return row.record;
        }),
        nextCursor,
        hasMore: rows.length > limit,
    });
}

// The payload is stored without interpretation (ADR 0018); only the envelope is validated, plus
// the one payload field the protocol depends on: a material is only replicated once its bytes are
// stored, so it must carry its object key. Keys are opaque and resolved within the caller's own
// storage namespace on download, so a key can never reach another account's objects.
function syncRecord(value: unknown): Json {
    if (!value || typeof value !== "object" || Array.isArray(value)) throw invalidChange("Each change must be an object.");
    const change = value as Json;
    if (typeof change.entityType !== "string" || !RECORD_ENTITY_TYPES.has(change.entityType)) {
        throw invalidChange("entityType must be task or material.");
    }
    if (typeof change.id !== "string" || !RECORD_ID_PATTERN.test(change.id)) {
        throw invalidChange("A record id must be 1 to 128 of A-Z, a-z, 0-9, '.', '_', ':' or '-'.");
    }
    const schemaVersion = change.schemaVersion;
    if (!Number.isSafeInteger(schemaVersion) || (schemaVersion as number) < 1 || (schemaVersion as number) > RECORD_MAX_SCHEMA_VERSION) {
        throw invalidChange("schemaVersion must be a positive integer.");
    }
    const payload = change.payload;
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) throw invalidChange("payload must be an object.");
    if (new TextEncoder().encode(JSON.stringify(payload)).length > RECORD_MAX_PAYLOAD_BYTES) {
        throw invalidChange(`payload must be at most ${RECORD_MAX_PAYLOAD_BYTES} bytes.`);
    }
    if (change.entityType === "material") {
        const remoteKey = (payload as Json).remoteKey;
        if (typeof remoteKey !== "string" || remoteKey.trim().length === 0 || remoteKey.length > 1024) {
            throw invalidChange("A material is only synced once uploaded, with its remoteKey.");
        }
    }
    return {
        entityType: change.entityType,
        id: change.id,
        deviceId: boundedString(change.deviceId, "deviceId"),
        updatedAt: instant(change.updatedAt, "updatedAt"),
        deleted: optionalBoolean(change.deleted, "deleted"),
        schemaVersion,
        payload,
    };
}

async function pushRecordChanges(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const value = await body(request);
    boundedString(value.deviceId, "deviceId");
    if (!Array.isArray(value.changes) || value.changes.length > SYNC_MAX_CHANGES) {
        throw invalidChange(`changes must be an array of at most ${SYNC_MAX_CHANGES} records.`);
    }
    const changes = value.changes.map(syncRecord);
    if (changes.length === 0) return json(200, { acceptedIds: [], rejectedIds: [] });
    const outcome = await databaseJson<{ acceptedIds?: unknown; rejectedIds?: unknown }>("rpc/sync_push_records", {
        method: "POST",
        body: JSON.stringify({ p_user_id: owner, p_changes: changes }),
    });
    if (!Array.isArray(outcome?.acceptedIds) || !Array.isArray(outcome?.rejectedIds)) {
        throw new Error("Database response had an invalid push outcome");
    }
    return json(200, { acceptedIds: outcome.acceptedIds, rejectedIds: outcome.rejectedIds });
}

async function body(request: Request): Promise<Json> {
    try {
        const value = await request.json();
        if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
        return value;
    } catch {
        throw new ApiError(400, "invalid_request", "The request body must be a JSON object.");
    }
}

function base64Url(bytes: Uint8Array): string {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function decodeBase64Url(value: string): Uint8Array {
    const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
    const padded = base64 + "=".repeat((4 - base64.length % 4) % 4);
    const binary = atob(padded);
    return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function sha256Hex(value: string): Promise<string> {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
    return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function storeChallenge(
    challenge: string,
    requestJson: string,
    expiresAt: string,
    purpose: "signin" | "registration",
    owner?: string,
): Promise<void> {
    await databaseJson("auth_signin_challenges", {
        method: "POST",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({
            challenge_hash: await sha256Hex(challenge),
            request_json: requestJson,
            expires_at: expiresAt,
            purpose,
            ...(owner ? { user_id: owner } : {}),
        }),
    });
}

// Claims a challenge, or reports that it was never issued, has expired, or has already been used.
// The claim is one statement in the database rather than a read here and a write later: with two
// statements, two concurrent replays of the same assertion both observe an unconsumed row and both
// succeed. Returning the row the update matched is what makes "exactly once" a property of the
// database rather than of this function's timing.
async function consumeChallenge(
    challenge: string,
    purpose: "signin" | "registration",
): Promise<ChallengeRow | undefined> {
    const rows = await databaseJson<ChallengeRow[]>("rpc/consume_auth_challenge", {
        method: "POST",
        body: JSON.stringify({ p_challenge_hash: await sha256Hex(challenge), p_purpose: purpose }),
    });
    return rows[0];
}

async function beginSignIn(_request: Request): Promise<Response> {
    const challenge = base64Url(crypto.getRandomValues(new Uint8Array(32)));
    const expiresAt = new Date(Date.now() + CHALLENGE_TTL_MS).toISOString();
    // No `allowCredentials`: sign-in happens before we know who is signing in, so the authenticator
    // picks from its own discoverable credentials. Listing candidates would require the caller to
    // name an account first, which would make this endpoint an account-existence oracle.
    const requestJson = JSON.stringify({
        challenge,
        rpId: passkeyRpId,
        timeout: CHALLENGE_TTL_MS,
        userVerification: "preferred",
    });
    await storeChallenge(challenge, requestJson, expiresAt, "signin");
    return json(200, { requestJson, expiresAt });
}

function translateTokens(tokens: GotrueTokens): Json {
    if (
        typeof tokens.access_token !== "string" || tokens.access_token.length === 0 ||
        typeof tokens.refresh_token !== "string" || tokens.refresh_token.length === 0 ||
        !Number.isSafeInteger(tokens.expires_in)
    ) {
        throw new Error("Auth response was missing token fields");
    }
    return {
        accessToken: tokens.access_token,
        refreshToken: tokens.refresh_token,
        expiresInSeconds: tokens.expires_in,
    };
}

async function authTokenGrant(grantType: string, payload: Json): Promise<Json> {
    const response = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=${grantType}`, {
        method: "POST",
        headers: {
            apikey: serviceKey,
            authorization: "Bearer " + serviceKey,
            "content-type": "application/json",
        },
        body: JSON.stringify(payload),
    });
    if (!response.ok) {
        if (response.status >= 500) throw new Error(`GoTrue token grant failed (${response.status})`);
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
    return translateTokens(await response.json() as GotrueTokens);
}

async function refreshTokens(request: Request): Promise<Response> {
    const value = await body(request);
    if (typeof value.refreshToken !== "string" || value.refreshToken.length === 0) {
        throw new ApiError(400, "invalid_request", "refreshToken is required.");
    }
    return json(200, await authTokenGrant("refresh_token", { refresh_token: value.refreshToken }));
}

function parseJwtPart<T>(part: string): T {
    try {
        return JSON.parse(new TextDecoder().decode(decodeBase64Url(part))) as T;
    } catch {
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
}

async function googleJwks(): Promise<GoogleJwk[]> {
    if (googleKeys) return googleKeys;
    const response = await fetch(GOOGLE_JWKS_URL);
    if (!response.ok) throw new Error(`Google JWKS request failed (${response.status})`);
    const body = await response.json();
    if (!body || !Array.isArray(body.keys)) throw new Error("Google JWKS response was malformed");
    googleKeys = body.keys as GoogleJwk[];
    return googleKeys;
}

function validAudience(audience: unknown): boolean {
    if (typeof audience === "string") return audience === googleServerClientId;
    return Array.isArray(audience) && audience.includes(googleServerClientId);
}

async function verifyGoogleIdToken(idToken: string): Promise<GoogleClaims> {
    const parts = idToken.split(".");
    if (parts.length !== 3) {
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
    const header = parseJwtPart<{ alg?: unknown; kid?: unknown }>(parts[0]);
    const claims = parseJwtPart<GoogleClaims>(parts[1]);
    if (header.alg !== "RS256" || typeof header.kid !== "string") {
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
    const jwk = (await googleJwks()).find((key) => key.kid === header.kid && key.kty === "RSA");
    if (!jwk) throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    const key = await crypto.subtle.importKey(
        "jwk",
        jwk,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"],
    );
    const data = new TextEncoder().encode(`${parts[0]}.${parts[1]}`);
    const signature = decodeBase64Url(parts[2]);
    if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, new Uint8Array(signature), data))) {
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
    const now = Math.floor(Date.now() / 1000);
    if (
        !["accounts.google.com", "https://accounts.google.com"].includes(String(claims.iss)) ||
        !validAudience(claims.aud) ||
        typeof claims.exp !== "number" || claims.exp <= now ||
        typeof claims.sub !== "string" || claims.sub.length === 0
    ) {
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
    if (typeof claims.iat === "number" && claims.iat > now + 300) {
        throw new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
    }
    return claims;
}

function invalidCredentials(): ApiError {
    // Every passkey failure answers identically on purpose: distinguishing "no such credential"
    // from "bad signature" from "replayed challenge" tells an attacker which half of their guess
    // was right, and turns the endpoint into an account-enumeration oracle.
    return new ApiError(401, "invalid_credentials", "The supplied credentials could not be verified.");
}

function credentialResponse(value: Json): Json {
    const response = value.response;
    if (!response || typeof response !== "object" || Array.isArray(response)) throw invalidCredentials();
    return response as Json;
}

function clientData(value: unknown): ClientData {
    if (typeof value !== "string" || value.length === 0) throw invalidCredentials();
    try {
        return JSON.parse(new TextDecoder().decode(decodeBase64Url(value))) as ClientData;
    } catch {
        throw invalidCredentials();
    }
}

function parseCredentialJson(value: unknown): Json {
    if (typeof value !== "string" || value.length === 0) throw invalidCredentials();
    try {
        const parsed = JSON.parse(value);
        if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error();
        return parsed as Json;
    } catch {
        throw invalidCredentials();
    }
}

async function passkeyCredential(credentialId: string): Promise<PasskeyCredentialRow | undefined> {
    const rows = await databaseJson<PasskeyCredentialRow[]>(
        `passkey_credentials?credential_id=eq.${encodeURIComponent(credentialId)}` +
            "&select=credential_id,user_id,public_key,sign_count,transports&limit=1",
    );
    return rows[0];
}

async function authFetch(path: string, init: RequestInit = {}): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set("apikey", serviceKey);
    headers.set("authorization", "Bearer " + serviceKey);
    if (init.body) headers.set("content-type", "application/json");
    return await fetch(`${supabaseUrl}/auth/v1/${path}`, { ...init, headers });
}

/**
 * Issues a Supabase session for an account this function has already authenticated itself.
 *
 * GoTrue has no admin "create a session for this user" route, so the only supported path is to
 * generate a magic link for the account and redeem its hashed token immediately. The link is
 * created and consumed inside this function and is never sent anywhere, so no user-reachable
 * credential is produced by it. It lives in one helper because that is the single place in the
 * codebase where a session is minted without the user presenting an identity-provider proof.
 */
async function mintSessionForUser(owner: string): Promise<Json> {
    const account = await authFetch(`admin/users/${encodeURIComponent(owner)}`);
    if (!account.ok) throw new Error(`Auth lookup failed (${account.status})`);
    const email = (await account.json()).email;
    if (typeof email !== "string" || email.length === 0) throw new Error("Auth user has no address to mint a session");
    const link = await authFetch("admin/generate_link", {
        method: "POST",
        body: JSON.stringify({ type: "magiclink", email }),
    });
    if (!link.ok) throw new Error(`Auth link generation failed (${link.status})`);
    const hashedToken = (await link.json()).hashed_token;
    if (typeof hashedToken !== "string" || hashedToken.length === 0) {
        throw new Error("Auth link response was missing hashed_token");
    }
    const session = await authFetch("verify", {
        method: "POST",
        body: JSON.stringify({ type: "magiclink", token_hash: hashedToken }),
    });
    if (!session.ok) throw new Error(`Auth session minting failed (${session.status})`);
    return translateTokens(await session.json() as GotrueTokens);
}

async function verifyPasskeyAssertion(assertion: unknown): Promise<Response> {
    const response = parseCredentialJson(assertion);
    const data = clientData(credentialResponse(response).clientDataJSON);
    const challenge = typeof data.challenge === "string" ? data.challenge : "";
    // Claimed before anything else is checked, so a request that fails later still burns the
    // nonce: a challenge that survived a failed attempt could be retried until one lands.
    if (challenge.length === 0 || !await consumeChallenge(challenge, "signin")) throw invalidCredentials();

    const credentialId = typeof response.id === "string" ? response.id : "";
    const credential = credentialId.length === 0 ? undefined : await passkeyCredential(credentialId);
    // The account is the one the *stored* credential belongs to. The user handle in the response is
    // a client-supplied claim about identity, so it is only ever checked against that owner, never
    // used to choose it — trusting it would let anyone sign in as anyone by editing one field.
    if (!credential) throw invalidCredentials();
    const userHandle = credentialResponse(response).userHandle;
    if (typeof userHandle === "string" && userHandle.length > 0) {
        const claimed = new TextDecoder().decode(decodeBase64Url(userHandle));
        if (claimed !== credential.user_id) throw invalidCredentials();
    }

    let verification;
    try {
        verification = await verifyAuthenticationResponse({
            response: response as unknown as AuthenticationResponseJSON,
            expectedChallenge: challenge,
            expectedOrigin: passkeyOrigins,
            expectedRPID: passkeyRpId,
            // User presence is always enforced by the library; user verification is not required
            // because the sign-in options ask for it only as `preferred`, and demanding here what
            // was not demanded there would reject conforming authenticators rather than attackers.
            requireUserVerification: false,
            credential: {
                id: credential.credential_id,
                publicKey: decodeBase64Url(credential.public_key),
                counter: Number(credential.sign_count),
                transports: (credential.transports ?? []) as AuthenticatorTransportFuture[],
            },
        });
    } catch {
        // The library reports a wrong origin, a wrong RP ID hash, a cleared user-presence flag, a
        // rewound signature counter and a bad signature by throwing; all of them are one answer.
        throw invalidCredentials();
    }
    if (!verification.verified) throw invalidCredentials();

    const newCounter = verification.authenticationInfo.newCounter;
    await databaseJson(`passkey_credentials?credential_id=eq.${encodeURIComponent(credential.credential_id)}`, {
        method: "PATCH",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({ sign_count: newCounter, last_used_at: new Date().toISOString() }),
    });
    return json(200, await mintSessionForUser(credential.user_id));
}

async function signIn(request: Request): Promise<Response> {
    const value = await body(request);
    if (value.type === "passkey") return await verifyPasskeyAssertion(value.assertion);
    if (value.type !== "google" || typeof value.idToken !== "string" || value.idToken.length === 0) {
        throw new ApiError(400, "invalid_request", "A supported sign-in credential is required.");
    }
    await verifyGoogleIdToken(value.idToken);
    return json(200, await authTokenGrant("id_token", {
        provider: "google",
        id_token: value.idToken,
        client_id: googleServerClientId,
    }));
}

/**
 * Creation options for adding a passkey to the account that is already signed in.
 *
 * Registration is authenticated by design: Google sign-in is the account-creation path, and a
 * passkey is an authenticator attached to an existing account. An unauthenticated registration
 * endpoint would let a stranger bind their own authenticator to an account they merely named.
 */
async function beginPasskeyRegistration(_request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const account = await authFetch(`admin/users/${encodeURIComponent(owner)}`);
    if (!account.ok) throw new Error(`Auth lookup failed (${account.status})`);
    const email = (await account.json()).email;
    const existing = await databaseJson<{ credential_id: string; transports: string[] | null }[]>(
        `passkey_credentials?user_id=eq.${encodeURIComponent(owner)}&select=credential_id,transports`,
    );
    const challenge = base64Url(crypto.getRandomValues(new Uint8Array(32)));
    const expiresAt = new Date(Date.now() + CHALLENGE_TTL_MS).toISOString();
    const requestJson = JSON.stringify({
        challenge,
        rp: { id: passkeyRpId, name: RELYING_PARTY_NAME },
        // The user handle is the account id, so an assertion that carries one can be checked
        // against the credential's owner instead of being taken at face value. It is deliberately
        // not the address: a handle is stored on the authenticator and survives an address change.
        user: {
            id: base64Url(new TextEncoder().encode(owner)),
            name: typeof email === "string" && email.length > 0 ? email : owner,
            displayName: typeof email === "string" && email.length > 0 ? email : owner,
        },
        pubKeyCredParams: [
            { type: "public-key", alg: ES256 },
            { type: "public-key", alg: RS256 },
        ],
        timeout: CHALLENGE_TTL_MS,
        attestation: "none",
        // Offering an authenticator that already holds a credential for this account the chance to
        // decline, so the user gets "you already have a passkey here" rather than a silent second
        // credential that makes the picker ambiguous forever.
        excludeCredentials: existing.map((row) => ({
            type: "public-key",
            id: row.credential_id,
            ...(row.transports?.length ? { transports: row.transports } : {}),
        })),
        authenticatorSelection: { residentKey: "required", userVerification: "preferred" },
    });
    await storeChallenge(challenge, requestJson, expiresAt, "registration", owner);
    return json(200, { requestJson, expiresAt });
}

async function completePasskeyRegistration(request: Request, owner?: string): Promise<Response> {
    if (!owner) throw new Error("Authenticated owner missing");
    const value = await body(request);
    if (typeof value.registrationResponseJson !== "string" || value.registrationResponseJson.length === 0) {
        throw new ApiError(400, "invalid_request", "registrationResponseJson is required.");
    }
    const response = parseCredentialJson(value.registrationResponseJson);
    const data = clientData(credentialResponse(response).clientDataJSON);
    const challenge = typeof data.challenge === "string" ? data.challenge : "";
    const claimed = challenge.length === 0 ? undefined : await consumeChallenge(challenge, "registration");
    // A registration challenge belongs to the account it was issued to. Without this check, one
    // user could hand their challenge to another, who would then attach their own authenticator
    // to the first user's account.
    if (!claimed || claimed.user_id !== owner) throw invalidCredentials();

    let verification;
    try {
        verification = await verifyRegistrationResponse({
            response: response as unknown as RegistrationResponseJSON,
            expectedChallenge: challenge,
            expectedOrigin: passkeyOrigins,
            expectedRPID: passkeyRpId,
            requireUserVerification: false,
        });
    } catch {
        throw invalidCredentials();
    }
    const registration = verification.registrationInfo;
    if (!verification.verified || !registration) throw invalidCredentials();

    const created = await database("passkey_credentials", {
        method: "POST",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({
            credential_id: registration.credential.id,
            user_id: owner,
            public_key: base64Url(registration.credential.publicKey),
            sign_count: registration.credential.counter,
            transports: registration.credential.transports ?? [],
        }),
    });
    if (created.status === 409) {
        throw new ApiError(409, "credential_already_registered", "This passkey is already registered.");
    }
    if (!created.ok) throw new Error(`Database request failed (${created.status})`);
    return json(201, { credentialId: registration.credential.id, createdAt: new Date().toISOString() });
}

const routes: Route[] = [
    { method: "POST", path: "/v1/auth/signin/challenge", operation: "beginSignIn", auth: "none", handler: beginSignIn },
    { method: "POST", path: "/v1/auth/signin", operation: "signIn", auth: "none", handler: signIn },
    { method: "POST", path: "/v1/auth/refresh", operation: "refreshTokens", auth: "none", handler: refreshTokens },
    { method: "GET", path: "/v1/subjects", operation: "listSubjects", auth: "jwt", handler: listSubjects },
    { method: "GET", path: "/v1/tasks", operation: "listTasks", auth: "jwt", handler: listTasks },
    {
        method: "GET",
        path: "/v1/sync/sessions",
        operation: "pullSessionChanges",
        auth: "jwt",
        handler: pullSessionChanges,
    },
    {
        method: "POST",
        path: "/v1/sync/sessions",
        operation: "pushSessionChanges",
        auth: "jwt",
        handler: pushSessionChanges,
    },
    {
        method: "GET",
        path: "/v1/sync/records",
        operation: "pullRecordChanges",
        auth: "jwt",
        handler: pullRecordChanges,
    },
    {
        method: "POST",
        path: "/v1/sync/records",
        operation: "pushRecordChanges",
        auth: "jwt",
        handler: pushRecordChanges,
    },
    { method: "DELETE", path: "/v1/account", operation: "deleteAccount", auth: "jwt", handler: deleteAccount },
    {
        method: "POST",
        path: "/v1/auth/passkey/registration/challenge",
        operation: "beginPasskeyRegistration",
        auth: "jwt",
        handler: beginPasskeyRegistration,
    },
    {
        method: "POST",
        path: "/v1/auth/passkey/registration",
        operation: "completePasskeyRegistration",
        auth: "jwt",
        handler: completePasskeyRegistration,
    },
];

export function resolveRoute(method: string, path: string): Route | undefined {
    return routes.find((route) => route.method === method && route.path === path);
}

function operationName(method: string, path: string): string {
    return resolveRoute(method, path)?.operation ?? "unknown";
}

async function dispatch(request: Request): Promise<Response> {
    const key = routeKey(request);
    const matchingPath = routes.some((route) => route.path === key.path);
    if (!matchingPath) throw new ApiError(404, "endpoint_not_found", "API endpoint not found.");
    const route = resolveRoute(key.method, key.path);
    if (!route) throw new ApiError(405, "method_not_allowed", "Use the endpoint's configured method.");
    let owner: string | undefined;
    switch (route.auth) {
        case "none":
            break;
        case "jwt":
            try {
                owner = await userId(request);
            } catch (error) {
                if (
                    key.path === "/v1/account" && error instanceof ApiError && error.status === 401 &&
                    await deletedToken(request)
                ) {
                    return json(404, { code: "account_not_found", message: "Account already deleted." });
                }
                throw error;
            }
            break;
        default:
            throw new ApiError(500, "auth_policy_missing", "Endpoint authentication policy is not configured.");
    }
    return await route.handler(request, owner);
}

export async function handleRequest(request: Request): Promise<Response> {
    const startedAt = performance.now();
    const requestId = requestTraceId(request);
    const key = routeKey(request);
    const operation = operationName(key.method, key.path);
    let status = 503;
    let errorCode: string | undefined;
    try {
        const response = await dispatch(request);
        status = response.status;
        return withTrace(response, requestId);
    } catch (error) {
        if (error instanceof ApiError) {
            status = error.status;
            errorCode = error.code;
            return withTrace(json(error.status, {
                code: error.code,
                message: error.message,
                ...(error.details ? { details: error.details } : {}),
            }), requestId);
        }
        errorCode = "api_unavailable";
        console.error(error instanceof Error ? `api_unavailable:${error.name}` : "api_unavailable");
        return withTrace(json(503, { code: "api_unavailable", message: "The API is temporarily unavailable." }), requestId);
    } finally {
        recordObservabilityAfterResponse({
            requestId,
            operation,
            status,
            durationMs: Math.max(0, Math.round(performance.now() - startedAt)),
            errorCode,
            egressBytes: 0,
        });
    }
}

if (import.meta.main) Deno.serve(handleRequest);
