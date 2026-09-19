import {
  AbortMultipartUploadCommand,
  CompleteMultipartUploadCommand,
  CreateMultipartUploadCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  S3Client,
  UploadPartCommand,
} from "npm:@aws-sdk/client-s3@3.1136.0";
import { getSignedUrl } from "npm:@aws-sdk/s3-request-presigner@3.1136.0";

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
  const expiresAt = new Date(Date.now() + UPLOAD_TTL_MS).toISOString();
  const reservation = await databaseJson<string>("rpc/storage_reserve_upload", {
    method: "POST",
    body: JSON.stringify({
      p_user_id: owner,
      p_object_key: key,
      p_content_hash: upload.hash,
      p_content_type: upload.contentType,
      p_size_bytes: upload.sizeBytes,
      p_part_checksums: upload.checksums,
      p_expires_at: expiresAt,
    }),
  });

  let providerUploadId: string | undefined;
  try {
    const created = await s3.send(new CreateMultipartUploadCommand({
      Bucket: bucket,
      Key: key,
      ContentType: upload.contentType,
      Metadata: { sha256: upload.hash, owner },
      ChecksumAlgorithm: "SHA256",
    }));
    providerUploadId = created.UploadId;
    if (!providerUploadId) throw new Error("Storage provider returned no upload id");
    await databaseJson(`storage_uploads?id=eq.${reservation}`, {
      method: "PATCH",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({ provider_upload_id: providerUploadId }),
    });

    const urlExpiresAt = new Date(Date.now() + SIGNED_URL_TTL_SECONDS * 1000).toISOString();
    const parts = await Promise.all(upload.checksums.map(async (checksum, index) => {
      const partNumber = index + 1;
      const size = Math.min(PART_SIZE_BYTES, upload.sizeBytes - index * PART_SIZE_BYTES);
      const url = await getSignedUrl(s3, new UploadPartCommand({
        Bucket: bucket,
        Key: key,
        UploadId: providerUploadId,
        PartNumber: partNumber,
        ChecksumSHA256: checksum,
      }), { expiresIn: SIGNED_URL_TTL_SECONDS });
      await audit(owner, "upload_part", key, urlExpiresAt, reservation);
      return { number: partNumber, offset: index * PART_SIZE_BYTES, size, url, expiresAt: urlExpiresAt };
    }));
    return json(200, { uploadId: reservation, expiresAt, parts });
  } catch (error) {
    if (providerUploadId) {
      await s3.send(new AbortMultipartUploadCommand({
        Bucket: bucket,
        Key: key,
        UploadId: providerUploadId,
      })).catch(() => undefined);
    }
    await database(`storage_uploads?id=eq.${reservation}`, {
      method: "PATCH",
      body: JSON.stringify({ state: "failed" }),
    });
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
  if (upload.state !== "pending" || !upload.provider_upload_id) {
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
  try {
    await s3.send(new CompleteMultipartUploadCommand({
      Bucket: bucket,
      Key: upload.object_key,
      UploadId: upload.provider_upload_id,
      MultipartUpload: { Parts: completedParts },
    }));
    const object = await s3.send(new HeadObjectCommand({ Bucket: bucket, Key: upload.object_key }));
    if (object.ContentLength !== upload.size_bytes || object.Metadata?.sha256 !== upload.content_hash) {
      await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: upload.object_key }));
      throw new ApiError(422, "integrity_mismatch", "The uploaded file did not match its declared size or hash.");
    }
    await scanIfConfigured(upload);
    await databaseJson(`storage_uploads?id=eq.${upload.id}`, {
      method: "PATCH",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({ state: "ready", completed_at: new Date().toISOString() }),
    });
    await databaseJson("thumbnail_generation_queue", {
      method: "POST",
      headers: { prefer: "return=minimal" },
      body: JSON.stringify({ upload_id: upload.id, user_id: owner, object_key: upload.object_key }),
    });
    return json(200, storedObject({ ...upload, state: "ready" }));
  } catch (error) {
    await database(`storage_uploads?id=eq.${upload.id}`, {
      method: "PATCH",
      body: JSON.stringify({ state: error instanceof ApiError ? "failed" : "pending" }),
    });
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
  return json(200, { url, expiresAt });
}

async function deleteObject(request: Request, owner: string): Promise<Response> {
  await rateLimit(owner, "delete", 30);
  const value = await body(request);
  if (typeof value.contentHash !== "string" || !HASH.test(value.contentHash)) {
    throw new ApiError(400, "invalid_content_hash", "contentHash must be a lowercase SHA-256 digest.");
  }
  const key = objectKey(owner, value.contentHash);
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
  if (request.headers.get("x-reaper-token") !== expected) {
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
        await s3.send(new AbortMultipartUploadCommand({
          Bucket: bucket,
          Key: upload.object_key,
          UploadId: upload.provider_upload_id,
        }));
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

export async function handleRequest(request: Request): Promise<Response> {
  try {
    if (request.method !== "POST") throw new ApiError(405, "method_not_allowed", "Use POST.");
    const operation = new URL(request.url).pathname.split("/").filter(Boolean).at(-1);
    if (operation === "reapOrphans") return await reapOrphans(request);
    const owner = await userId(request);
    switch (operation) {
      case "initUpload":
        return await initUpload(request, owner);
      case "completeUpload":
        return await completeUpload(request, owner);
      case "getDownloadUrl":
        return await getDownloadUrl(request, owner);
      case "delete":
        return await deleteObject(request, owner);
      default:
        throw new ApiError(404, "endpoint_not_found", "Storage endpoint not found.");
    }
  } catch (error) {
    if (error instanceof ApiError) {
      const headers = error.status === 429 ? { "retry-after": "60" } : {};
      return json(error.status, {
        code: error.code,
        message: error.message,
        ...(error.details ? { details: error.details } : {}),
      }, headers);
    }
    console.error(error instanceof Error ? error.message : "Unknown storage service failure");
    return json(503, { code: "storage_unavailable", message: "Cloud storage is temporarily unavailable." });
  }
}

if (import.meta.main) Deno.serve(handleRequest);
