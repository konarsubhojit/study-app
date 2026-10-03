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

Deno.test("a well-formed stat request reads its body once and reports a missing object", async () => {
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
        if (String(input).includes("/rest/v1/storage_uploads")) return jsonResponse(200, []);
        return storageBackend(200)(input);
    }, async () => {
        const response = await handleRequest(request);
        const result = await response.json();
        if (response.status !== 404 || result.code !== "object_not_found") {
            throw new Error(`valid stat became ${response.status} ${result.code}`);
        }
        if (!request.bodyUsed) throw new Error("stat did not consume its body");
        if (reads !== 1) throw new Error(`stat read its body ${reads} times`);
    });
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
            if (response.status !== 400 || result.code !== "invalid_request") {
                throw new Error("upstream client-error mapping changed");
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

Deno.test("a database refusal inside a storage operation is a client error, not a retryable outage", async () => {
    for (const [upstreamStatus, expectedStatus, expectedCode] of [
        [400, 400, "invalid_request"],
        [403, 400, "invalid_request"],
        [409, 409, "conflict"],
        [500, 503, "storage_unavailable"],
        [502, 503, "storage_unavailable"],
    ] as const) {
        await withFetch(storageBackend(upstreamStatus), async () => {
            const response = await handleRequest(statRequest());
            const body = await response.json();
            if (response.status !== expectedStatus || body.code !== expectedCode) {
                throw new Error(`database ${upstreamStatus} became ${response.status} ${body.code}`);
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

/** Auth and rate limiting succeed; the `storage_uploads` lookup answers [uploadsStatus]. */
function storageBackend(uploadsStatus: number): (input: RequestInfo | URL) => Response {
    return (input) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: OWNER });
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        if (url.includes("/rest/v1/rpc/storage_consume_rate_limit")) return jsonResponse(200, null);
        if (url.includes("/rest/v1/storage_uploads")) return jsonResponse(uploadsStatus, { message: "upstream said no" });
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
