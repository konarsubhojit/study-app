const settings = {
    SUPABASE_URL: "http://127.0.0.1:54321",
    SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
    STORAGE_S3_BUCKET: "materials",
    STORAGE_S3_ENDPOINT: "http://127.0.0.1:54321/storage/v1/s3",
    STORAGE_S3_ACCESS_KEY_ID: "test-access-key",
    STORAGE_S3_SECRET_ACCESS_KEY: "test-secret-key",
};
Object.entries(settings).forEach(([name, value]) => Deno.env.set(name, value));

const {
    handleRequest,
    objectKey,
    observabilityLogLine,
    observabilityPayload,
    requestTraceId,
    statResponse,
    unavailableLogLine,
    validateUpload,
} = await import("./index.ts");

const checksum = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

Deno.test("object keys are always derived from the authenticated owner", () => {
    const hash = "a".repeat(64);
    if (objectKey("alice", hash) !== `alice/${hash}`) throw new Error("owner prefix was not applied");
    if (objectKey("bob", hash) !== `bob/${hash}`) throw new Error("owner prefix was not applied");
});

Deno.test("upload validation accepts an allowed file and exact part checksums", () => {
    const upload = validateUpload({
        contentHash: "a".repeat(64),
        contentType: "application/pdf",
        sizeBytes: 8 * 1024 * 1024 + 1,
        partChecksums: [checksum, checksum],
    });
    if (upload.checksums.length !== 2) throw new Error("part count was not preserved");
});

Deno.test("upload validation rejects files above the server cap", () => {
    assertApiError(
        () =>
            validateUpload({
                contentHash: "a".repeat(64),
                contentType: "application/pdf",
                sizeBytes: 50 * 1024 * 1024 + 1,
                partChecksums: [],
            }),
        "file_too_large",
    );
});

Deno.test("upload validation rejects disallowed MIME types", () => {
    assertApiError(
        () =>
            validateUpload({
                contentHash: "a".repeat(64),
                contentType: "text/html",
                sizeBytes: 1,
                partChecksums: [checksum],
            }),
        "unsupported_media_type",
    );
});

Deno.test("upload validation rejects missing part checksums", () => {
    assertApiError(
        () =>
            validateUpload({
                contentHash: "a".repeat(64),
                contentType: "application/pdf",
                sizeBytes: 8 * 1024 * 1024 + 1,
                partChecksums: [checksum],
            }),
        "invalid_part_checksums",
    );
});

Deno.test("request tracing accepts only bounded opaque identifiers", () => {
    const accepted = requestTraceId(new Request("https://edge.test/initUpload", {
        headers: { "x-request-id": "trace_01:abc-123" },
    }));
    if (accepted !== "trace_01:abc-123") throw new Error("trace id was not preserved");

    const rejected = requestTraceId(new Request("https://edge.test/initUpload", {
        headers: { "x-request-id": "https://storage.example/signed?token=secret" },
    }));
    if (rejected.includes("storage.example") || rejected.includes("token")) {
        throw new Error("unsafe trace id was preserved");
    }
});

Deno.test("observability logs do not contain user content or presigned urls", () => {
    const line = observabilityLogLine({
        requestId: "trace-1",
        operation: "getDownloadUrl",
        status: 200,
        durationMs: 42,
        egressBytes: 1024,
    });
    const event = JSON.parse(line);
    if (event.event !== "storage_request") throw new Error("wrong event name");
    if ("url" in event || "object_key" in event || "user_id" in event) throw new Error("unsafe field logged");
    if (line.includes("https://") || line.includes("alice")) throw new Error("unsafe value logged");
});

Deno.test("stat reports the stored object without its owner-scoped key", () => {
    const hash = "b".repeat(64);
    const result = statResponse({
        id: "upload-1",
        user_id: "alice",
        object_key: objectKey("alice", hash),
        provider_upload_id: null,
        content_hash: hash,
        content_type: "application/pdf",
        size_bytes: 12,
        part_checksums: [checksum],
        state: "ready",
        completed_at: "2026-03-01T09:00:00+00:00",
    });
    if (result.contentHash !== hash || result.contentType !== "application/pdf" || result.sizeBytes !== 12) {
        throw new Error("stored object was not reported");
    }
    if (result.updatedAt !== "2026-03-01T09:00:00+00:00") throw new Error("completion time was not reported");
    if (JSON.stringify(result).includes("alice")) throw new Error("owner leaked into the response");
});

