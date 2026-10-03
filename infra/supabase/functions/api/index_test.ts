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
    PASSKEY_RP_ID: "studyflow.test",
    PASSKEY_ANDROID_ORIGIN: "android:apk-key-hash:test-signing-certificate",
};
Object.entries(settings).forEach(([name, value]) => Deno.env.set(name, value));

const { decodeSyncCursor, encodeSyncCursor, handleRequest, observabilityLogLine, resolveRoute, routedPath } = await import(
    "./index.ts"
);

Deno.test("router strips the Supabase gateway prefix and matches full paths", () => {
    const path = routedPath("/functions/v1/api/v1/auth/signin/challenge");
    if (path !== "/v1/auth/signin/challenge") throw new Error(`unexpected route path ${path}`);
    const route = resolveRoute("POST", path);
    if (route?.operation !== "beginSignIn") throw new Error("challenge route was not resolved");
});

Deno.test("api distinguishes invalid body shapes without logging their contents", async () => {
    const lines: string[] = [];
    const original = console.warn;
    console.warn = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    try {
        await withFetch(() => jsonResponse(201, {}), async () => {
            for (
                const [payload, reason] of [
                    ["", "empty_body"],
                    [" \n ", "empty_body"],
                    ['{"private-note":', "invalid_json"],
                    ['["private-note"]', "json_array"],
                    ['"private-note"', "json_string"],
                    ["null", "json_null"],
                    ["1", "json_number"],
                    ["true", "json_boolean"],
                ]
            ) {
                const response = await handleRequest(new Request("https://edge.test/v1/auth/refresh", {
                    method: "POST",
                    headers: { "content-type": "application/json" },
                    body: payload,
                }));
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

Deno.test("a consumed api request stream is a retryable server failure", async () => {
    const request = new Request("https://edge.test/v1/auth/refresh", {
        method: "POST",
        body: JSON.stringify({ refreshToken: "private-note" }),
    });
    await request.text();
    await withFetch(() => jsonResponse(201, {}), async () => {
        const response = await handleRequest(request);
        const result = await response.json();
        if (response.status !== 503 || result.code !== "api_unavailable") {
            throw new Error(`consumed stream became ${response.status} ${result.code}`);
        }
    });
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

Deno.test("list routes require JWT and are distinct", () => {
    const subjects = resolveRoute("GET", routedPath("/functions/v1/api/v1/subjects"));
    const tasks = resolveRoute("GET", routedPath("/functions/v1/api/v1/tasks"));
    if (subjects?.operation !== "listSubjects" || subjects.auth !== "jwt") throw new Error("wrong subjects route policy");
    if (tasks?.operation !== "listTasks" || tasks.auth !== "jwt") throw new Error("wrong tasks route policy");
    if (resolveRoute("POST", "/v1/tasks")) throw new Error("wrong method routed");
});

Deno.test("subjects map signed ARGB colours and explicitly scope service-role reads to the JWT owner", async () => {
    await withFetch((input) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: "11111111-1111-1111-1111-111111111111" });
        if (url.includes("/rest/v1/subjects?")) {
            if (!url.includes("user_id=eq.11111111-1111-1111-1111-111111111111") || !url.includes("deleted_at=is.null")) {
                throw new Error("subject list was not constrained to the verified owner and active rows");
            }
            return jsonResponse(200, [
                { id: "s1", name: "Maths", color_argb: -16711936 },
                { id: "s2", name: "History", color_argb: 0x7F2E7D32 },
            ]);
        }
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${url}`);
    }, async () => {
        const response = await handleRequest(new Request("https://example.test/v1/subjects", {
            headers: { authorization: ["Bearer", "valid-test-token"].join(" ") },
        }));
        const body = await response.json();
        if (response.status !== 200 || JSON.stringify(body) !== JSON.stringify([
            { id: "s1", name: "Maths", colorHex: "#00FF00" },
            { id: "s2", name: "History", colorHex: "#2E7D32" },
        ])) throw new Error("subjects were not translated to client colour format");
    });
});

Deno.test("tasks derive completion, carry the DST-resolved due instant with its zone, filter by subject, and omit orphaned tasks", async () => {
    await withFetch((input, init) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: "11111111-1111-1111-1111-111111111111" });
        if (url.endsWith("/rest/v1/rpc/list_study_tasks")) {
            const body = JSON.parse(String(init?.body));
            if (body.p_user_id !== "11111111-1111-1111-1111-111111111111" || body.p_subject_id !== "s1") {
                throw new Error("task list was not constrained to the verified owner and requested subject");
            }
            return jsonResponse(200, [
                {
                    id: "t1",
                    subject_id: "s1",
                    title: "Spring deadline",
                    completed_at: "2026-03-08T12:00:00Z",
                    due_at: "2026-03-08T13:00:00+00:00",
                    time_zone: "America/New_York",
                },
                { id: "t2", subject_id: "s1", title: "Incomplete", completed_at: null, due_at: null, time_zone: "UTC" },
                { id: "t3", subject_id: null, title: "Deleted subject", completed_at: null, due_at: null, time_zone: "UTC" },
            ]);
        }
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${url}`);
    }, async () => {
        const response = await handleRequest(new Request("https://example.test/v1/tasks?subjectId=s1", {
            headers: { authorization: ["Bearer", "valid-test-token"].join(" ") },
        }));
        const body = await response.json();
        if (response.status !== 200 || JSON.stringify(body) !== JSON.stringify([
            {
                id: "t1",
                subjectId: "s1",
                title: "Spring deadline",
                completed: true,
                dueAt: "2026-03-08T13:00:00.000Z",
                dueAtTimeZone: "America/New_York",
            },
            { id: "t2", subjectId: "s1", title: "Incomplete", completed: false, dueAt: null, dueAtTimeZone: null },
        ])) throw new Error("tasks were not translated correctly");
    });
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
            if (command.input.Prefix !== "11111111-1111-1111-1111-111111111111/") {
                throw new Error("multipart purge used an unverified owner");
            }
            return {
                Uploads: [{ Key: "11111111-1111-1111-1111-111111111111/pending", UploadId: "pending-upload" }],
                IsTruncated: false,
            };
        }
        if (command instanceof ListObjectsV2Command) {
            if (command.input.Prefix !== "11111111-1111-1111-1111-111111111111/") {
                throw new Error("object purge used an unverified owner");
            }
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
                    if (!url.endsWith("/11111111-1111-1111-1111-111111111111")) {
                        throw new Error("request body selected a different deletion target");
                    }
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
    if (resolveRoute("POST", "/v1/sync/sessions")?.operation !== "pushSessionChanges") {
        throw new Error("sync push was not routed to its own operation");
    }
});

const ALICE = "11111111-1111-1111-1111-111111111111";
const SESSION_ID = "a2222222-1111-1111-1111-111111111111";
const authorized = { authorization: ["Bearer", "valid-test-token"].join(" ") };

function stoppedSession(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        id: SESSION_ID,
        deviceId: "device-a",
        updatedAt: "2026-03-01T10:00:00.000Z",
        startedAt: "2026-03-01T09:00:00.000Z",
        endedAt: "2026-03-01T09:30:00.000Z",
        status: "STOPPED",
        note: "private note text",
        deleted: false,
        manualOverride: false,
        countedMillis: 1_800_000,
        unverifiedMillis: 0,
        events: [
            {
                id: "e1",
                sessionId: SESSION_ID,
                type: "STARTED",
                sequence: 0,
                wallClock: "2026-03-01T09:00:00.000Z",
                uptimeMillis: 1234,
                bootId: "boot-a",
            },
        ],
        ...overrides,
    };
}

function pushSessionsRequest(payload: Record<string, unknown> = { deviceId: "device-a", changes: [] }): Request {
    return new Request("https://example.test/v1/sync/sessions", {
        method: "POST",
        headers: authorized,
        body: JSON.stringify(payload),
    });
}

type Recorded = { rpc: Record<string, unknown>[]; telemetry: Record<string, unknown>[] };
function syncBackend(rpcName: string, rpcResult: (body: Record<string, unknown>) => unknown, recorded: Recorded) {
    return (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: ALICE });
        if (url.endsWith(`/rest/v1/rpc/${rpcName}`)) {
            const body = JSON.parse(String(init?.body));
            recorded.rpc.push(body);
            return jsonResponse(200, rpcResult(body));
        }
        if (url.includes("/rest/v1/backend_observability_events")) {
            recorded.telemetry.push(JSON.parse(String(init?.body)));
            return jsonResponse(201, {});
        }
        throw new Error(`unexpected fetch ${url}`);
    };
}

Deno.test("sync routes require JWT and share one path under two operations", () => {
    const pull = resolveRoute("GET", routedPath("/functions/v1/api/v1/sync/sessions"));
    const push = resolveRoute("POST", routedPath("/functions/v1/api/v1/sync/sessions"));
    if (pull?.operation !== "pullSessionChanges" || pull.auth !== "jwt") throw new Error("wrong pull route policy");
    if (push?.operation !== "pushSessionChanges" || push.auth !== "jwt") throw new Error("wrong push route policy");
    if (resolveRoute("DELETE", "/v1/sync/sessions")) throw new Error("wrong method routed");
});

Deno.test("sync endpoints reject unauthenticated calls before touching the database", async () => {
    await withFetch((input) => {
        const url = String(input);
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${url}`);
    }, async () => {
        for (const method of ["GET", "POST"]) {
            const response = await handleRequest(new Request("https://example.test/v1/sync/sessions", {
                method,
                ...(method === "POST" ? { body: JSON.stringify({ deviceId: "d", changes: [] }) } : {}),
            }));
            const body = await response.json();
            if (response.status !== 401 || body.code !== "authentication_required") {
                throw new Error(`${method} was not rejected as unauthenticated`);
            }
        }
    });
});

// A GoTrue rejection of the caller's token is the caller's problem, never a server outage: the
// client refreshes on 401 and treats 503 as "try again later", so a misreported status is the
// difference between a device that recovers and one that retries an expired token forever.
Deno.test("an expired access token is reported as 401 whether GoTrue answers 401 or 403", async () => {
    for (const [upstreamStatus, upstreamBody] of [
        [401, { error_code: "bad_jwt", msg: "invalid claims" }],
        [403, { error_code: "bad_jwt", msg: "token has invalid claims: token is expired" }],
        [404, { msg: "user not found" }],
    ] as const) {
        await withFetch((input) => {
            const url = String(input);
            if (url.endsWith("/auth/v1/user")) return jsonResponse(upstreamStatus, upstreamBody);
            if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
            throw new Error(`unexpected fetch ${url} for upstream ${upstreamStatus}`);
        }, async () => {
            const response = await handleRequest(pushSessionsRequest());
            const body = await response.json();
            if (response.status !== 401 || body.code !== "authentication_required") {
                throw new Error(`GoTrue ${upstreamStatus} became ${response.status} ${body.code}`);
            }
        });
    }
});

Deno.test("a GoTrue outage still reports an unavailable API", async () => {
    await withFetch((input) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(503, { msg: "gateway down" });
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        throw new Error(`unexpected fetch ${url}`);
    }, async () => {
        const response = await handleRequest(pushSessionsRequest());
        const body = await response.json();
        if (response.status !== 503 || body.code !== "api_unavailable") {
            throw new Error(`a GoTrue 503 became ${response.status} ${body.code}`);
        }
    });
});

Deno.test("a database refusal of the caller's request is not a retryable outage", async () => {
    for (const [upstreamStatus, expectedStatus, expectedCode] of [
        [400, 400, "invalid_request"],
        [403, 400, "invalid_request"],
        [409, 409, "conflict"],
        [500, 503, "api_unavailable"],
        [502, 503, "api_unavailable"],
    ] as const) {
        await withFetch((input) => {
            const url = String(input);
            if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: ALICE });
            if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
            if (url.includes("/rest/v1/rpc/sync_push_study_sessions")) {
                return jsonResponse(upstreamStatus, { message: "upstream said no" });
            }
            throw new Error(`unexpected fetch ${url}`);
        }, async () => {
            const response = await handleRequest(pushSessionsRequest({
                deviceId: "device-a",
                changes: [stoppedSession()],
            }));
            const body = await response.json();
            if (response.status !== expectedStatus || body.code !== expectedCode) {
                throw new Error(`database ${upstreamStatus} became ${response.status} ${body.code}`);
            }
        });
    }
});

Deno.test("an unexpected failure is logged with its message and operation, and nothing else", async () => {
    const lines: string[] = [];
    const original = console.error;
    console.error = (...args: unknown[]) => void lines.push(args.map(String).join(" "));
    try {
        await withFetch((input) => {
            const url = String(input);
            if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: ALICE });
            if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
            if (url.includes("/rest/v1/rpc/sync_push_study_sessions")) return jsonResponse(500, { message: "boom" });
            throw new Error(`unexpected fetch ${url}`);
        }, async () => {
            const response = await handleRequest(pushSessionsRequest({
                deviceId: "device-a",
                changes: [stoppedSession()],
            }));
            if (response.status !== 503) throw new Error("an upstream 500 was not reported as unavailable");
        });
    } finally {
        console.error = original;
    }
    const line = lines.find((entry) => entry.startsWith("api_unavailable"));
    if (!line) throw new Error("no api_unavailable line was logged");
    for (const expected of ["operation=pushSessionChanges", "name=UpstreamError", "message=Database request failed (500)"]) {
        if (!line.includes(expected)) throw new Error(`log line "${line}" is missing ${expected}`);
    }
    if (line.includes("valid-test-token") || line.includes("Bearer") || line.includes("    at ")) {
        throw new Error(`log line "${line}" carried a credential or a stack trace`);
    }
});

Deno.test("sync cursors round-trip opaquely and reject anything the server did not issue", () => {
    for (const seq of [0, 1, 50, 9_007_199_254_740_991]) {
        const cursor = encodeSyncCursor(seq);
        if (!/^[A-Za-z0-9_-]+$/.test(cursor)) throw new Error(`cursor ${cursor} is not URL-safe`);
        if (decodeSyncCursor(cursor) !== seq) throw new Error(`cursor for ${seq} did not round-trip`);
    }
    for (const forged of ["", "42", "not base64!", btoa("v2:1"), btoa("v1:-1"), btoa("v1:01"), btoa("v1:1;drop"), btoa("v1:99999999999999999")]) {
        let rejected = false;
        try {
            decodeSyncCursor(forged);
        } catch (error) {
            rejected = (error as { code?: string }).code === "invalid_cursor";
        }
        if (!rejected) throw new Error(`forged cursor ${forged} was accepted`);
    }
});

Deno.test("delta pull scopes to the JWT owner, pages with a look-ahead row, and never returns anchors", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    const leaky = stoppedSession();
    await withFetch(syncBackend("sync_pull_study_sessions", () => [
        { change_seq: 7, session: leaky },
        { change_seq: 9, session: { ...leaky, id: "b2222222-1111-1111-1111-111111111111", events: [] } },
        { change_seq: 12, session: leaky },
    ], recorded), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/sync/sessions?limit=2", {
            headers: authorized,
        }));
        const body = await response.json();
        if (response.status !== 200) throw new Error(`pull failed with ${response.status}`);
        if (response.headers.get("x-minimum-client-version") !== "1.2.3") throw new Error("minimum version header missing");
        const args = recorded.rpc[0];
        if (args.p_user_id !== ALICE || args.p_after !== 0 || args.p_limit !== 3) {
            throw new Error(`pull was not owner-scoped with a look-ahead row: ${JSON.stringify(args)}`);
        }
        if (body.changes.length !== 2 || body.hasMore !== true) throw new Error("look-ahead row leaked or hasMore wrong");
        if (decodeSyncCursor(body.nextCursor) !== 9) throw new Error("nextCursor does not point at the last returned change");
        const text = JSON.stringify(body);
        if (text.includes("uptimeMillis") || text.includes("bootId") || text.includes("boot-a")) {
            throw new Error("device-local anchors were returned");
        }
        const event = body.changes[0].events[0];
        if (JSON.stringify(Object.keys(event).sort()) !== JSON.stringify(["id", "sequence", "sessionId", "type", "wallClock"])) {
            throw new Error("returned event carried unexpected fields");
        }
        const telemetry = JSON.stringify(recorded.telemetry);
        if (!telemetry.includes('"operation":"pullSessionChanges"')) throw new Error("pull operation was not recorded");
        if (telemetry.includes(ALICE) || telemetry.includes("private note text") || telemetry.includes("valid-test-token")) {
            throw new Error("telemetry leaked identity, content or credentials");
        }
    });
});

Deno.test("delta pull resumes from a mid-stream cursor and echoes the cursor verbatim when nothing changed", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    const cursor = encodeSyncCursor(9);
    await withFetch(syncBackend("sync_pull_study_sessions", (args) =>
        args.p_after === 9 ? [{ change_seq: 12, session: stoppedSession({ events: [] }) }] : [], recorded), async () => {
        const resumed = await handleRequest(new Request(`https://example.test/v1/sync/sessions?cursor=${cursor}&limit=50`, {
            headers: authorized,
        }));
        const page = await resumed.json();
        if (recorded.rpc[0].p_after !== 9 || recorded.rpc[0].p_limit !== 51) throw new Error("pull did not resume after the cursor");
        if (page.changes.length !== 1 || page.hasMore !== false || decodeSyncCursor(page.nextCursor) !== 12) {
            throw new Error("resumed page was wrong");
        }

        const unchanged = await handleRequest(new Request(`https://example.test/v1/sync/sessions?cursor=${page.nextCursor}`, {
            headers: authorized,
        }));
        const empty = await unchanged.json();
        // SyncEngine treats "no changes and the cursor it sent" as unchanged and writes nothing.
        if (empty.changes.length !== 0 || empty.nextCursor !== page.nextCursor || empty.hasMore !== false) {
            throw new Error(`unchanged pull did not echo the cursor: ${JSON.stringify(empty)}`);
        }

        const first = await (await handleRequest(new Request("https://example.test/v1/sync/sessions?cursor=" + encodeSyncCursor(99), {
            headers: authorized,
        }))).json();
        if (first.nextCursor !== encodeSyncCursor(99)) throw new Error("empty page did not echo the cursor");
    });
    const fresh: Recorded = { rpc: [], telemetry: [] };
    await withFetch(syncBackend("sync_pull_study_sessions", () => [], fresh), async () => {
        const body = await (await handleRequest(new Request("https://example.test/v1/sync/sessions", { headers: authorized })))
            .json();
        if (body.nextCursor !== null || body.changes.length !== 0) throw new Error("empty first pull invented a cursor");
    });
});

