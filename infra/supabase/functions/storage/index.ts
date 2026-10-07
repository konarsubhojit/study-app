import {
    AbortMultipartUploadCommand,
    CompleteMultipartUploadCommand,
    CreateMultipartUploadCommand,
    DeleteObjectCommand,
    GetObjectCommand,
    HeadObjectCommand,
    ListPartsCommand,
    S3Client,
    UploadPartCommand,
} from "npm:@aws-sdk/client-s3@3.1135.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.1135.0";
import { FetchHttpHandler } from "npm:@smithy/fetch-http-handler@5.7.2";

const MAX_SIZE_BYTES = 50 * 1024 * 1024;
const PART_SIZE_BYTES = 8 * 1024 * 1024;
const SIGNED_URL_TTL_SECONDS = 10 * 60;
const UPLOAD_TTL_MS = 24 * 60 * 60 * 1000;
const COMPLETION_TTL_MS = 15 * 60 * 1000;
const HASH = /^[0-9a-f]{64}$/;
const SHA256_BASE64 = /^[A-Za-z0-9+/]{43}=$/;
const ALLOWED_MIME_TYPES = new Set([
    "application/pdf",
    "image/gif",
    "image/jpeg",
    "image/png",
    "image/webp",
    "text/plain",
    "video/mp4",
]);
const OPERATIONS = new Set(["initUpload", "completeUpload", "getDownloadUrl", "delete", "stat", "reapOrphans"]);
// `backend_observability_events.operation` is constrained to the operations that existed when the
// table was created; newer ones are recorded there as "unknown" and named in the log line instead.
const RECORDED_OPERATIONS = new Set(["initUpload", "completeUpload", "getDownloadUrl", "delete", "reapOrphans"]);
const TRACE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,64}$/;
const responseEgressBytes = new WeakMap<Response, number>();

