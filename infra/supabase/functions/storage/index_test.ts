const settings = {
    SUPABASE_URL: "http://127.0.0.1:54321",
    SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
    STORAGE_S3_BUCKET: "materials",
    STORAGE_S3_ENDPOINT: "http://127.0.0.1:54321/storage/v1/s3",
    STORAGE_S3_ACCESS_KEY_ID: "test-access-key",
    STORAGE_S3_SECRET_ACCESS_KEY: "test-secret-key",
};
Object.entries(settings).forEach(([name, value]) => Deno.env.set(name, value));

const { objectKey, observabilityLogLine, observabilityPayload, requestTraceId, statResponse, validateUpload } =
    await import("./index.ts");

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

function assertApiError(block: () => unknown, code: string): void {
    try {
        block();
        throw new Error(`expected ${code}`);
    } catch (error) {
        if (!(error instanceof Error) || !error.message) throw error;
        if (!("code" in error) || error.code !== code) throw error;
    }
}