Deno.test("delta pull rejects a forged cursor or bad limit without querying", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    await withFetch(syncBackend("sync_pull_study_sessions", () => [], recorded), async () => {
        for (const [query, code] of [["cursor=" + btoa("v1:1;x"), "invalid_cursor"], ["limit=0", "invalid_request"], ["limit=ten", "invalid_request"]]) {
            const response = await handleRequest(new Request(`https://example.test/v1/sync/sessions?${query}`, { headers: authorized }));
            const body = await response.json();
            if (response.status !== 400 || body.code !== code) throw new Error(`${query} gave ${response.status} ${body.code}`);
        }
        if (recorded.rpc.length !== 0) throw new Error("an invalid pull reached the database");
    });
});

Deno.test("push strips device-local anchors, scopes to the owner, and reports stale changes as rejections", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    const staleId = "b2222222-1111-1111-1111-111111111111";
    await withFetch(syncBackend("sync_push_study_sessions", () => ({ acceptedIds: [SESSION_ID], rejectedIds: [staleId] }), recorded), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/sync/sessions", {
            method: "POST",
            headers: authorized,
            body: JSON.stringify({
                deviceId: "device-a",
                changes: [
                    stoppedSession({ id: SESSION_ID.toUpperCase() }),
                    stoppedSession({ id: staleId, deleted: true, events: [] }),
                ],
            }),
        }));
        const body = await response.json();
        if (response.status !== 200) throw new Error(`a stale push was an error (${response.status})`);
        if (JSON.stringify(body) !== JSON.stringify({ acceptedIds: [SESSION_ID], rejectedIds: [staleId] })) {
            throw new Error(`per-entity outcome was not returned: ${JSON.stringify(body)}`);
        }
        const args = recorded.rpc[0];
        if (args.p_user_id !== ALICE) throw new Error("push was not scoped to the JWT owner");
        const changes = args.p_changes as Record<string, unknown>[];
        const sent = JSON.stringify(changes);
        if (sent.includes("uptimeMillis") || sent.includes("bootId") || sent.includes("boot-a")) {
            throw new Error("device-local anchors reached the database");
        }
        if (changes[0].id !== SESSION_ID) throw new Error("session id was not normalised");
        if (changes[1].deleted !== true) throw new Error("tombstone flag was lost");
        if (!JSON.stringify(recorded.telemetry).includes('"operation":"pushSessionChanges"')) {
            throw new Error("push operation was not recorded");
        }
        if (JSON.stringify(recorded.telemetry).includes("private note text")) throw new Error("telemetry leaked note text");
    });
});