type Json = Record<string, unknown>;
type UploadRow = {
    id: string;
    user_id: string;
    object_key: string;
    provider_upload_id: string | null;
    content_hash: string;
    content_type: string;
    size_bytes: number;
    part_checksums: string[];
    state: string;
    completed_at?: string | null;
    completing_at?: string | null;
    created_at?: string;
};
type Reservation = {
    upload_id: string;
    created: boolean;
    expires_at: string;
    provider_upload_id: string | null;
};
type ObservabilityEvent = {
    requestId: string;
    operation: string;
    status: number;
    durationMs: number;
    errorCode?: string;
    egressBytes?: number;
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

/**
 * A non-OK answer from a service this function called on the caller's behalf.
 *
 * Carrying the upstream status is what keeps a constraint violation from being reported as a
 * server outage: a bare `Error` here would reach the catch-all and become a retryable 503, which
 * the client then retries forever against a request that can never succeed. Mirrors the `api`
 * function's class of the same name so the two report upstream failures identically.
 */
class UpstreamError extends Error {
    constructor(
        readonly service: string,
        readonly status: number,
    ) {
        super(`${service} request failed (${status})`);
        this.name = "UpstreamError";
    }
}

const env = (name: string): string => {
    const value = Deno.env.get(name);
    if (!value) throw new Error(`Missing required server setting: ${name}`);
    return value;
};

const supabaseUrl = env("SUPABASE_URL");
const serviceKey = env("SUPABASE_SERVICE_ROLE_KEY");
const bucket = env("STORAGE_S3_BUCKET");
const s3 = new S3Client({
    endpoint: env("STORAGE_S3_ENDPOINT"),
    region: Deno.env.get("STORAGE_S3_REGION") ?? "us-east-1",
    forcePathStyle: true,
    requestHandler: new FetchHttpHandler({
        requestTimeout: 60_000,
        requestInit: () => ({ signal: AbortSignal.timeout(60_000) }),
    }),
    maxAttempts: 2,
    // Supabase Storage's S3 protocol supports no `x-amz-checksum-*` / `x-amz-sdk-checksum-algorithm`
    // headers: it creates its backing multipart upload without a checksum algorithm and records
    // parts without one, so any checksum we add is either ignored or — on completion — turns every
    // part into an `InvalidPart`. Only send checksums an operation strictly requires (none here).
    requestChecksumCalculation: "WHEN_REQUIRED",
    responseChecksumValidation: "WHEN_REQUIRED",
    credentials: {
        accessKeyId: env("STORAGE_S3_ACCESS_KEY_ID"),
        secretAccessKey: env("STORAGE_S3_SECRET_ACCESS_KEY"),
    },
});

const json = (status: number, body: Json, headers: HeadersInit = {}): Response =>
    new Response(JSON.stringify(body), {
        status,
        headers: { "content-type": "application/json", ...headers },
    });

export function requestTraceId(request: Request): string {
    const provided = request.headers.get("x-request-id") ?? request.headers.get("traceparent");
    return provided && TRACE_ID_PATTERN.test(provided) ? provided : crypto.randomUUID();
}

function operationName(request: Request): string {
    const candidate = new URL(request.url).pathname.split("/").filter(Boolean).at(-1) ?? "";
    return OPERATIONS.has(candidate) ? candidate : "unknown";
}

export function observabilityPayload(event: ObservabilityEvent): Json {
    return {
        request_id: event.requestId,
        operation: RECORDED_OPERATIONS.has(event.operation) ? event.operation : "unknown",
        status: event.status,
        duration_ms: event.durationMs,
        error_code: event.errorCode ?? null,
        egress_bytes: event.egressBytes ?? 0,
    };
}

export function observabilityLogLine(event: ObservabilityEvent): string {
    return JSON.stringify({
        event: "storage_request",
        ...observabilityPayload(event),
        operation: event.operation,
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
    const promise = recordObservability(event).catch(() => {
        console.warn("observability_write_failed");
    });
    const runtime = (globalThis as { EdgeRuntime?: { waitUntil: (promise: Promise<unknown>) => void } }).EdgeRuntime;
    if (runtime) {
        runtime.waitUntil(promise);
    } else {
        void promise;
    }
}

function withTrace(response: Response, requestId: string): Response {
    response.headers.set("x-request-id", requestId);
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
    if (!response.ok) {
        const detail = await response.text();
        let code = "unknown";
        try {
            const value = JSON.parse(detail).code;
            if (typeof value === "string" && /^(?:[0-9A-Z]{5}|PGRST\d{3})$/.test(value)) code = value;
        } catch {
            // Only bounded database error codes are safe to log; messages can contain user data.
        }
        const resource = path.split("?")[0];
        console.warn(JSON.stringify({
            event: "storage_upstream_rejected",
            resource: /^(?:rpc\/)?[a-z_]+$/.test(resource) ? resource : "unknown",
            status: response.status,
            code,
        }));
        if (detail.includes("rate_limited")) {
            throw new ApiError(429, "rate_limited", "Too many storage requests. Try again in one minute.");
        }
        if (detail.includes("quota_exceeded")) {
            const available = /"details":"(\d+)"/.exec(detail)?.[1];
            throw new ApiError(
                413,
                "quota_exceeded",
                "This upload exceeds your remaining storage quota.",
                available ? { availableBytes: available } : undefined,
            );
        }
        if (detail.includes("object_already_exists")) {
            throw new ApiError(409, "object_already_exists", "This file already exists.");
        }
        if (detail.includes("upload_in_progress")) {
            throw new ApiError(409, "upload_in_progress", "This file is being finalized. Retry shortly.");
        }
        throw new UpstreamError("Database", response.status);
    }
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
}

async function userId(request: Request): Promise<string> {
    const authorization = request.headers.get("authorization");
    if (!authorization?.startsWith("Bearer ")) {
        throw new ApiError(401, "authentication_required", "Sign in before using cloud storage.");
    }
    const response = await fetch(`${supabaseUrl}/auth/v1/user`, {
        headers: { apikey: serviceKey, authorization },
    });
    if (!response.ok) throw new ApiError(401, "authentication_required", "Your session has expired.");
    const user = await response.json();
    if (typeof user.id !== "string") throw new ApiError(401, "authentication_required", "Invalid session.");
    return user.id;
}

async function body(request: Request): Promise<Json> {
    if (request.bodyUsed) throw new Error("Request body stream already consumed.");
    let text: string;
    try {
        text = await request.text();
    } catch {
        throw new Error("Request body stream could not be read.");
    }
    let value: unknown;
    let reason: string | undefined;
    if (!text.trim()) {
        reason = "empty_body";
    } else {
        try {
            value = JSON.parse(text);
        } catch {
            reason = "invalid_json";
        }
        if (!reason && (value === null || typeof value !== "object" || Array.isArray(value))) {
            reason = value === null ? "json_null" : Array.isArray(value) ? "json_array" : `json_${typeof value}`;
        }
    }
    if (reason) {
        console.warn(JSON.stringify({ event: "request_body_invalid", reason }));
        throw new ApiError(400, "invalid_request", "The request body must be a JSON object.");
    }
    return value as Json;
}

async function rateLimit(owner: string, operation: string, limit: number): Promise<void> {
    await databaseJson("rpc/storage_consume_rate_limit", {
        method: "POST",
        body: JSON.stringify({
            p_user_id: owner,
            p_operation: operation,
            p_limit: limit,
            p_window_seconds: 60,
        }),
    });
}

export const objectKey = (owner: string, hash: string): string => `${owner}/${hash}`;

export function validateUpload(value: Json): {
    hash: string;
    contentType: string;
    sizeBytes: number;
    checksums: string[];
} {
    const hash = value.contentHash;
    const contentType = value.contentType;
    const sizeBytes = value.sizeBytes;
    const checksums = value.partChecksums;
    if (typeof hash !== "string" || !HASH.test(hash)) {
        throw new ApiError(400, "invalid_content_hash", "contentHash must be a lowercase SHA-256 digest.");
    }
    if (typeof contentType !== "string" || !ALLOWED_MIME_TYPES.has(contentType)) {
        throw new ApiError(415, "unsupported_media_type", "Choose a PDF, image, text file, or MP4 video.");
    }
    if (!Number.isSafeInteger(sizeBytes) || (sizeBytes as number) <= 0) {
        throw new ApiError(400, "invalid_size", "sizeBytes must be a positive integer.");
    }
    if ((sizeBytes as number) > MAX_SIZE_BYTES) {
        throw new ApiError(413, "file_too_large", `Choose a file smaller than ${MAX_SIZE_BYTES} bytes.`);
    }
    const partCount = Math.ceil((sizeBytes as number) / PART_SIZE_BYTES);
    if (
        !Array.isArray(checksums) || checksums.length !== partCount ||
        !checksums.every((checksum) => typeof checksum === "string" && SHA256_BASE64.test(checksum))
    ) {
        throw new ApiError(
            400,
            "invalid_part_checksums",
            `Provide exactly ${partCount} base64 SHA-256 part checksums.`,
        );
    }
    return { hash, contentType, sizeBytes: sizeBytes as number, checksums: checksums as string[] };
}

async function audit(
    owner: string,
    operation: "upload_part" | "download",
    key: string,
    expiresAt: string,
    uploadId?: string,
): Promise<void> {
    await databaseJson("storage_url_audit", {
        method: "POST",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({
            user_id: owner,
            operation,
            object_key: key,
            upload_id: uploadId,
            expires_at: expiresAt,
        }),
    });
}

async function initUpload(request: Request, owner: string): Promise<Response> {
    await rateLimit(owner, "initUpload", 10);
    const upload = validateUpload(await body(request));
    const key = objectKey(owner, upload.hash);
    const requestedExpiry = new Date(Date.now() + UPLOAD_TTL_MS).toISOString();
    const reservations = await databaseJson<Reservation[]>("rpc/storage_reserve_upload", {
        method: "POST",
        body: JSON.stringify({
            p_user_id: owner,
            p_object_key: key,
            p_content_hash: upload.hash,
            p_content_type: upload.contentType,
            p_size_bytes: upload.sizeBytes,
            p_part_checksums: upload.checksums,
            p_expires_at: requestedExpiry,
        }),
    });
    const reservation = reservations[0];
    if (!reservation) throw new Error("Database returned no upload reservation");
    const uploadId = reservation.upload_id;
    const expiresAt = reservation.expires_at;

    let providerUploadId = reservation.provider_upload_id ?? undefined;
    let createdProviderId: string | undefined;
    let providerMayBePersisted = false;
    try {
        let replaceProvider = reservation.created;
        if (!reservation.created) {
            if (!providerUploadId) {
                throw new ApiError(409, "upload_initializing", "This upload is being initialized. Retry shortly.");
            }
            const existing = await ownedUpload(owner, uploadId);
            if (existing.state !== "pending" || existing.provider_upload_id !== providerUploadId) {
                throw new ApiError(409, "upload_in_progress", "This upload changed while being initialized. Retry shortly.");
            }
            if (existing.size_bytes !== upload.sizeBytes || existing.content_type !== upload.contentType ||
                existing.part_checksums.length !== Math.ceil(upload.sizeBytes / PART_SIZE_BYTES) ||
                JSON.stringify(existing.part_checksums) !== JSON.stringify(upload.checksums)) {
                await databaseJson(
                    `storage_uploads?id=eq.${uploadId}&state=eq.pending&provider_upload_id=eq.${encodeURIComponent(providerUploadId)}`,
                    {
                        method: "PATCH",
                        headers: { prefer: "return=minimal" },
                        body: JSON.stringify({ state: "failed" }),
                    },
                );
                throw new ApiError(409, "upload_initializing", "Upload metadata changed. Retry with a fresh reservation.");
            }
            try {
                await s3.send(new ListPartsCommand({
                    Bucket: bucket,
                    Key: key,
                    UploadId: providerUploadId,
                    MaxParts: 1,
                }));
            } catch (error) {
                if (!isS3Fault(error, ["NoSuchUpload"])) throw error;
                replaceProvider = true;
            }
        }
        if (replaceProvider) {
            const created = await s3.send(
                new CreateMultipartUploadCommand({
                    Bucket: bucket,
                    Key: key,
                    ContentType: upload.contentType,
                    Metadata: { sha256: upload.hash, owner },
                }),
            );
            createdProviderId = created.UploadId;
            if (!createdProviderId) throw new Error("Storage provider returned no upload id");
            const previousHandle = providerUploadId
                ? `eq.${encodeURIComponent(providerUploadId)}`
                : "is.null";
            // A lost PATCH response does not prove the provider handle was left unreferenced.
            providerMayBePersisted = true;
            const updated = await databaseJson<UploadRow[]>(
                `storage_uploads?id=eq.${uploadId}&state=eq.pending&provider_upload_id=${previousHandle}`,
                {
                    method: "PATCH",
                    headers: { prefer: "return=representation" },
                    body: JSON.stringify({ provider_upload_id: createdProviderId, part_checksums: upload.checksums }),
                },
            );
            if (updated.length !== 1) {
                providerMayBePersisted = false;
                throw new ApiError(409, "upload_in_progress", "This upload changed while being initialized. Retry shortly.");
            }
            providerUploadId = createdProviderId;
        }

        const urlExpiresAt = new Date(Date.now() + SIGNED_URL_TTL_SECONDS * 1000).toISOString();
        // Part checksums stay part of the client contract (and of the reservation's identity), but
        // are not sent to the provider: see the S3 client above. Signing them in would add a header
        // the provider ignores, and completing with them makes the provider reject every part.
        const parts = await Promise.all(upload.checksums.map(async (_checksum, index) => {
            const partNumber = index + 1;
            const size = Math.min(PART_SIZE_BYTES, upload.sizeBytes - index * PART_SIZE_BYTES);
            const url = await getSignedUrl(
                s3,
                new UploadPartCommand({
                    Bucket: bucket,
                    Key: key,
                    UploadId: providerUploadId,
                    PartNumber: partNumber,
                }),
                { expiresIn: SIGNED_URL_TTL_SECONDS },
            );
            await audit(owner, "upload_part", key, urlExpiresAt, uploadId);
            return {
                number: partNumber,
                offset: index * PART_SIZE_BYTES,
                size,
                url,
                expiresAt: urlExpiresAt,
                requiredHeaders: {},
            };
        }));
        return json(200, { uploadId, providerUploadId, expiresAt, parts });
    } catch (error) {
        // Never abort a persisted handle unless its pending row was successfully retired.
        // A completion or replacement may have acquired ownership while signing was in flight.
        let abortCreated = !!createdProviderId && !providerMayBePersisted;
        if (createdProviderId && providerMayBePersisted) {
            const retired = await databaseJson<UploadRow[]>(
                `storage_uploads?id=eq.${uploadId}&state=eq.pending&provider_upload_id=eq.${encodeURIComponent(createdProviderId)}`,
                {
                    method: "PATCH",
                    headers: { prefer: "return=representation" },
                    body: JSON.stringify({ state: "failed" }),
                },
            ).catch(() => []);
            abortCreated = retired.length === 1;
        } else if (reservation.created && !createdProviderId) {
            await database(`storage_uploads?id=eq.${uploadId}&state=eq.pending&provider_upload_id=is.null`, {
                method: "PATCH",
                body: JSON.stringify({ state: "failed" }),
            });
        }
        if (abortCreated) {
            await s3.send(
                new AbortMultipartUploadCommand({
                    Bucket: bucket,
                    Key: key,
                    UploadId: createdProviderId,
                }),
            ).catch(() => undefined);
        }
        throw error;
    }
}

async function ownedUpload(owner: string, uploadId: unknown): Promise<UploadRow> {
    if (typeof uploadId !== "string") {
        throw new ApiError(400, "invalid_upload_id", "uploadId is required.");
    }
    const rows = await databaseJson<UploadRow[]>(
        `storage_uploads?id=eq.${encodeURIComponent(uploadId)}&user_id=eq.${owner}&select=*`,
    );
    if (rows.length !== 1) throw new ApiError(404, "upload_not_found", "Upload not found.");
    return rows[0];
}

async function completeUpload(request: Request, owner: string): Promise<Response> {
    await rateLimit(owner, "completeUpload", 20);
    const value = await body(request);
    const upload = await ownedUpload(owner, value.uploadId);
    if (upload.state === "ready") {
        return json(200, storedObject(upload));
    }
    if (value.providerUploadId !== undefined && value.providerUploadId !== upload.provider_upload_id) {
        throw new ApiError(422, "invalid_parts", "These part receipts belong to a replaced upload.");
    }
    if (!["pending", "completing"].includes(upload.state) || !upload.provider_upload_id) {
        throw new ApiError(409, "upload_not_completable", "This upload cannot be completed.");
    }
    const parts = value.parts;
    if (!Array.isArray(parts) || parts.length !== upload.part_checksums.length) {
        throw new ApiError(400, "invalid_parts", `Provide all ${upload.part_checksums.length} uploaded parts.`);
    }
    const completedParts = parts.map((part, index) => {
        if (
            !part || typeof part !== "object" || part.number !== index + 1 ||
            typeof part.etag !== "string" || part.etag.length === 0
        ) {
            throw new ApiError(400, "invalid_parts", "Parts must be complete and ordered by number.");
        }
        return { PartNumber: index + 1, ETag: part.etag };
    });

    const claim = await databaseJson<string | null>("rpc/storage_claim_completion", {
        method: "POST",
        body: JSON.stringify({
            p_upload_id: upload.id,
            p_user_id: owner,
            p_provider_upload_id: upload.provider_upload_id,
        }),
    });
    if (!claim) {
        throw new ApiError(409, "upload_in_progress", "This upload is already being completed. Retry shortly.");
    }
    let providerCompleted = false;
    try {
        let object = await headObject(upload.object_key);
        providerCompleted = object !== null;
        if (!object) {
            await s3.send(
                new CompleteMultipartUploadCommand({
                    Bucket: bucket,
                    Key: upload.object_key,
                    UploadId: upload.provider_upload_id,
                    MultipartUpload: { Parts: completedParts },
                }),
            );
            providerCompleted = true;
            object = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: upload.object_key }));
        }
        if (object.ContentLength !== upload.size_bytes) {
            await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: upload.object_key }));
            throw new ApiError(422, "integrity_mismatch", "The uploaded file did not match its declared size.");
        }
        await scanIfConfigured(upload);
        const finalized = await databaseJson<boolean>("rpc/storage_finalize_upload", {
            method: "POST",
            body: JSON.stringify({ p_upload_id: upload.id, p_user_id: owner, p_completing_at: claim }),
        });
        if (!finalized) throw new Error("Upload state changed before finalization");
        return json(200, storedObject({ ...upload, state: "ready" }));
    } catch (error) {
        const failure = isInvalidMultipartPart(error)
            ? new ApiError(422, "invalid_parts", "Storage rejected the uploaded part identifiers.")
            : error;
        const rejected = failure instanceof ApiError &&
            ["integrity_mismatch", "invalid_parts", "scan_rejected"].includes(failure.code);
        // Once HEAD or CompleteMultipartUpload confirms provider success, keep the fenced claim.
        // The 15-minute lease recovers it if finalization fails; a retry will verify via HEAD.
        if (rejected || !providerCompleted) {
            await databaseJson(
                `storage_uploads?id=eq.${upload.id}&state=eq.completing&completing_at=eq.${encodeURIComponent(claim)}`,
                {
                    method: "PATCH",
                    headers: { prefer: "return=minimal" },
                    body: JSON.stringify({ state: rejected ? "failed" : "pending", completing_at: null }),
                },
            ).catch(() => console.warn("completion_release_failed"));
        }
        throw failure;
    }
}