Deno.test("operations newer than the observability table are recorded as unknown but logged by name", () => {
    const event = { requestId: "trace-1", operation: "stat", status: 404, durationMs: 3, errorCode: "object_not_found" };
    if (observabilityPayload(event).operation !== "unknown") throw new Error("stat would violate the check constraint");
    if (JSON.parse(observabilityLogLine(event)).operation !== "stat") throw new Error("stat was not named in the log");
    if (observabilityPayload({ ...event, operation: "delete" }).operation !== "delete") {
        throw new Error("a recorded operation was renamed");
    }
});

const OWNER = "00000000-0000-4000-8000-000000000001";
const TOKEN = "valid-test-token";

Deno.test("a well-formed stat request reads once and reports stored metadata or a missing object", async () => {
    const upload = {
        id: "upload-1",
        user_id: OWNER,
        object_key: objectKey(OWNER, "c".repeat(64)),
        provider_upload_id: null,
        content_hash: "c".repeat(64),
        content_type: "application/pdf",
        size_bytes: 12,
        part_checksums: [checksum],
        state: "ready",
        completed_at: "2026-03-01T09:00:00+00:00",
    };
    for (const rows of [[], [upload]]) {
        const request = statRequest();
        let reads = 0;
        const originalJson = request.json.bind(request);
        request.json = () => {
            reads++;
            return originalJson();
        };
        const original = request.text.bind(request);
        request.text = () => {
            reads++;
            return original();
        };
        await withFetch((input) => {
            const url = String(input);
            if (url.includes("/rest/v1/storage_uploads")) {
                if (!url.includes(`user_id=eq.${OWNER}`) || !url.includes(`object_key=eq.${encodeURIComponent(upload.object_key)}`)) {
                    throw new Error("stat lookup was not owner scoped");
                }
                return jsonResponse(200, rows);
            }
            return storageBackend(200)(input);
        }, async () => {
            const response = await handleRequest(request);
            const result = await response.json();
            if (rows.length === 0) {
                if (response.status !== 404 || result.code !== "object_not_found") {
                    throw new Error(`valid stat became ${response.status} ${result.code}`);
                }
            } else if (response.status !== 200 || JSON.stringify(result) !== JSON.stringify(statResponse(upload))) {
                throw new Error("stat did not return stored metadata");
            }
            if (!request.bodyUsed) throw new Error("stat did not consume its body");
            if (reads !== 1) throw new Error(`stat read its body ${reads} times`);
        });
    }
});

Deno.test("storage distinguishes invalid body shapes without logging their contents", async () => {
    const cases = [
        ["", "empty_body"],
        [" \n ", "empty_body"],
        ['{"private-note":', "invalid_json"],
        ['["private-note"]', "json_array"],
        ['"private-note"', "json_string"],
        ["null", "json_null"],
        ["1", "json_number"],
        ["true", "json_boolean"],
    ];
    const lines: string[] = [];
    const original = console.warn;
    console.warn = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    try {
        await withFetch(storageBackend(200), async () => {
            for (const [payload, reason] of cases) {
                const response = await handleRequest(new Request(statRequest(), { body: payload }));
                const result = await response.json();
                if (response.status !== 400 || result.code !== "invalid_request") {
                    throw new Error(`invalid body became ${response.status} ${result.code}`);
                }
                const diagnostic = JSON.parse(lines.at(-1) ?? "{}");
                if (diagnostic.event !== "request_body_invalid" || diagnostic.reason !== reason) {
                    throw new Error(`missing body diagnostic ${reason}`);
                }
            }
        });
    } finally {
        console.warn = original;
    }
    if (lines.join("").includes("private-note")) throw new Error("request contents were logged");
});

Deno.test("a consumed storage request stream is a retryable server failure", async () => {
    const request = statRequest();
    await request.text();
    await withFetch(storageBackend(200), async () => {
        const response = await handleRequest(request);
        const result = await response.json();
        if (response.status !== 503 || result.code !== "storage_unavailable") {
            throw new Error(`consumed stream became ${response.status} ${result.code}`);
        }
    });
});

Deno.test("a locked storage request stream is a retryable server failure", async () => {
    const request = statRequest();
    const reader = request.body!.getReader();
    try {
        await withFetch(storageBackend(200), async () => {
            const response = await handleRequest(request);
            const result = await response.json();
            if (response.status !== 503 || result.code !== "storage_unavailable") {
                throw new Error(`locked stream became ${response.status} ${result.code}`);
            }
        });
    } finally {
        reader.releaseLock();
        await request.body!.cancel();
    }
});

