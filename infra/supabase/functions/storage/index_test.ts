const settings = {
  SUPABASE_URL: "http://127.0.0.1:54321",
  SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
  STORAGE_S3_BUCKET: "materials",
  STORAGE_S3_ENDPOINT: "http://127.0.0.1:54321/storage/v1/s3",
  STORAGE_S3_ACCESS_KEY_ID: "test-access-key",
  STORAGE_S3_SECRET_ACCESS_KEY: "test-secret-key",
};
Object.entries(settings).forEach(([name, value]) => Deno.env.set(name, value));

const { objectKey, validateUpload } = await import("./index.ts");

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

function assertApiError(block: () => unknown, code: string): void {
  try {
    block();
    throw new Error(`expected ${code}`);
  } catch (error) {
    if (!(error instanceof Error) || !error.message) throw error;
    if (!("code" in error) || error.code !== code) throw error;
  }
}