function isInvalidMultipartPart(error: unknown): boolean {
    return isS3Fault(error, ["InvalidPart", "InvalidPartOrder", "NoSuchUpload"]);
}

function isS3Fault(error: unknown, codes: string[]): boolean {
    if (!error || typeof error !== "object") return false;
    const fault = error as { Code?: unknown; code?: unknown; name?: unknown };
    return [fault.Code, fault.code, fault.name].some((code) =>
        typeof code === "string" && codes.includes(code)
    );
}

async function headObject(key: string) {
    try {
        return await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    } catch (error) {
        const name = error instanceof Error ? error.name : "";
        if (name === "NotFound" || name === "NoSuchKey") return null;
        throw error;
    }
}

function storedObject(upload: UploadRow): Json {
    return {
        contentHash: upload.content_hash,
        contentType: upload.content_type,
        sizeBytes: upload.size_bytes,
    };
}

async function scanIfConfigured(upload: UploadRow): Promise<void> {
    const hook = Deno.env.get("STORAGE_SCAN_HOOK_URL");
    if (!hook) return;
    const response = await fetch(hook, {
        method: "POST",
        headers: {
            authorization: "Bearer " + env("STORAGE_SCAN_HOOK_TOKEN"),
            "content-type": "application/json",
        },
        body: JSON.stringify({
            bucket,
            key: upload.object_key,
            expectedSha256: upload.content_hash,
            sizeBytes: upload.size_bytes,
        }),
    });
    const result = response.ok ? await response.json() : null;
    if (result?.clean !== true || result?.sha256 !== upload.content_hash) {
        await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: upload.object_key }));
        throw new ApiError(422, "scan_rejected", "The uploaded file failed its safety scan.");
    }
}