Deno.test("an upstream refusal is diagnosed separately from invalid client JSON", async () => {
    const lines: string[] = [];
    const original = console.warn;
    console.warn = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    try {
        await withFetch((input) => {
            if (String(input).includes("/rest/v1/rpc/storage_consume_rate_limit")) {
                return jsonResponse(400, { code: "23514", message: "private-note", details: OWNER });
            }
            return storageBackend(200)(input);
        }, async () => {
            const response = await handleRequest(statRequest());
            const result = await response.json();
            if (response.status !== 503 || result.code !== "storage_unavailable") {
                throw new Error("a server query failure was reported as invalid client input");
            }
        });
    } finally {
        console.warn = original;
    }
    const diagnostic = JSON.parse(lines[0] ?? "{}");
    if (
        diagnostic.event !== "storage_upstream_rejected" || diagnostic.status !== 400 ||
        diagnostic.resource !== "rpc/storage_consume_rate_limit" || diagnostic.code !== "23514"
    ) throw new Error("upstream rejection was not diagnosed");
    if (lines.join("").includes("private-note") || lines.join("").includes(OWNER)) {
        throw new Error("upstream details leaked");
    }
});

Deno.test("unmatched database refusals are retryable storage outages", async () => {
    for (const [upstreamStatus, upstreamCode] of [
        [400, "42P01"],
        [403, "42501"],
        [404, "PGRST202"],
        [409, "23505"],
        [500, "unknown"],
        [502, "unknown"],
    ] as const) {
        await withFetch(storageBackend(upstreamStatus, { code: upstreamCode }), async () => {
            const response = await handleRequest(statRequest());
            const body = await response.json();
            if (response.status !== 503 || body.code !== "storage_unavailable") {
                throw new Error(`database ${upstreamStatus} became ${response.status} ${body.code}`);
            }
        });
    }
});

Deno.test("explicit storage client errors keep their status and retry policy", async () => {
    for (const [code, expectedStatus] of [
        ["rate_limited", 429],
        ["quota_exceeded", 413],
        ["object_already_exists", 409],
    ] as const) {
        await withFetch(storageBackend(400, { message: code, details: "82" }), async () => {
            const response = await handleRequest(statRequest());
            const body = await response.json();
            if (response.status !== expectedStatus || body.code !== code) {
                throw new Error(`${code} became ${response.status} ${body.code}`);
            }
            const retryAfter = response.headers.get("retry-after");
            if (retryAfter !== (code === "rate_limited" ? "60" : null)) {
                throw new Error(`${code} changed its retry policy`);
            }
            if (code === "quota_exceeded" && body.details?.availableBytes !== "82") {
                throw new Error("quota details were lost");
            }
        });
    }
});

Deno.test("an unexpected storage failure is logged with its message and operation, and nothing else", async () => {
    const lines: string[] = [];
    const original = console.error;
    console.error = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    try {
        await withFetch(storageBackend(500), async () => {
            const response = await handleRequest(statRequest());
            await response.body?.cancel();
            if (response.status !== 503) throw new Error("an upstream 500 was not reported as unavailable");
        });
    } finally {
        console.error = original;
    }
    const line = lines.find((entry) => entry.startsWith("storage_unavailable"));
    if (!line) throw new Error("no storage_unavailable line was logged");
    for (const expected of ["operation=stat", "name=UpstreamError", "message=Database request failed (500)"]) {
        if (!line.includes(expected)) throw new Error(`log line "${line}" is missing ${expected}`);
    }
    if (line.includes(TOKEN) || line.includes("Bearer") || line.includes("    at ") || line.includes(OWNER)) {
        throw new Error(`log line "${line}" carried a credential, an owner id or a stack trace`);
    }
});

Deno.test("the catch-all log line redacts a signed URL quoted in an error message", () => {
    const signed = "https://bucket.example/materials/abc?X-Amz-Signature=deadbeef&X-Amz-Credential=AKIA";
    const line = unavailableLogLine("initUpload", new Error(`PUT ${signed} failed`));
    if (!line.includes("operation=initUpload") || !line.includes("message=PUT <url> failed")) {
        throw new Error(`log line "${line}" lost its operation or message`);
    }
    if (line.includes("X-Amz") || line.includes("bucket.example") || line.includes("AKIA")) {
        throw new Error(`log line "${line}" leaked the signed URL`);
    }
    if (unavailableLogLine("stat", "not an error") !== "storage_unavailable operation=stat") {
        throw new Error("a non-Error value was not logged by operation alone");
    }
});