Deno.test("push refuses an active session with 409 and malformed input with 400, writing nothing", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    await withFetch(syncBackend("sync_push_study_sessions", () => ({ acceptedIds: [], rejectedIds: [] }), recorded), async () => {
        const cases: [unknown, number, string][] = [
            [{ deviceId: "d", changes: [stoppedSession(), stoppedSession({ status: "RUNNING", endedAt: null })] }, 409, "active_session_not_syncable"],
            [{ deviceId: "d", changes: [stoppedSession({ status: "PAUSED" })] }, 409, "active_session_not_syncable"],
            [{ deviceId: "d", changes: [stoppedSession({ id: "not-a-uuid" })] }, 400, "invalid_request"],
            [{ deviceId: "d", changes: [stoppedSession({ endedAt: "2026-03-01T08:00:00Z" })] }, 400, "invalid_request"],
            [{ deviceId: "d", changes: [stoppedSession({ countedMillis: -1 })] }, 400, "invalid_request"],
            [{ deviceId: "d", changes: [stoppedSession({ updatedAt: "yesterday" })] }, 400, "invalid_request"],
            [{ deviceId: "d", changes: [stoppedSession({ events: [{ id: "e", sessionId: "other", type: "STARTED", sequence: 0, wallClock: "2026-03-01T09:00:00Z" }] })] }, 400, "invalid_request"],
            [{ deviceId: "d", changes: [stoppedSession({ events: [{ id: "e", sessionId: SESSION_ID, type: "TICK", sequence: 0, wallClock: "2026-03-01T09:00:00Z" }] })] }, 400, "invalid_request"],
            [{ changes: [] }, 400, "invalid_request"],
            [{ deviceId: "d", changes: "all" }, 400, "invalid_request"],
        ];
        for (const [payload, status, code] of cases) {
            const response = await handleRequest(new Request("https://example.test/v1/sync/sessions", {
                method: "POST",
                headers: authorized,
                body: JSON.stringify(payload),
            }));
            const body = await response.json();
            if (response.status !== status || body.code !== code) {
                throw new Error(`${JSON.stringify(payload).slice(0, 80)} gave ${response.status} ${body.code}`);
            }
            if (response.headers.get("x-minimum-client-version") !== "1.2.3") throw new Error("minimum version header missing");
        }
        if (recorded.rpc.length !== 0) throw new Error("a refused batch reached the database");
    });
});