async function readyObject(owner: string, hash: unknown, includeInProgress = false): Promise<UploadRow> {
    if (typeof hash !== "string" || !HASH.test(hash)) {
        throw new ApiError(400, "invalid_content_hash", "contentHash must be a lowercase SHA-256 digest.");
    }
    const key = objectKey(owner, hash);
    const rows = await databaseJson<UploadRow[]>(
        `storage_uploads?user_id=eq.${owner}&object_key=eq.${encodeURIComponent(key)}` +
            `&state=${includeInProgress ? "in.(ready,completing,reaping)" : "eq.ready"}&select=*&limit=1`,
    );
    if (rows.length !== 1) throw new ApiError(404, "object_not_found", "File not found.");
    const upload = rows[0];
    if (upload.state !== "ready") {
        const claimTime = Date.parse(upload.completing_at ?? upload.created_at ?? "");
        if (upload.state === "completing" && claimTime < Date.now() - COMPLETION_TTL_MS) {
            throw new ApiError(404, "object_not_found", "File not found.");
        }
        throw new ApiError(409, "upload_in_progress", "This file is being finalized. Retry shortly.");
    }
    return upload;
}

async function getDownloadUrl(request: Request, owner: string): Promise<Response> {
    await rateLimit(owner, "getDownloadUrl", 60);
    const upload = await readyObject(owner, (await body(request)).contentHash);
    const expiresAt = new Date(Date.now() + SIGNED_URL_TTL_SECONDS * 1000).toISOString();
    const url = await getSignedUrl(
        s3,
        new GetObjectCommand({ Bucket: bucket, Key: upload.object_key }),
        { expiresIn: SIGNED_URL_TTL_SECONDS },
    );
    await audit(owner, "download", upload.object_key, expiresAt);
    const response = json(200, { url, expiresAt });
    // The BFF only sees URL issuance, not the provider's later transfer log; this is projected egress.
    responseEgressBytes.set(response, upload.size_bytes);
    return response;
}