function statRequest(): Request {
    return new Request("https://edge.test/functions/v1/storage/stat", {
        method: "POST",
        headers: { authorization: "Bearer " + TOKEN, "content-type": "application/json" },
        body: JSON.stringify({ contentHash: "c".repeat(64) }),
    });
}

const pendingUpload = {
    id: "upload-1",
    user_id: OWNER,
    object_key: objectKey(OWNER, "c".repeat(64)),
    provider_upload_id: "provider-1",
    content_hash: "c".repeat(64),
    content_type: "application/pdf",
    size_bytes: 12,
    part_checksums: [checksum],
    state: "pending",
};
const claimTime = "2026-10-05T00:00:00.000Z";

function uploadRequest(operation: string, value: unknown): Request {
    return new Request(`https://edge.test/functions/v1/storage/${operation}`, {
        method: "POST",
        headers: { authorization: "Bearer " + TOKEN, "x-request-id": "completion-test" },
        body: JSON.stringify(value),
    });
}

const completionRequest = () => uploadRequest("completeUpload", {
    uploadId: pendingUpload.id,
    parts: [{ number: 1, etag: "\"part-etag\"" }],
});

const initializationRequest = () => uploadRequest("initUpload", {
    contentHash: pendingUpload.content_hash,
    contentType: pendingUpload.content_type,
    sizeBytes: pendingUpload.size_bytes,
    partChecksums: [checksum],
});

async function withUploadBackend(
    provider: (request: Request) => Promise<Response> | Response,
    block: (patches: Array<{ url: string; value: Record<string, unknown> }>) => Promise<void>,
    options: {
        upload?: typeof pendingUpload;
        claim?: string | null;
        finalize?: boolean;
        reuse?: boolean;
        loseReplacement?: boolean;
        failAudit?: boolean;
    } = {},
): Promise<void> {
    const patches: Array<{ url: string; value: Record<string, unknown> }> = [];
    await withFetch(async (input, init) => {
        const url = input instanceof Request ? input.url : String(input);
        if (input instanceof Request && !url.includes("/rest/v1/") && !url.endsWith("/auth/v1/user")) {
            return await provider(input);
        }
        if (url.includes("/rpc/storage_claim_completion")) {
            const value = JSON.parse(String(init?.body));
            if (value.p_provider_upload_id !== (options.upload ?? pendingUpload).provider_upload_id) {
                throw new Error("completion did not fence its claim on the provider handle it read");
            }
            return jsonResponse(200, options.claim === null ? null : claimTime);
        }
        if (url.includes("/rest/v1/storage_url_audit")) return jsonResponse(options.failAudit ? 500 : 201, {});
        if (url.includes("/rpc/storage_finalize_upload")) return jsonResponse(200, options.finalize ?? true);
        if (url.includes("/rpc/storage_reserve_upload")) {
            return jsonResponse(200, [{
                upload_id: pendingUpload.id,
                created: !options.reuse,
                expires_at: "2026-10-06T00:00:00Z",
                provider_upload_id: options.reuse ? pendingUpload.provider_upload_id : null,
            }]);
        }
        if (url.includes("/rest/v1/storage_uploads")) {
            if (init?.method === "PATCH") {
                patches.push({ url, value: JSON.parse(String(init.body)) });
                if (options.loseReplacement && patches.at(-1)?.value.provider_upload_id) return jsonResponse(200, []);
                return jsonResponse(200, [{ ...pendingUpload, state: "completing" }]);
            }
            return jsonResponse(200, [options.upload ?? pendingUpload]);
        }
        return storageBackend(200)(input);
    }, () => block(patches));
}