function taskRecord(overrides: Record<string, unknown> = {}): Record<string, unknown> {
    return {
        entityType: "task",
        id: "c3333333-1111-1111-1111-111111111111",
        deviceId: "device-a",
        updatedAt: "2026-03-01T10:00:00.000Z",
        deleted: false,
        schemaVersion: 1,
        payload: { id: "c3333333-1111-1111-1111-111111111111", title: "private task title" },
        ...overrides,
    };
}

Deno.test("record routes require JWT and keep their cursor sequence apart from sessions", async () => {
    const pull = resolveRoute("GET", routedPath("/functions/v1/api/v1/sync/records"));
    const push = resolveRoute("POST", routedPath("/functions/v1/api/v1/sync/records"));
    if (pull?.operation !== "pullRecordChanges" || pull.auth !== "jwt") throw new Error("wrong record pull route policy");
    if (push?.operation !== "pushRecordChanges" || push.auth !== "jwt") throw new Error("wrong record push route policy");

    const recorded: Recorded = { rpc: [], telemetry: [] };
    await withFetch(syncBackend("sync_pull_records", () => [], recorded), async () => {
        // A session cursor is not a record cursor: accepting it would silently skip records.
        const response = await handleRequest(new Request(
            `https://example.test/v1/sync/records?cursor=${encodeSyncCursor(5)}`,
            { headers: authorized },
        ));
        if (response.status !== 400 || (await response.json()).code !== "invalid_cursor") {
            throw new Error("a session cursor was accepted on the record stream");
        }
        if (recorded.rpc.length !== 0) throw new Error("an invalid record pull reached the database");
    });
});