export function statResponse(upload: UploadRow): Json {
    return {
        ...storedObject(upload),
        ...(upload.completed_at ? { updatedAt: upload.completed_at } : {}),
    };
}

// Lets a client confirm an object it believes it uploaded really exists before trusting it; a
// `404 object_not_found` tells it to upload the bytes again.
async function statObject(request: Request, owner: string): Promise<Response> {
    await rateLimit(owner, "stat", 60);
    const upload = await readyObject(owner, (await body(request)).contentHash, true);
    return json(200, statResponse(upload));
}

async function deleteObject(request: Request, owner: string): Promise<Response> {
    await rateLimit(owner, "delete", 30);
    const value = await body(request);
    if (typeof value.contentHash !== "string" || !HASH.test(value.contentHash)) {
        throw new ApiError(400, "invalid_content_hash", "contentHash must be a lowercase SHA-256 digest.");
    }
    const key = objectKey(owner, value.contentHash);
    const uploads = await databaseJson<UploadRow[]>(
        `storage_uploads?user_id=eq.${owner}&object_key=eq.${encodeURIComponent(key)}&select=*`,
    );
    for (const upload of uploads) {
        if (upload.provider_upload_id && upload.state !== "ready") {
            await s3.send(
                new AbortMultipartUploadCommand({
                    Bucket: bucket,
                    Key: key,
                    UploadId: upload.provider_upload_id,
                }),
            ).catch(() => undefined);
        }
    }
    await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
    await databaseJson(`storage_uploads?user_id=eq.${owner}&object_key=eq.${encodeURIComponent(key)}`, {
        method: "PATCH",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({ state: "deleted" }),
    });
    return new Response(null, { status: 204 });
}