Deno.test("a broken completion response is awaited, logged and returned as structured JSON", async () => {
    const logs: string[] = [];
    const original = console.info;
    console.info = (...args: unknown[]) => void logs.push(args.map(String).join(" "));
    let completed = false;
    try {
        await withUploadBackend((request) => {
            if (request.method === "HEAD") return new Response(null, { status: 404 });
            if (request.method !== "POST") throw new Error("unexpected S3 command");
            completed = true;
            return new Response(new ReadableStream({
                start(controller) {
                    controller.enqueue(new TextEncoder().encode("<CompleteMultipartUploadResult>"));
                    controller.error(new TypeError("reached unexpected EOF"));
                },
            }), { headers: { "content-type": "application/xml" } });
        }, async (patches) => {
            const response = await handleRequest(completionRequest());
            const value = await response.json();
            if (!completed) throw new Error("completion did not use the catchable fetch transport");
            if (response.status !== 503 || value.code !== "storage_unavailable") {
                throw new Error("completion did not return a structured outage");
            }
            if (response.headers.get("x-request-id") !== "completion-test") throw new Error("trace was lost");
            const released = patches.at(-1);
            if (released?.value.state !== "pending" || !released.url.includes("state=eq.completing") ||
                !released.url.includes(`completing_at=eq.${encodeURIComponent(claimTime)}`)) {
                throw new Error("unexpected failures must release only their own claim to pending");
            }
        });
    } finally {
        console.info = original;
    }
    const event = logs.map((line) => JSON.parse(line)).find((entry) => entry.event === "storage_request");
    if (event?.status !== 503 || event.operation !== "completeUpload" || event.error_code !== "storage_unavailable") {
        throw new Error("completion outage was not logged");
    }
});

Deno.test("multipart creation, signing and completion send no provider checksums", async () => {
    let creation = false;
    let completion = false;
    let exists = false;
    let completionXml = "";
    await withUploadBackend(async (request) => {
        const url = new URL(request.url);
        if (request.method === "HEAD") {
            return new Response(null, { status: exists ? 200 : 404, headers: exists ? { "content-length": "12" } : {} });
        }
        if (url.searchParams.has("uploads")) {
            creation = true;
            // Supabase Storage creates its backing upload without a checksum algorithm; asking for
            // one here is what made every completion fail with InvalidPart.
            const checksumHeaders = [...request.headers.keys()].filter((name) => name.includes("checksum"));
            if (checksumHeaders.length > 0) {
                throw new Error(`creation sent provider checksum headers: ${checksumHeaders}`);
            }
            return new Response("<InitiateMultipartUploadResult><UploadId>provider-1</UploadId></InitiateMultipartUploadResult>");
        }
        completion = true;
        completionXml = await request.text();
        const expected = '<?xml version="1.0" encoding="UTF-8"?>' +
            '<CompleteMultipartUpload xmlns="http://s3.amazonaws.com/doc/2006-03-01/">' +
            "<Part><ETag>&quot;part-etag&quot;</ETag><PartNumber>1</PartNumber></Part>" +
            "</CompleteMultipartUpload>";
        if (completionXml !== expected || url.searchParams.get("uploadId") !== "provider-1") {
            throw new Error(`completion command shape drifted: ${completionXml}`);
        }
        exists = true;
        return new Response("<CompleteMultipartUploadResult><ETag>object-etag</ETag></CompleteMultipartUploadResult>");
    }, async () => {
        const response = await handleRequest(uploadRequest("initUpload", {
            contentHash: pendingUpload.content_hash,
            contentType: pendingUpload.content_type,
            sizeBytes: pendingUpload.size_bytes,
            partChecksums: [checksum],
        }));
        const value = await response.json();
        const signed = new URL(value.parts[0].url);
        const signedChecksums = [...signed.searchParams.keys()].filter((name) => name.includes("checksum"));
        if (response.status !== 200 || Object.keys(value.parts[0].requiredHeaders).length !== 0 ||
            signedChecksums.length > 0 || !signed.searchParams.get("X-Amz-SignedHeaders")?.split(";").every(
                (header) => header === "host",
            )) {
            throw new Error(`part signing must not require provider checksums: ${signed.search}`);
        }
        const completed = await handleRequest(completionRequest());
        const completionResult = await completed.json();
        if (completed.status !== 200 || !creation || !completion) {
            throw new Error(
                `multipart contract did not complete: status=${completed.status} body=${JSON.stringify(completionResult)} ` +
                    `creation=${creation} completion=${completion} xml=${completionXml}`,
            );
        }
    });
});

Deno.test("an invalid S3 part identity is permanent and fails its completion claim", async () => {
    await withUploadBackend((request) => {
        if (request.method === "HEAD") return new Response(null, { status: 404 });
        return new Response(
            "<Error><Code>InvalidPart</Code><Message>entity tag mismatch</Message></Error>",
            { status: 400, headers: { "content-type": "application/xml" } },
        );
    }, async (patches) => {
        const response = await handleRequest(completionRequest());
        const value = await response.json();
        if (response.status !== 422 || value.code !== "invalid_parts" || patches.at(-1)?.value.state !== "failed") {
            throw new Error("an invalid part identity was reported as retryable or left reserved");
        }
    });
});