Deno.test("record pull scopes to the JWT owner, pages with a look-ahead row and returns records verbatim", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    await withFetch(syncBackend("sync_pull_records", () => [
        { change_seq: 4, record: taskRecord() },
        { change_seq: 6, record: taskRecord({ entityType: "material", payload: { remoteKey: "materials/abc" } }) },
    ], recorded), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/sync/records?limit=1", {
            headers: authorized,
        }));
        const body = await response.json();
        const args = recorded.rpc[0];
        if (args.p_user_id !== ALICE || args.p_after !== 0 || args.p_limit !== 2) {
            throw new Error(`record pull was not owner-scoped with a look-ahead row: ${JSON.stringify(args)}`);
        }
        if (JSON.stringify(body.changes) !== JSON.stringify([taskRecord()]) || body.hasMore !== true) {
            throw new Error("record page was not returned verbatim");
        }
        if (decodeSyncCursor(body.nextCursor, "r1:") !== 4) throw new Error("nextCursor does not point at the last record");
        const telemetry = JSON.stringify(recorded.telemetry);
        if (!telemetry.includes('"operation":"pullRecordChanges"')) throw new Error("record pull was not recorded");
        if (telemetry.includes("private task title") || telemetry.includes(ALICE)) throw new Error("telemetry leaked content");
    });
});

Deno.test("record push forwards validated envelopes to the owner-scoped RPC and refuses malformed batches", async () => {
    const recorded: Recorded = { rpc: [], telemetry: [] };
    const id = "c3333333-1111-1111-1111-111111111111";
    await withFetch(syncBackend("sync_push_records", () => ({ acceptedIds: [id], rejectedIds: [] }), recorded), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/sync/records", {
            method: "POST",
            headers: authorized,
            body: JSON.stringify({ deviceId: "device-a", changes: [taskRecord({ extra: "dropped" })] }),
        }));
        const body = await response.json();
        if (response.status !== 200 || JSON.stringify(body) !== JSON.stringify({ acceptedIds: [id], rejectedIds: [] })) {
            throw new Error(`record push failed: ${response.status} ${JSON.stringify(body)}`);
        }
        const args = recorded.rpc[0];
        if (args.p_user_id !== ALICE) throw new Error("record push was not scoped to the JWT owner");
        const sent = (args.p_changes as Record<string, unknown>[])[0];
        const expected = { ...taskRecord() };
        if (JSON.stringify(sent) !== JSON.stringify(expected)) throw new Error(`unexpected envelope ${JSON.stringify(sent)}`);
        if (!JSON.stringify(recorded.telemetry).includes('"operation":"pushRecordChanges"')) {
            throw new Error("record push was not recorded");
        }

        recorded.rpc.length = 0;
        const oversized = { blob: "x".repeat(64 * 1024) };
        const cases: unknown[] = [
            { deviceId: "d", changes: [taskRecord({ entityType: "subject" })] },
            { deviceId: "d", changes: [taskRecord({ id: "has spaces" })] },
            { deviceId: "d", changes: [taskRecord({ schemaVersion: 0 })] },
            { deviceId: "d", changes: [taskRecord({ payload: [] })] },
            { deviceId: "d", changes: [taskRecord({ payload: oversized })] },
            { deviceId: "d", changes: [taskRecord({ updatedAt: "yesterday" })] },
            { deviceId: "d", changes: [taskRecord({ entityType: "material", payload: { title: "not uploaded" } })] },
            { deviceId: "d", changes: Array.from({ length: 201 }, () => taskRecord()) },
            { changes: [] },
        ];
        for (const payload of cases) {
            const refused = await handleRequest(new Request("https://example.test/v1/sync/records", {
                method: "POST",
                headers: authorized,
                body: JSON.stringify(payload),
            }));
            const error = await refused.json();
            if (refused.status !== 400 || error.code !== "invalid_request") {
                throw new Error(`${JSON.stringify(payload).slice(0, 80)} gave ${refused.status} ${error.code}`);
            }
        }
        if (recorded.rpc.length !== 0) throw new Error("a refused record batch reached the database");
    });
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

Deno.test("passkey registration routes require a verified JWT and are distinct from sign-in", () => {
    const challenge = resolveRoute("POST", routedPath("/functions/v1/api/v1/auth/passkey/registration/challenge"));
    const register = resolveRoute("POST", routedPath("/functions/v1/api/v1/auth/passkey/registration"));
    if (challenge?.operation !== "beginPasskeyRegistration" || challenge.auth !== "jwt") {
        throw new Error("registration challenge route is not authenticated");
    }
    if (register?.operation !== "completePasskeyRegistration" || register.auth !== "jwt") {
        throw new Error("registration route is not authenticated");
    }
    if (resolveRoute("GET", "/v1/auth/passkey/registration")) throw new Error("wrong method routed");
});

Deno.test("passkey registration rejects an unauthenticated caller without touching the credential store", async () => {
    const state = passkeyState();
    await withFetch(passkeyFetch({ ...state, authenticated: false }), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/auth/passkey/registration", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ registrationResponseJson: "{}" }),
        }));
        if (response.status !== 401) throw new Error("registration accepted an anonymous caller");
    });
    if (state.inserted.length !== 0) throw new Error("a credential was stored without a verified caller");
});