async function reapOrphans(request: Request): Promise<Response> {
    const expected = env("ORPHAN_REAPER_TOKEN");
    if (!(await equalTokens(request.headers.get("x-reaper-token"), expected))) {
        throw new ApiError(401, "authentication_required", "Invalid reaper token.");
    }
    const uploads = await databaseJson<UploadRow[]>("rpc/storage_claim_expired_uploads", {
        method: "POST",
        body: JSON.stringify({ p_limit: 100 }),
    });
    let reaped = 0;
    for (const upload of uploads) {
        try {
            if (upload.provider_upload_id) {
                await s3.send(
                    new AbortMultipartUploadCommand({
                        Bucket: bucket,
                        Key: upload.object_key,
                        UploadId: upload.provider_upload_id,
                    }),
                );
            }
            await databaseJson(`storage_uploads?id=eq.${upload.id}`, {
                method: "PATCH",
                headers: { prefer: "return=minimal" },
                body: JSON.stringify({ state: "failed" }),
            });
            reaped++;
        } catch {
            await database(`storage_uploads?id=eq.${upload.id}`, {
                method: "PATCH",
                body: JSON.stringify({ state: "pending" }),
            });
        }
    }
    return json(200, { reaped });
}

async function equalTokens(provided: string | null, expected: string): Promise<boolean> {
    if (!provided) return false;
    const encoder = new TextEncoder();
    const [providedHash, expectedHash] = await Promise.all([
        crypto.subtle.digest("SHA-256", encoder.encode(provided)),
        crypto.subtle.digest("SHA-256", encoder.encode(expected)),
    ]);
    const left = new Uint8Array(providedHash);
    const right = new Uint8Array(expectedHash);
    return left.every((value, index) => value === right[index]);
}

