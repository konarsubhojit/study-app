import {
    AbortMultipartUploadCommand,
    DeleteObjectCommand,
    ListMultipartUploadsCommand,
    ListObjectsV2Command,
    S3Client,
} from "npm:@aws-sdk/client-s3@3.1135.0";

const TRACE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,64}$/;
const ROUTE_PREFIX = ["functions", "v1", "api"];
const CHALLENGE_TTL_MS = 5 * 60 * 1000;
const GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";

type Json = Record<string, unknown>;
type AuthMode = "none" | "jwt";
type Route = {
    method: "GET" | "POST" | "DELETE";
    path: string;
    operation: "beginSignIn" | "signIn" | "refreshTokens" | "listSubjects" | "listTasks" | "deleteAccount";
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
type TaskRow = {
    id: unknown;
    subject_id: unknown;
    title: unknown;
    completed_at: unknown;
    due_at: unknown;
    time_zone: unknown;
};

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

const json = (status: number, body: Json, headers: HeadersInit = {}): Response =>
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

async function beginSignIn(_request: Request): Promise<Response> {
    const challenge = base64Url(crypto.getRandomValues(new Uint8Array(32)));
    const expiresAt = new Date(Date.now() + CHALLENGE_TTL_MS).toISOString();
    const requestJson = JSON.stringify({
        challenge,
        timeout: CHALLENGE_TTL_MS,
        userVerification: "preferred",
    });
    await databaseJson("auth_signin_challenges", {
        method: "POST",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({
            challenge_hash: await sha256Hex(challenge),
            request_json: requestJson,
            expires_at: expiresAt,
        }),
    });
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

async function signIn(request: Request): Promise<Response> {
    const value = await body(request);
    if (value.type === "passkey") {
        throw new ApiError(501, "not_implemented", "Passkey sign-in verification is not implemented yet.");
    }
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

const routes: Route[] = [
    { method: "POST", path: "/v1/auth/signin/challenge", operation: "beginSignIn", auth: "none", handler: beginSignIn },
    { method: "POST", path: "/v1/auth/signin", operation: "signIn", auth: "none", handler: signIn },
    { method: "POST", path: "/v1/auth/refresh", operation: "refreshTokens", auth: "none", handler: refreshTokens },
    { method: "GET", path: "/v1/subjects", operation: "listSubjects", auth: "jwt", handler: listSubjects },
    { method: "GET", path: "/v1/tasks", operation: "listTasks", auth: "jwt", handler: listTasks },
    { method: "DELETE", path: "/v1/account", operation: "deleteAccount", auth: "jwt", handler: deleteAccount },
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