Deno.test("passkey registration binds the stored credential to the JWT owner and echoes its id", async () => {
    const passkey = await newPasskey();
    const state = passkeyState();
    state.challenges.push({ challenge_hash: "hash", user_id: OWNER });
    await withFetch(passkeyFetch(state), async () => {
        const options = await handleRequest(new Request("https://example.test/v1/auth/passkey/registration/challenge", {
            method: "POST",
            headers: { authorization: "Bearer " + "valid-test-token" },
        }));
        const issued = await options.json();
        const creationOptions = JSON.parse(issued.requestJson);
        if (options.status !== 200 || creationOptions.rp.id !== RP_ID) throw new Error("wrong relying party issued");
        if (creationOptions.user.id !== base64Url(new TextEncoder().encode(OWNER))) {
            throw new Error("the creation options did not identify the signed-in account");
        }
        if (Number.isNaN(Date.parse(issued.expiresAt))) throw new Error("the challenge has no expiry");

        const response = await handleRequest(new Request("https://example.test/v1/auth/passkey/registration", {
            method: "POST",
            headers: { authorization: "Bearer " + "valid-test-token", "content-type": "application/json" },
            body: JSON.stringify({
                registrationResponseJson: await registrationResponseJson(passkey, creationOptions.challenge),
            }),
        }));
        const body = await response.json();
        if (response.status !== 201 || body.credentialId !== passkey.credentialId) {
            throw new Error(`registration was not accepted: ${JSON.stringify(body)}`);
        }
    });
    const stored = state.inserted[0] as Record<string, unknown>;
    if (state.inserted.length !== 1 || stored.user_id !== OWNER || stored.credential_id !== passkey.credentialId) {
        throw new Error("the credential was not bound to the verified caller");
    }
    if (state.consumed[0]?.p_purpose !== "registration") throw new Error("a sign-in challenge was consumed");
    if (state.issued[0]?.purpose !== "registration" || state.issued[0]?.user_id !== OWNER) {
        throw new Error("the challenge was not stored as a registration challenge for the signed-in account");
    }
});

Deno.test("a registration challenge issued to another account cannot be redeemed", async () => {
    const passkey = await newPasskey();
    const state = passkeyState();
    state.challenges.push({ challenge_hash: "hash", user_id: OTHER_OWNER });
    await withFetch(passkeyFetch(state), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/auth/passkey/registration", {
            method: "POST",
            headers: { authorization: "Bearer " + "valid-test-token", "content-type": "application/json" },
            body: JSON.stringify({ registrationResponseJson: await registrationResponseJson(passkey, CHALLENGE) }),
        }));
        if (response.status !== 401) throw new Error("another account's challenge was accepted");
    });
    if (state.inserted.length !== 0) throw new Error("a credential was attached using another account's challenge");
});

Deno.test("a replayed registration challenge is refused once the database reports it consumed", async () => {
    const passkey = await newPasskey();
    const state = passkeyState();
    await withFetch(passkeyFetch(state), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/auth/passkey/registration", {
            method: "POST",
            headers: { authorization: "Bearer " + "valid-test-token", "content-type": "application/json" },
            body: JSON.stringify({ registrationResponseJson: await registrationResponseJson(passkey, CHALLENGE) }),
        }));
        if (response.status !== 401) throw new Error("a spent registration challenge was accepted");
    });
    if (state.inserted.length !== 0) throw new Error("a credential was stored from a replayed challenge");
});

Deno.test("passkey sign-in verifies the assertion, consumes the challenge once and mints the owner's session", async () => {
    const passkey = await newPasskey();
    const state = passkeyState();
    state.credentials[passkey.credentialId] = credentialRow(passkey, OWNER, 3);
    state.challenges.push({ challenge_hash: "hash", user_id: null });
    await withFetch(passkeyFetch(state), async () => {
        const response = await handleRequest(new Request("https://example.test/v1/auth/signin", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                type: "passkey",
                assertion: await assertionJson(passkey, { counter: 4, userHandle: OWNER }),
            }),
        }));
        const body = await response.json();
        if (response.status !== 200 || body.accessToken !== "passkey-access" || body.refreshToken !== "passkey-refresh") {
            throw new Error(`passkey sign-in did not return a session: ${JSON.stringify(body)}`);
        }
        if (body.expiresInSeconds !== 3600) throw new Error("token fields were not translated");
    });
    if (state.consumed.length !== 1 || state.consumed[0].p_purpose !== "signin") {
        throw new Error("the challenge was not claimed exactly once as a sign-in challenge");
    }
    if ((state.patched[0] as Record<string, unknown>)?.sign_count !== 4) {
        throw new Error("the signature counter was not advanced");
    }
    if (state.calls.join(",") !== `adminUser:${OWNER},generateLink,verify`) {
        throw new Error(`the session was minted for the wrong account: ${state.calls.join(",")}`);
    }
});

Deno.test("a replayed passkey assertion is refused and mints no session", async () => {
    const passkey = await newPasskey();
    const state = passkeyState();
    state.credentials[passkey.credentialId] = credentialRow(passkey, OWNER, 0);
    // The database claims the challenge atomically, so the second attempt matches no row at all.
    state.challenges.push({ challenge_hash: "hash", user_id: null });
    const assertion = await assertionJson(passkey, { counter: 1 });
    await withFetch(passkeyFetch(state), async () => {
        const first = await handleRequest(passkeySignInRequest(assertion));
        if (first.status !== 200) throw new Error("the first use of the challenge was rejected");
        const second = await handleRequest(passkeySignInRequest(assertion));
        if (second.status !== 401) throw new Error("a replayed assertion was accepted");
    });
    if (state.calls.filter((call) => call === "verify").length !== 1) {
        throw new Error("the replay minted a second session");
    }
});

Deno.test("passkey sign-in rejects an assertion from a foreign origin", async () => {
    await expectPasskeySignInRejected({ origin: "https://phishing.example" }, "a foreign origin was accepted");
});

Deno.test("passkey sign-in rejects an assertion made against a different relying party", async () => {
    await expectPasskeySignInRejected({ rpId: "evil.test" }, "a foreign relying party was accepted");
});

Deno.test("passkey sign-in rejects an assertion without the user-presence flag", async () => {
    await expectPasskeySignInRejected({ flags: 0x04 }, "an assertion without user presence was accepted");
});

Deno.test("passkey sign-in rejects a signature counter that goes backwards", async () => {
    await expectPasskeySignInRejected({ counter: 2, storedCounter: 5 }, "a rewound signature counter was accepted");
});

Deno.test("passkey sign-in rejects an assertion signed by a different key", async () => {
    const other = await newPasskey();
    await expectPasskeySignInRejected({ signWith: other }, "an assertion signed by another key was accepted");
});