export async function handleRequest(request: Request): Promise<Response> {
    const startedAt = performance.now();
    const requestId = requestTraceId(request);
    const operation = operationName(request);
    let status = 503;
    let errorCode: string | undefined;
    let response: Response | undefined;
    try {
        if (request.method !== "POST") throw new ApiError(405, "method_not_allowed", "Use POST.");
        if (operation === "reapOrphans") {
            response = await reapOrphans(request);
            status = response.status;
            return withTrace(response, requestId);
        }
        const owner = await userId(request);
        switch (operation) {
            case "initUpload":
                response = await initUpload(request, owner);
                break;
            case "completeUpload":
                response = await completeUpload(request, owner);
                break;
            case "getDownloadUrl":
                response = await getDownloadUrl(request, owner);
                break;
            case "delete":
                response = await deleteObject(request, owner);
                break;
            case "stat":
                response = await statObject(request, owner);
                break;
            default:
                throw new ApiError(404, "endpoint_not_found", "Storage endpoint not found.");
        }
        status = response.status;
        return withTrace(response, requestId);
    } catch (error) {
        // Only explicitly matched ApiErrors are client faults; our upstream queries can fail
        // with 4xx for server misconfiguration and must remain retryable outages.
        if (error instanceof ApiError) {
            const headers: HeadersInit = error.status === 429 ? { "retry-after": "60" } : {};
            status = error.status;
            errorCode = error.code;
            response = json(error.status, {
                code: error.code,
                message: error.message,
                ...(error.details ? { details: error.details } : {}),
            }, headers);
            return withTrace(response, requestId);
        }
        errorCode = "storage_unavailable";
        console.error(unavailableLogLine(operation, error));
        response = json(503, { code: "storage_unavailable", message: "Cloud storage is temporarily unavailable." });
        return withTrace(response, requestId);
    } finally {
        recordObservabilityAfterResponse({
            requestId,
            operation,
            status,
            durationMs: Math.max(0, Math.round(performance.now() - startedAt)),
            errorCode,
            egressBytes: response ? responseEgressBytes.get(response) : 0,
        });
    }
}

/**
 * The catch-all's log line: the resolved operation, the error's name and its message.
 *
 * The messages thrown in this file are developer-authored ("Database request failed (500)") and
 * carry no user data; the name alone was true of every unexpected failure and identified none of
 * them. Stack traces, request bodies, tokens and headers stay out, and any URL is redacted, because
 * a provider SDK's message may quote the request it was making — including a signed URL.
 */
export function unavailableLogLine(operation: string, error: unknown): string {
    if (!(error instanceof Error)) return `storage_unavailable operation=${operation}`;
    const message = error.message.replace(/[a-z][a-z0-9+.-]*:\/\/\S+/gi, "<url>");
    return `storage_unavailable operation=${operation} name=${error.name} message=${message}`;
}

if (import.meta.main) Deno.serve(handleRequest);
