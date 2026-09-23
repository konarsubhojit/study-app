import {
    AbortMultipartUploadCommand,
    CompleteMultipartUploadCommand,
    CreateMultipartUploadCommand,
    DeleteObjectCommand,
    GetObjectCommand,
    HeadObjectCommand,
    S3Client,
    UploadPartCommand,
} from "npm:@aws-sdk/client-s3@3.1135.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.1135.0";

const MAX_SIZE_BYTES = 50 * 1024 * 1024;
const PART_SIZE_BYTES = 8 * 1024 * 1024;
const SIGNED_URL_TTL_SECONDS = 10 * 60;
const UPLOAD_TTL_MS = 24 * 60 * 60 * 1000;
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
const OPERATIONS = new Set(["initUpload", "completeUpload", "getDownloadUrl", "delete", "reapOrphans"]);
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

export function observabilityLogLine(event: ObservabilityEvent): string {
    return JSON.stringify({
        event: "storage_request",
        request_id: event.requestId,
        operation: event.operation,
        status: event.status,
        duration_ms: event.durationMs,
        error_code: event.errorCode ?? null,
        egress_bytes: event.egressBytes ?? 0,
    });
}

async function recordObservability(event: ObservabilityEvent): Promise<void> {
    console.info(observabilityLogLine(event));
    await database("backend_observability_events", {
        method: "POST",
        headers: { prefer: "return=minimal" },
        body: JSON.stringify({
            request_id: event.requestId,
            operation: event.operation,
            status: event.status,
            duration_ms: event.durationMs,
            error_code: event.errorCode ?? null,
            egress_bytes: event.egressBytes ?? 0,
        }),
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
            throw new ApiError(409, "object_already_exists", "This file already exists or is being finalized.");
        }
        throw new Error(`Database request failed (${response.status})`);
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
    try {
        const value = await request.json();
        if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error();
        return value;
    } catch {
        throw new ApiError(400, "invalid_request", "The request body must be a JSON object.");
    }
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
    try {
        if (reservation.created) {
            const created = await s3.send(
                new CreateMultipartUploadCommand({
                    Bucket: bucket,
                    Key: key,
                    ContentType: upload.contentType,
                    Metadata: { sha256: upload.hash, owner },
                    ChecksumAlgorithm: "SHA256",
                }),
            );
            providerUploadId = created.UploadId;
            if (!providerUploadId) throw new Error("Storage provider returned no upload id");
            await databaseJson(`storage_uploads?id=eq.${uploadId}`, {
                method: "PATCH",
                headers: { prefer: "return=minimal" },
                body: JSON.stringify({ provider_upload_id: providerUploadId }),
            });
        } else if (!providerUploadId) {
            throw new ApiError(409, "upload_initializing", "This upload is being initialized. Retry shortly.");
        }

        const urlExpiresAt = new Date(Date.now() + SIGNED_URL_TTL_SECONDS * 1000).toISOString();
        const parts = await Promise.all(upload.checksums.map(async (checksum, index) => {
            const partNumber = index + 1;
            const size = Math.min(PART_SIZE_BYTES, upload.sizeBytes - index * PART_SIZE_BYTES);
            const url = await getSignedUrl(
                s3,
                new UploadPartCommand({
                    Bucket: bucket,
                    Key: key,
                    UploadId: providerUploadId,
                    PartNumber: partNumber,
                    ChecksumSHA256: checksum,
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
                requiredHeaders: { "x-amz-checksum-sha256": checksum },
            };
        }));
        return json(200, { uploadId, expiresAt, parts });
    } catch (error) {
        if (reservation.created && providerUploadId) {
            await s3.send(
                new AbortMultipartUploadCommand({
                    Bucket: bucket,
                    Key: key,
                    UploadId: providerUploadId,
                }),
            ).catch(() => undefined);
        }
        if (reservation.created) {
            await database(`storage_uploads?id=eq.${uploadId}`, {
                method: "PATCH",
                body: JSON.stringify({ state: "failed" }),
            });
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
        return {
            PartNumber: index + 1,
            ETag: part.etag,
            ChecksumSHA256: upload.part_checksums[index],
        };
    });

    if (upload.state === "pending") {
        const claimed = await databaseJson<UploadRow[]>(
            `storage_uploads?id=eq.${upload.id}&state=eq.pending&select=*`,
            {
                method: "PATCH",
                headers: { prefer: "return=representation" },
                body: JSON.stringify({ state: "completing" }),
            },
        );
        if (claimed.length !== 1) {
            throw new ApiError(409, "upload_not_completable", "This upload is already being completed.");
        }
    }
    try {
        let object = await headObject(upload.object_key);
        if (!object) {
            await s3.send(
                new CompleteMultipartUploadCommand({
                    Bucket: bucket,
                    Key: upload.object_key,
                    UploadId: upload.provider_upload_id,
                    MultipartUpload: { Parts: completedParts },
                }),
            );
            object = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: upload.object_key }));
        }
        if (object.ContentLength !== upload.size_bytes) {
            await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: upload.object_key }));
            throw new ApiError(422, "integrity_mismatch", "The uploaded file did not match its declared size.");
        }
        await scanIfConfigured(upload);
        const finalized = await databaseJson<boolean>("rpc/storage_finalize_upload", {
            method: "POST",
            body: JSON.stringify({ p_upload_id: upload.id, p_user_id: owner }),
        });
        if (!finalized) throw new Error("Upload state changed before finalization");
        return json(200, storedObject({ ...upload, state: "ready" }));
    } catch (error) {
        if (error instanceof ApiError) {
            await database(`storage_uploads?id=eq.${upload.id}`, {
                method: "PATCH",
                body: JSON.stringify({ state: "failed" }),
            });
        }
        throw error;
    }
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

async function readyObject(owner: string, hash: unknown): Promise<UploadRow> {
    if (typeof hash !== "string" || !HASH.test(hash)) {
        throw new ApiError(400, "invalid_content_hash", "contentHash must be a lowercase SHA-256 digest.");
    }
    const key = objectKey(owner, hash);
    const rows = await databaseJson<UploadRow[]>(
        `storage_uploads?user_id=eq.${owner}&object_key=eq.${encodeURIComponent(key)}&state=eq.ready&select=*&limit=1`,
    );
    if (rows.length !== 1) throw new ApiError(404, "object_not_found", "File not found.");
    return rows[0];
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
    responseEgressBytes.set(response, upload.size_bytes);
    return response;
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
            default:
                throw new ApiError(404, "endpoint_not_found", "Storage endpoint not found.");
        }
        status = response.status;
        return withTrace(response, requestId);
    } catch (error) {
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
        console.error(error instanceof Error ? `storage_unavailable:${error.name}` : "storage_unavailable");
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

if (import.meta.main) Deno.serve(handleRequest);
