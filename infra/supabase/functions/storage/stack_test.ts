/**
 * End-to-end test of the storage function against a real local Supabase stack (ADR 0016).
 *
 * The unit tests in `index_test.ts` answer for our own logic with a faked S3 endpoint; a fake can
 * only ever agree with our assumptions about the provider, which is how an unsupported checksum
 * contract survived fifteen pull requests. This file drives the real Supabase Storage S3 protocol
 * through the whole client sequence — `initUpload` → `PUT` every signed part → `completeUpload` →
 * `stat` → download → `delete` — and is the authority on what the provider accepts.
 *
 * It runs only when `STORAGE_STACK_TEST=1`, against a stack started from `infra/` with
 * `supabase start`; `infra/scripts/storage-stack-test.sh` supplies every setting below.
 */
const enabled = Deno.env.get("STORAGE_STACK_TEST") === "1";

const required = (name: string): string => {
    const value = Deno.env.get(name);
    if (!value) throw new Error(`${name} is required when STORAGE_STACK_TEST=1`);
    return value;
};

const PART_SIZE = 8 * 1024 * 1024;

type Signed = {
    number: number;
    offset: number;
    size: number;
    url: string;
    requiredHeaders: Record<string, string>;
};

async function sha256(bytes: Uint8Array<ArrayBuffer>): Promise<Uint8Array> {
    return new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
}

const hex = (bytes: Uint8Array): string => [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
const base64 = (bytes: Uint8Array): string => btoa(String.fromCharCode(...bytes));

function randomBytes(size: number): Uint8Array<ArrayBuffer> {
    const bytes = new Uint8Array(size);
    for (let offset = 0; offset < size; offset += 65_536) {
        crypto.getRandomValues(bytes.subarray(offset, Math.min(size, offset + 65_536)));
    }
    return bytes;
}

async function signedInUser(supabaseUrl: string, serviceKey: string, anonKey: string): Promise<string> {
    const email = `stack-${crypto.randomUUID()}@example.test`;
    const password = crypto.randomUUID();
    const created = await fetch(`${supabaseUrl}/auth/v1/admin/users`, {
        method: "POST",
        headers: { apikey: serviceKey, authorization: "Bearer " + serviceKey, "content-type": "application/json" },
        body: JSON.stringify({ email, password, email_confirm: true }),
    });
    if (!created.ok) throw new Error(`could not create a test user (${created.status}): ${await created.text()}`);
    await created.body?.cancel();
    const session = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=password`, {
        method: "POST",
        headers: { apikey: anonKey, "content-type": "application/json" },
        body: JSON.stringify({ email, password }),
    });
    if (!session.ok) throw new Error(`could not sign the test user in (${session.status})`);
    return (await session.json()).access_token;
}

Deno.test({
    name: "real Supabase Storage accepts the whole upload sequence for one-part and multi-part files",
    ignore: !enabled,
    sanitizeOps: false,
    sanitizeResources: false,
    fn: async () => {
        const supabaseUrl = required("SUPABASE_URL");
        const serviceKey = required("SUPABASE_SERVICE_ROLE_KEY");
        const anonKey = required("SUPABASE_ANON_KEY");
        required("STORAGE_S3_BUCKET");
        required("STORAGE_S3_ENDPOINT");
        required("STORAGE_S3_ACCESS_KEY_ID");
        required("STORAGE_S3_SECRET_ACCESS_KEY");
        const { handleRequest } = await import("./index.ts");
        const token = await signedInUser(supabaseUrl, serviceKey, anonKey);

        const call = async (operation: string, body: unknown): Promise<Response> =>
            await handleRequest(
                new Request(`${supabaseUrl}/functions/v1/storage/${operation}`, {
                    method: "POST",
                    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
                    body: JSON.stringify(body),
                }),
            );

        // One part, a part plus a remainder, and an exact multiple of the part size.
        for (const size of [150_000, PART_SIZE + 1_048_576, 2 * PART_SIZE]) {
            const label = `${size} bytes`;
            const bytes = randomBytes(size);
            const contentHash = hex(await sha256(bytes));
            const partChecksums: string[] = [];
            for (let offset = 0; offset < size; offset += PART_SIZE) {
                partChecksums.push(base64(await sha256(bytes.slice(offset, Math.min(size, offset + PART_SIZE)))));
            }

            const init = await call("initUpload", { contentHash, contentType: "application/pdf", sizeBytes: size, partChecksums });
            const session = await init.json();
            if (init.status !== 200) throw new Error(`${label}: initUpload ${init.status} ${JSON.stringify(session)}`);
            const parts = session.parts as Signed[];
            if (parts.length !== partChecksums.length) throw new Error(`${label}: signed ${parts.length} parts`);

            const receipts = [];
            for (const part of parts) {
                const put = await fetch(part.url, {
                    method: "PUT",
                    headers: part.requiredHeaders,
                    body: bytes.slice(part.offset, part.offset + part.size),
                });
                const etag = put.headers.get("etag");
                const failure = put.ok ? "" : await put.text();
                if (!put.ok || !etag) throw new Error(`${label}: part ${part.number} PUT ${put.status} ${failure}`);
                await put.body?.cancel();
                receipts.push({ number: part.number, etag });
            }

            const complete = await call("completeUpload", {
                uploadId: session.uploadId,
                providerUploadId: session.providerUploadId,
                parts: receipts,
            });
            const stored = await complete.json();
            if (complete.status !== 200 || stored.sizeBytes !== size || stored.contentHash !== contentHash) {
                throw new Error(`${label}: completeUpload ${complete.status} ${JSON.stringify(stored)}`);
            }

            const stat = await call("stat", { contentHash });
            const statBody = await stat.json();
            if (stat.status !== 200 || statBody.sizeBytes !== size) {
                throw new Error(`${label}: stat ${stat.status} ${JSON.stringify(statBody)}`);
            }

            const download = await call("getDownloadUrl", { contentHash });
            const { url } = await download.json();
            if (download.status !== 200) throw new Error(`${label}: getDownloadUrl ${download.status}`);
            const fetched = new Uint8Array(await (await fetch(url)).arrayBuffer());
            if (hex(await sha256(fetched)) !== contentHash) throw new Error(`${label}: downloaded bytes differ`);

            const removed = await call("delete", { contentHash });
            if (removed.status !== 204) throw new Error(`${label}: delete ${removed.status}`);
            const gone = await call("stat", { contentHash });
            await gone.body?.cancel();
            if (gone.status !== 404) throw new Error(`${label}: stat after delete ${gone.status}`);
        }
    },
});
