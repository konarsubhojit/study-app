const settings = {
    SUPABASE_URL: "http://127.0.0.1:54321",
    SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
    GOOGLE_SERVER_CLIENT_ID: "google-client-id.test",
    API_MINIMUM_CLIENT_VERSION: "1.2.3",
    API_BACKUP_RETENTION_DAYS: "30",
    STORAGE_S3_ENDPOINT: "http://127.0.0.1:9000",
    STORAGE_S3_BUCKET: "test-bucket",
    STORAGE_S3_ACCESS_KEY_ID: "test-access-key",
    STORAGE_S3_SECRET_ACCESS_KEY: "test-secret-key",
};
Object.entries(settings).forEach(([name, value]) => Deno.env.set(name, value));

const { handleRequest, observabilityLogLine, resolveRoute, routedPath } = await import("./index.ts");

Deno.test("router strips the Supabase gateway prefix and matches full paths", () => {
    const path = routedPath("/functions/v1/api/v1/auth/signin/challenge");
    if (path !== "/v1/auth/signin/challenge") throw new Error(`unexpected route path ${path}`);
    const route = resolveRoute("POST", path);
    if (route?.operation !== "beginSignIn") throw new Error("challenge route was not resolved");
});

Deno.test("router falls back to direct v1 paths for local tests", () => {
    const route = resolveRoute("POST", routedPath("/v1/auth/refresh"));
    if (route?.operation !== "refreshTokens") throw new Error("refresh route was not resolved");
});

Deno.test("account deletion route requires JWT and is distinct from other endpoints", () => {
    const route = resolveRoute("DELETE", routedPath("/functions/v1/api/v1/account"));
    if (route?.operation !== "deleteAccount" || route.auth !== "jwt") throw new Error("wrong account route policy");
    if (resolveRoute("POST", "/v1/account")) throw new Error("wrong method routed");
});

Deno.test("account deletion rejects missing and invalid JWTs without deleting data", async () => {
    let deletes = 0;
    await withFetch((input, init) => {
        const url = String(input);
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        if (url.endsWith("/auth/v1/user")) return jsonResponse(401, {});
        if (url.includes("/rest/v1/account_deletion_receipts")) return jsonResponse(200, []);
        if (init?.method === "DELETE") deletes++;
        throw new Error("unexpected fetch");
    }, async () => {
        for (const headers of [new Headers(), new Headers({ authorization: "Bearer " + "invalid-test-token" })]) {
            const response = await handleRequest(new Request("https://example.test/v1/account", {
                method: "DELETE", headers,
            }));
            if (response.status !== 401) throw new Error("invalid JWT was accepted");
            if (response.headers.get("x-minimum-client-version") !== "1.2.3") throw new Error("missing version");
        }
    });
    if (deletes !== 0) throw new Error("deletion occurred without verified JWT");
});

Deno.test("account deletion purges storage before Auth, returns configured retention and safe retry", async () => {
    const { S3Client, ListMultipartUploadsCommand, ListObjectsV2Command } =
        await import("npm:@aws-sdk/client-s3@3.1135.0");
    const originalSend = S3Client.prototype.send;
    const calls: string[] = [];
    S3Client.prototype.send = (async (command: unknown) => {
        const name = (command as { constructor: { name: string } }).constructor.name;
        calls.push(name);
        if (command instanceof ListMultipartUploadsCommand) {
            return {
                Uploads: [{ Key: "11111111-1111-1111-1111-111111111111/pending", UploadId: "pending-upload" }],
                IsTruncated: false,
            };
        }
        if (command instanceof ListObjectsV2Command) {
            return { Contents: [{ Key: "11111111-1111-1111-1111-111111111111/file" }], IsTruncated: false };
        }
        if (name === "AbortMultipartUploadCommand" || name === "DeleteObjectCommand") return {};
        throw new Error(`unexpected S3 command ${name}`);
    }) as typeof S3Client.prototype.send;
    let removed = false;
    try {
        await withFetch((input, init) => {
            const url = String(input);
            if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
            if (url.endsWith("/auth/v1/user")) {
                return removed ? jsonResponse(401, {}) : jsonResponse(200, { id: "11111111-1111-1111-1111-111111111111" });
            }
            if (url.includes("/rest/v1/account_deletion_receipts")) {
                if (init?.method === "POST") {
                    calls.push("receipt");
                    return jsonResponse(201, {});
                }
                return jsonResponse(200, [{ user_id: "11111111-1111-1111-1111-111111111111" }]);
            }
            if (url.includes("/auth/v1/admin/users/")) {
                if (init?.method === "DELETE") {
                    calls.push("authDelete");
                    removed = true;
                    return jsonResponse(200, {});
                }
                return jsonResponse(404, {});
            }
            throw new Error(`unexpected fetch ${url}`);
        }, async () => {
            const request = () => new Request("https://example.test/functions/v1/api/v1/account", {
                method: "DELETE",
                headers: { authorization: "Bearer " + "valid-test-token" },
                body: JSON.stringify({ userId: "22222222-2222-2222-2222-222222222222" }),
            });
            const first = await handleRequest(request());
            const receipt = await first.json();
            if (first.status !== 200 || receipt.retentionWindowDays !== 30 ||
                Number.isNaN(Date.parse(receipt.acceptedAt)) || Object.keys(receipt).length !== 2) {
                throw new Error("incorrect account-deletion receipt");
            }
            if (first.headers.get("x-minimum-client-version") !== "1.2.3") throw new Error("missing version");
            const second = await handleRequest(request());
            if (second.status !== 404) throw new Error("retry did not return 404");
        });
    } finally {
        S3Client.prototype.send = originalSend;
    }
    if (calls.join(",") !==
        "ListMultipartUploadsCommand,AbortMultipartUploadCommand,ListObjectsV2Command,DeleteObjectCommand,receipt,authDelete") {
        throw new Error(`unexpected deletion order ${calls.join(",")}`);
    }
});