Deno.test("reuse validates a live provider upload before signing without replacing it", async () => {
    let listed = false;
    await withUploadBackend((request) => {
        const url = new URL(request.url);
        if (request.method !== "GET" || url.searchParams.get("uploadId") !== "provider-1") {
            throw new Error("live reuse should only list parts");
        }
        listed = true;
        return new Response("<ListPartsResult><UploadId>provider-1</UploadId></ListPartsResult>");
    }, async (patches) => {
        const response = await handleRequest(initializationRequest());
        const value = await response.json();
        if (response.status !== 200 || !listed || patches.length !== 0 ||
            value.providerUploadId !== "provider-1" ||
            new URL(value.parts[0].url).searchParams.get("uploadId") !== "provider-1") {
            throw new Error("live provider reuse changed its handle or skipped validation");
        }
    }, { reuse: true });
});

Deno.test("reuse replaces a dead provider upload and signs every part with its new handle", async () => {
    const operations: string[] = [];
    await withUploadBackend((request) => {
        const url = new URL(request.url);
        if (request.method === "GET") {
            operations.push("list");
            return new Response("<Error><Code>NoSuchUpload</Code><Message>Upload is gone</Message></Error>", { status: 404 });
        }
        if (url.searchParams.has("uploads")) {
            operations.push("create");
            return new Response("<InitiateMultipartUploadResult><UploadId>provider-2</UploadId></InitiateMultipartUploadResult>");
        }
        throw new Error("unexpected provider operation");
    }, async (patches) => {
        const response = await handleRequest(initializationRequest());
        const value = await response.json();
        const replaced = patches[0];
        if (response.status !== 200 || operations.join(",") !== "list,create" ||
            value.uploadId !== pendingUpload.id || value.providerUploadId !== "provider-2" ||
            new URL(value.parts[0].url).searchParams.get("uploadId") !== "provider-2" ||
            replaced.value.provider_upload_id !== "provider-2" ||
            JSON.stringify(replaced.value.part_checksums) !== JSON.stringify([checksum]) ||
            !replaced.url.includes("state=eq.pending&provider_upload_id=eq.provider-1")) {
            throw new Error("dead provider reuse did not atomically replace its handle and checksums");
        }
    }, { reuse: true });
});

for (const incompatible of [
    { part_checksums: [checksum, checksum] },
    { part_checksums: ["different-checksum"] },
    { size_bytes: 13 },
    { content_type: "text/plain" },
]) {
    Deno.test(`reuse retires incompatible stored metadata: ${Object.keys(incompatible)[0]} ${JSON.stringify(incompatible)}`, async () => {
        await withUploadBackend(() => {
            throw new Error("incompatible metadata must not reach the provider");
        }, async (patches) => {
            const response = await handleRequest(initializationRequest());
            const value = await response.json();
            if (response.status !== 409 || value.code !== "upload_initializing" ||
                patches.at(-1)?.value.state !== "failed") {
                throw new Error("incompatible pending metadata was reused or permanently blocked the next initialization");
            }
        }, { reuse: true, upload: { ...pendingUpload, ...incompatible } });
    });
}

Deno.test("a replacement losing its fence aborts only its new upload and leaves the winning row alone", async () => {
    let aborted = false;
    await withUploadBackend((request) => {
        const url = new URL(request.url);
        if (request.method === "GET") {
            throw Object.assign(new Error("Upload is gone"), { name: "S3Error", Code: "NoSuchUpload" });
        }
        if (request.method === "DELETE" && url.searchParams.get("uploadId") === "provider-2") {
            aborted = true;
            return new Response(null, { status: 204 });
        }
        return new Response("<InitiateMultipartUploadResult><UploadId>provider-2</UploadId></InitiateMultipartUploadResult>");
    }, async (patches) => {
        const response = await handleRequest(initializationRequest());
        const value = await response.json();
        if (response.status !== 409 || value.code !== "upload_in_progress" || !aborted ||
            patches.some((patch) => patch.value.state === "failed")) {
            throw new Error("a losing replacement aborted the wrong upload or failed the winning row");
        }
    }, { reuse: true, loseReplacement: true });
});