Deno.test("passkey sign-in rejects a credential id that is not registered", async () => {
    await expectPasskeySignInRejected({ storeCredential: false }, "an unregistered credential was accepted");
});

Deno.test("passkey sign-in never trusts a client-supplied user handle", async () => {
    const passkey = await newPasskey();
    const state = passkeyState();
    state.credentials[passkey.credentialId] = credentialRow(passkey, OWNER, 0);
    state.challenges.push({ challenge_hash: "hash", user_id: null });
    await withFetch(passkeyFetch(state), async () => {
        const assertion = await assertionJson(passkey, { counter: 1, userHandle: OTHER_OWNER });
        const response = await handleRequest(passkeySignInRequest(assertion));
        if (response.status !== 401) throw new Error("a user handle naming another account was accepted");
    });
    if (state.calls.length !== 0) throw new Error("a session was minted for a client-named account");
});

Deno.test("passkey sign-in rejects an assertion without a user handle", async () => {
    await expectPasskeySignInRejected(
        { omitUserHandle: true },
        "an assertion without a user handle was accepted",
    );
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

// ---------------------------------------------------------------------------------------------
// Passkey fixtures.
//
// The assertions and attestations below are produced with WebCrypto rather than recorded from a
// handset, because the point of every negative test is to change exactly one signed field — the
// origin, the RP ID, a flag bit, the counter, the signing key — and observe that verification
// still fails. A captured fixture cannot be edited that way without invalidating its signature,
// which would make every test pass for the wrong reason.
// ---------------------------------------------------------------------------------------------

const OWNER = "11111111-1111-1111-1111-111111111111";
const OTHER_OWNER = "22222222-2222-2222-2222-222222222222";
const RP_ID = "studyflow.test";
const ORIGIN = "android:apk-key-hash:test-signing-certificate";
const CHALLENGE = "dGVzdC1jaGFsbGVuZ2UtMzItYnl0ZXMtbG9uZy12YWx1ZQ";

type Passkey = { keyPair: CryptoKeyPair; cose: Uint8Array; credentialId: string };
type ChallengeRow = { challenge_hash: string; user_id: string | null };
type PasskeyState = {
    authenticated: boolean;
    credentials: Record<string, Record<string, unknown>>;
    challenges: ChallengeRow[];
    issued: Record<string, unknown>[];
    consumed: { p_purpose: string; p_challenge_hash: string }[];
    inserted: unknown[];
    patched: unknown[];
    calls: string[];
};

function passkeyState(): PasskeyState {
    return {
        authenticated: true,
        credentials: {},
        challenges: [],
        issued: [],
        consumed: [],
        inserted: [],
        patched: [],
        calls: [],
    };
}

function credentialRow(passkey: Passkey, owner: string, signCount: number): Record<string, unknown> {
    return {
        credential_id: passkey.credentialId,
        user_id: owner,
        public_key: base64Url(passkey.cose),
        sign_count: signCount,
        transports: ["internal"],
    };
}

function passkeySignInRequest(assertion: string): Request {
    return new Request("https://example.test/v1/auth/signin", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ type: "passkey", assertion }),
    });
}

function passkeyFetch(state: PasskeyState): (input: RequestInfo | URL, init?: RequestInit) => Response {
    return (input, init) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url.includes("/rest/v1/backend_observability_events")) return jsonResponse(201, {});
        if (url.endsWith("/auth/v1/user")) {
            return state.authenticated ? jsonResponse(200, { id: OWNER }) : jsonResponse(401, {});
        }
        if (url.includes("/rest/v1/auth_signin_challenges")) {
            state.issued.push(JSON.parse(String(init?.body)));
            return jsonResponse(201, {});
        }
        if (url.endsWith("/rest/v1/rpc/consume_auth_challenge")) {            state.consumed.push(JSON.parse(String(init?.body)));
            const row = state.challenges.shift();
            return jsonResponse(200, row ? [row] : []);
        }
        if (url.includes("/rest/v1/passkey_credentials")) {
            if (method === "POST") {
                state.inserted.push(JSON.parse(String(init?.body)));
                return jsonResponse(201, {});
            }
            if (method === "PATCH") {
                state.patched.push(JSON.parse(String(init?.body)));
                return jsonResponse(200, {});
            }
            const credentialId = url.match(/credential_id=eq\.([^&]+)/)?.[1];
            if (credentialId) {
                const row = state.credentials[decodeURIComponent(credentialId)];
                return jsonResponse(200, row ? [row] : []);
            }
            return jsonResponse(200, Object.values(state.credentials));
        }
        if (url.endsWith("/auth/v1/admin/generate_link")) {
            state.calls.push("generateLink");
            return jsonResponse(200, { hashed_token: "hashed-magic-token" });
        }
        if (url.includes("/auth/v1/admin/users/")) {
            state.calls.push("adminUser:" + url.slice(url.lastIndexOf("/") + 1));
            return jsonResponse(200, { id: OWNER, email: "alice@example.com" });
        }
        if (url.endsWith("/auth/v1/verify")) {
            state.calls.push("verify");
            return jsonResponse(200, {
                access_token: "passkey-access",
                refresh_token: "passkey-refresh",
                expires_in: 3600,
            });
        }
        throw new Error(`unexpected fetch ${url}`);
    };
}

async function expectPasskeySignInRejected(
    options: {
        origin?: string;
        rpId?: string;
        flags?: number;
        counter?: number;
        storedCounter?: number;
        storeCredential?: boolean;
        omitUserHandle?: boolean;
        signWith?: Passkey;
    },
    message: string,
): Promise<void> {
    const passkey = await newPasskey();
    const state = passkeyState();
    if (options.storeCredential !== false) {
        state.credentials[passkey.credentialId] = credentialRow(passkey, OWNER, options.storedCounter ?? 0);
    }
    state.challenges.push({ challenge_hash: "hash", user_id: null });
    await withFetch(passkeyFetch(state), async () => {
        const assertion = await assertionJson(options.signWith ?? passkey, {
            counter: options.counter ?? 1,
            origin: options.origin,
            rpId: options.rpId,
            flags: options.flags,
            credentialId: passkey.credentialId,
            userHandle: options.omitUserHandle ? undefined : options.userHandle ?? OWNER,
        });
        const response = await handleRequest(passkeySignInRequest(assertion));
        if (response.status !== 401) throw new Error(message);
    });
    if (state.calls.length !== 0) throw new Error(`${message}: a session was minted anyway`);
}