Deno.test("account deletion preserves Auth account if S3 purge fails", async () => {
    const { S3Client } = await import("npm:@aws-sdk/client-s3@3.1135.0");
    const originalSend = S3Client.prototype.send;
    S3Client.prototype.send = (async () => {
        throw new Error("provider unavailable");
    }) as typeof S3Client.prototype.send;
    let authDeleted = false;
    try {
        await withFetch((input, init) => {
            const url = String(input);
            if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
            if (url.endsWith("/auth/v1/user")) {
                return jsonResponse(200, { id: "11111111-1111-1111-1111-111111111111" });
            }
            if (init?.method === "DELETE") authDeleted = true;
            throw new Error(`unexpected fetch ${url}`);
        }, async () => {
            const response = await handleRequest(new Request("https://example.test/v1/account", {
                method: "DELETE", headers: { authorization: "Bearer " + "valid-test-token" },
            }));
            if (response.status !== 503) throw new Error("storage failure was accepted");
        });
    } finally {
        S3Client.prototype.send = originalSend;
    }
    if (authDeleted) throw new Error("Auth user was deleted despite storage failure");
});

Deno.test("router keeps colliding last path segments distinct", () => {
    if (routedPath("/functions/v1/api/v1/sessions") !== "/v1/sessions") throw new Error("sessions path changed");
    if (routedPath("/functions/v1/api/v1/sync/sessions") !== "/v1/sync/sessions") {
        throw new Error("sync sessions path changed");
    }
    if (resolveRoute("POST", "/v1/sessions")) throw new Error("out-of-scope sessions endpoint was routed");
    if (resolveRoute("POST", "/v1/sync/sessions")) throw new Error("out-of-scope sync endpoint was routed");
});

Deno.test("refresh translates client and GoTrue token field names", async () => {
    const calls: { url: string; body: string }[] = [];
    await withFetch(async (input, init) => {
        const url = String(input);
        if (url.includes("/auth/v1/token")) {
            calls.push({ url, body: String(init?.body) });
            return jsonResponse(200, {
                access_token: "new-access",
                refresh_token: "new-refresh",
                expires_in: 3600,
            });
        }
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${url}`);
    }, async () => {
        const response = await handleRequest(new Request("https://example.test/functions/v1/api/v1/auth/refresh", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ refreshToken: "old-refresh" }),
        }));
        const body = await response.json();
        if (response.status !== 200) throw new Error(`unexpected status ${response.status}`);
        if (body.accessToken !== "new-access" || body.refreshToken !== "new-refresh" || body.expiresInSeconds !== 3600) {
            throw new Error("response fields were not translated");
        }
        if (calls.length !== 1) throw new Error("GoTrue refresh grant was not called once");
        if (!calls[0].url.endsWith("/auth/v1/token?grant_type=refresh_token")) throw new Error("wrong GoTrue grant");
        const gotrueBody = JSON.parse(calls[0].body);
        if (gotrueBody.refresh_token !== "old-refresh" || "refreshToken" in gotrueBody) {
            throw new Error("request fields were not translated");
        }
        if (response.headers.get("x-minimum-client-version") !== "1.2.3") {
            throw new Error("minimum client version header missing");
        }
    });
});

Deno.test("method errors use the shared vocabulary", async () => {
    await withFetch(async (input) => {
        if (String(input).includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${input}`);
    }, async () => {
        const response = await handleRequest(new Request("https://example.test/functions/v1/api/v1/auth/refresh", {
            method: "GET",
        }));
        const body = await response.json();
        if (response.status !== 405 || body.code !== "method_not_allowed") throw new Error("wrong method error");
    });
});

Deno.test("observability logs do not contain credentials or tokens", () => {
    const line = observabilityLogLine({
        requestId: "trace-1",
        operation: "signIn",
        status: 401,
        durationMs: 5,
        errorCode: "invalid_credentials",
    });
    const event = JSON.parse(line);
    if (event.event !== "api_request") throw new Error("wrong event name");
    if ("body" in event || "idToken" in event || "refreshToken" in event || "assertion" in event) {
        throw new Error("unsafe field logged");
    }
    if (line.includes("token") || line.includes("credential-proof")) throw new Error("unsafe value logged");
});

Deno.test("account deletion telemetry contains only the established fields", () => {
    const line = observabilityLogLine({
        requestId: "trace-delete",
        operation: "deleteAccount",
        status: 200,
        durationMs: 6,
        egressBytes: 0,
    });
    const event = JSON.parse(line);
    const expected = ["duration_ms", "egress_bytes", "error_code", "event", "operation", "request_id", "status"];
    if (JSON.stringify(Object.keys(event).sort()) !== JSON.stringify(expected)) {
        throw new Error("unexpected telemetry fields");
    }
    if (line.includes("11111111-1111-1111-1111-111111111111") || line.includes("alice@example.com")) {
        throw new Error("user identity was logged");
    }
});

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
    } finally {
        globalThis.fetch = original;
    }
}