Deno.test("a replacement signing failure retires its row before aborting the new handle", async () => {
    let aborted = false;
    await withUploadBackend((request) => {
        if (request.method === "GET") {
            throw Object.assign(new Error("Upload is gone"), { name: "S3Error", code: "NoSuchUpload" });
        }
        if (request.method === "DELETE") {
            aborted = true;
            return new Response(null, { status: 204 });
        }
        return new Response("<InitiateMultipartUploadResult><UploadId>provider-2</UploadId></InitiateMultipartUploadResult>");
    }, async (patches) => {
        const response = await handleRequest(initializationRequest());
        if (response.status !== 503 || !aborted || patches.at(-1)?.value.state !== "failed" ||
            !patches.at(-1)?.url.includes("state=eq.pending&provider_upload_id=eq.provider-2")) {
            throw new Error("failed initialization left a reused dead handle pending");
        }
    }, { reuse: true, failAudit: true });
});

for (const code of ["InvalidPart", "InvalidPartOrder", "NoSuchUpload"]) {
    for (const field of ["Code", "code"]) {
        Deno.test(`S3Error ${field}=${code} rejects stale parts and fails only its completion claim`, async () => {
            await withUploadBackend((request) => {
                if (request.method === "HEAD") return new Response(null, { status: 404 });
                throw Object.assign(new Error(
                    "One or more of the specified parts could not be found. " +
                        "The part may not have been uploaded, or the specified entity tag may not match the part's entity tag.",
                ), {
                    name: "S3Error",
                    [field]: code,
                    $metadata: { httpStatusCode: code === "NoSuchUpload" ? 404 : 400 },
                });
            }, async (patches) => {
                const response = await handleRequest(completionRequest());
                const value = await response.json();
                const released = patches.at(-1);
                if (response.status !== 422 || value.code !== "invalid_parts" ||
                    released?.value.state !== "failed" || released.value.completing_at !== null ||
                    !released.url.includes("state=eq.completing&completing_at=eq." + encodeURIComponent(claimTime))) {
                    throw new Error("S3Error was reported as an outage or its completion claim was not failed");
                }
            });
        });
    }
}

Deno.test("a second completion cannot proceed without winning a claim", async () => {
    await withUploadBackend(() => {
        throw new Error("a losing claimant touched S3");
    }, async (patches) => {
        const response = await handleRequest(completionRequest());
        const value = await response.json();
        if (response.status !== 409 || value.code !== "upload_in_progress" || patches.length !== 0) {
            throw new Error("a losing claimant proceeded");
        }
    }, { upload: { ...pendingUpload, state: "completing" }, claim: null });
});

Deno.test("stale provider receipts cannot fail a replacement reservation", async () => {
    await withUploadBackend(() => {
        throw new Error("stale receipts touched the replacement provider");
    }, async (patches) => {
        const response = await handleRequest(uploadRequest("completeUpload", {
            uploadId: pendingUpload.id,
            providerUploadId: "dead-provider",
            parts: [{ number: 1, etag: "dead-etag" }],
        }));
        const value = await response.json();
        if (response.status !== 422 || value.code !== "invalid_parts" || patches.length !== 0) {
            throw new Error("stale receipts changed or failed the replacement reservation");
        }
    });
});

Deno.test("a completed provider object is recovered without completing again", async () => {
    await withUploadBackend((request) => {
        if (request.method !== "HEAD") throw new Error("recovery repeated provider completion");
        return new Response(null, { headers: { "content-length": "12" } });
    }, async () => {
        const response = await handleRequest(completionRequest());
        if (response.status !== 200) throw new Error("provider success was not recovered");
        await response.body?.cancel();
    });
});

Deno.test("provider success followed by a lost response is recovered on the next completion", async () => {
    let exists = false;
    let completions = 0;
    await withUploadBackend((request) => {
        if (request.method === "HEAD") {
            return new Response(null, { status: exists ? 200 : 404, headers: exists ? { "content-length": "12" } : {} });
        }
        completions++;
        exists = true;
        return new Response(new ReadableStream({
            start(controller) {
                controller.error(new TypeError("reached unexpected EOF"));
            }
        }));
    }, async (patches) => {
        const failed = await handleRequest(completionRequest());
        await failed.body?.cancel();
        if (failed.status !== 503 || patches.at(-1)?.value.state !== "pending") {
            throw new Error("ambiguous completion was not made retryable");
        }
        const recovered = await handleRequest(completionRequest());
        await recovered.body?.cancel();
        if (recovered.status !== 200 || completions !== 1) {
            throw new Error("recovery repeated completion instead of finalizing the existing object");
        }
    });
});