async function newPasskey(): Promise<Passkey> {
    const keyPair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
    const raw = new Uint8Array(await crypto.subtle.exportKey("raw", keyPair.publicKey));
    return {
        keyPair,
        cose: coseKey(raw.slice(1, 33), raw.slice(33, 65)),
        credentialId: base64Url(crypto.getRandomValues(new Uint8Array(16))),
    };
}

async function assertionJson(
    passkey: Passkey,
    options: {
        counter: number;
        origin?: string;
        rpId?: string;
        flags?: number;
        credentialId?: string;
        userHandle?: string;
    },
): Promise<string> {
    const credentialId = options.credentialId ?? passkey.credentialId;
    const clientDataJSON = new TextEncoder().encode(JSON.stringify({
        type: "webauthn.get",
        challenge: CHALLENGE,
        origin: options.origin ?? ORIGIN,
        androidPackageName: "dev.studyflow",
    }));
    const authenticatorData = await authData(options.rpId ?? RP_ID, options.flags ?? 0x05, options.counter);
    const signed = concat(authenticatorData, new Uint8Array(await crypto.subtle.digest("SHA-256", clientDataJSON)));
    const raw = new Uint8Array(await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        passkey.keyPair.privateKey,
        signed,
    ));
    return JSON.stringify({
        id: credentialId,
        rawId: credentialId,
        type: "public-key",
        clientExtensionResults: {},
        response: {
            clientDataJSON: base64Url(clientDataJSON),
            authenticatorData: base64Url(authenticatorData),
            signature: base64Url(derSignature(raw)),
            ...(options.userHandle ? { userHandle: base64Url(new TextEncoder().encode(options.userHandle)) } : {}),
        },
    });
}

async function registrationResponseJson(passkey: Passkey, challenge: string): Promise<string> {
    const clientDataJSON = new TextEncoder().encode(JSON.stringify({
        type: "webauthn.create",
        challenge,
        origin: ORIGIN,
        androidPackageName: "dev.studyflow",
    }));
    const credentialId = decodeBase64Url(passkey.credentialId);
    // The attested-credential-data flag (0x40) is what tells a verifier the credential id and
    // public key follow the counter; without it the authenticator data is an assertion, not a
    // registration.
    const attested = concat(
        await authData(RP_ID, 0x45, 0),
        new Uint8Array(16),
        Uint8Array.from([credentialId.length >> 8, credentialId.length & 0xFF]),
        credentialId,
        passkey.cose,
    );
    const attestationObject = cborMap([
        ["fmt", cborText("none")],
        ["attStmt", cborMap([])],
        ["authData", cborBytes(attested)],
    ]);
    return JSON.stringify({
        id: passkey.credentialId,
        rawId: passkey.credentialId,
        type: "public-key",
        clientExtensionResults: {},
        response: {
            clientDataJSON: base64Url(clientDataJSON),
            attestationObject: base64Url(attestationObject),
            transports: ["internal", "hybrid"],
        },
    });
}

async function authData(rpId: string, flags: number, counter: number): Promise<Uint8Array> {
    const rpIdHash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(rpId)));
    return concat(
        rpIdHash,
        Uint8Array.from([flags]),
        Uint8Array.from([counter >>> 24 & 0xFF, counter >>> 16 & 0xFF, counter >>> 8 & 0xFF, counter & 0xFF]),
    );
}

function coseKey(x: Uint8Array, y: Uint8Array): Uint8Array {
    return cborMap([
        [1, cborInt(2)],
        [3, cborInt(-7)],
        [-1, cborInt(1)],
        [-2, cborBytes(x)],
        [-3, cborBytes(y)],
    ]);
}

// A WebCrypto ECDSA signature is the raw r||s pair; WebAuthn carries the ASN.1 DER encoding, so a
// fixture that skipped this step would be rejected for its shape rather than for what it proves.
function derSignature(raw: Uint8Array): Uint8Array {
    const parts = [raw.slice(0, 32), raw.slice(32)].map((value) => {
        let start = 0;
        while (start < value.length - 1 && value[start] === 0) start++;
        const trimmed = Array.from(value.slice(start));
        if (trimmed[0] & 0x80) trimmed.unshift(0);
        return [0x02, trimmed.length, ...trimmed];
    });
    const body = [...parts[0], ...parts[1]];
    return Uint8Array.from([0x30, body.length, ...body]);
}

function cborHead(major: number, value: number): number[] {
    if (value < 24) return [major << 5 | value];
    if (value < 0x100) return [major << 5 | 24, value];
    if (value < 0x10000) return [major << 5 | 25, value >> 8, value & 0xFF];
    return [major << 5 | 26, value >>> 24 & 0xFF, value >>> 16 & 0xFF, value >>> 8 & 0xFF, value & 0xFF];
}

function cborInt(value: number): Uint8Array {
    return Uint8Array.from(value >= 0 ? cborHead(0, value) : cborHead(1, -1 - value));
}

function cborBytes(value: Uint8Array): Uint8Array {
    return concat(Uint8Array.from(cborHead(2, value.length)), value);
}

function cborText(value: string): Uint8Array {
    const bytes = new TextEncoder().encode(value);
    return concat(Uint8Array.from(cborHead(3, bytes.length)), bytes);
}

function cborMap(entries: [number | string, Uint8Array][]): Uint8Array {
    return entries.reduce(
        (encoded, [key, value]) =>
            concat(encoded, typeof key === "number" ? cborInt(key) : cborText(key), value),
        Uint8Array.from(cborHead(5, entries.length)),
    );
}

function concat(...parts: Uint8Array[]): Uint8Array {
    const joined = new Uint8Array(parts.reduce((total, part) => total + part.length, 0));
    parts.reduce((offset, part) => {
        joined.set(part, offset);
        return offset + part.length;
    }, 0);
    return joined;
}

function base64Url(bytes: Uint8Array): string {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function decodeBase64Url(value: string): Uint8Array {
    const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
    const binary = atob(base64 + "=".repeat((4 - base64.length % 4) % 4));
    return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