Deno.test("a confirmed size mismatch deletes the object and fails only the owning claim", async () => {
    let deleted = false;
    await withUploadBackend((request) => {
        if (request.method === "DELETE") {
            deleted = true;
            return new Response(null, { status: 204 });
        }
        return new Response(null, { headers: { "content-length": "13" } });
    }, async (patches) => {
        const response = await handleRequest(completionRequest());
        const value = await response.json();
        if (response.status !== 422 || value.code !== "integrity_mismatch" || !deleted ||
            patches.at(-1)?.value.state !== "failed") {
            throw new Error("confirmed integrity rejection was not cleaned up");
        }
    });
});

Deno.test("database finalization failure preserves a completed provider object for retry", async () => {
    await withUploadBackend(() => new Response(null, { headers: { "content-length": "12" } }), async (patches) => {
        const response = await handleRequest(completionRequest());
        await response.body?.cancel();
        if (response.status !== 503 || patches.length !== 0) {
            throw new Error("a completed provider object lost its completion lease");
        }
    }, { finalize: false });
});

Deno.test("stat distinguishes active completion from a reclaimable claim", async () => {
    for (const state of ["completing", "reaping"]) {
        for (const expired of [false, true]) {
            const upload = {
                ...pendingUpload,
                state,
                completing_at: new Date(Date.now() - (expired ? 16 : 1) * 60_000).toISOString(),
            };
            await withFetch((input) => {
                if (String(input).includes("/rest/v1/storage_uploads")) return jsonResponse(200, [upload]);
                return storageBackend(200)(input);
            }, async () => {
                const response = await handleRequest(statRequest());
                const value = await response.json();
                const reclaimable = state === "completing" && expired;
                if (response.status !== (reclaimable ? 404 : 409) ||
                    value.code !== (reclaimable ? "object_not_found" : "upload_in_progress")) {
                    throw new Error("stat hid an active claim or blocked a reclaimable upload");
                }
            });
        }
    }
});

Deno.test("stat and initUpload agree that an active completion is in progress", async () => {
    const upload = {
        ...pendingUpload,
        state: "completing",
        completing_at: new Date().toISOString(),
    };
    await withFetch((input) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: OWNER });
        if (url.includes("/rpc/storage_consume_rate_limit")) return jsonResponse(200, null);
        if (url.includes("/rest/v1/storage_uploads")) return jsonResponse(200, [upload]);
        if (url.includes("/rpc/storage_reserve_upload")) {
            return jsonResponse(400, { message: "upload_in_progress" });
        }
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${url}`);
    }, async () => {
        const stat = await handleRequest(statRequest());
        const init = await handleRequest(uploadRequest("initUpload", {
            contentHash: upload.content_hash,
            contentType: upload.content_type,
            sizeBytes: upload.size_bytes,
            partChecksums: [checksum],
        }));
        const [statBody, initBody] = await Promise.all([stat.json(), init.json()]);
        if (stat.status !== 409 || init.status !== stat.status ||
            statBody.code !== "upload_in_progress" || initBody.code !== statBody.code) {
            throw new Error("stat and initUpload disagreed about an active completion");
        }
    });
});

/** Auth and rate limiting succeed; the `storage_uploads` lookup answers [uploadsStatus]. */
function storageBackend(
    uploadsStatus: number,
    uploadsBody: unknown = { message: "upstream said no" },
): (input: RequestInfo | URL) => Response {
    return (input) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: OWNER });
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        if (url.includes("/rest/v1/rpc/storage_consume_rate_limit")) return jsonResponse(200, null);
        if (url.includes("/rest/v1/storage_uploads")) return jsonResponse(uploadsStatus, uploadsBody);
        throw new Error(`unexpected fetch ${url}`);
    };
}

function jsonResponse(status: number, body: unknown): Response {
    return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

async function withFetch(
    replacement: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response> | Response,
    block: () => Promise<void>,
): Promise<void> {
    const original = globalThis.fetch;
    globalThis.fetch = replacement as typeof fetch;
    try {
        await block();
        // Observability is written after the response; let it land on the stub, not the network.
        await new Promise((resolve) => setTimeout(resolve, 0));
    } finally {
        globalThis.fetch = original;
    }
}

function assertApiError(block: () => unknown, code: string): void {
    try {
        block();
        throw new Error(`expected ${code}`);
    } catch (error) {
        if (!(error instanceof Error) || !error.message) throw error;
        if (!("code" in error) || error.code !== code) throw error;
    }
}
