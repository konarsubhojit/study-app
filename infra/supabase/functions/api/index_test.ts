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

const { decodeSyncCursor, encodeSyncCursor, handleRequest, observabilityLogLine, resolveRoute, routedPath } = await import(
    "./index.ts"
);

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

Deno.test("tasks derive completion, preserve DST-resolved due instants, filter by subject, and omit orphaned tasks", async () => {
    await withFetch((input, init) => {
        const url = String(input);
        if (url.endsWith("/auth/v1/user")) return jsonResponse(200, { id: "11111111-1111-1111-1111-111111111111" });
        if (url.endsWith("/rest/v1/rpc/list_study_tasks")) {
            const body = JSON.parse(String(init?.body));
            if (body.p_user_id !== "11111111-1111-1111-1111-111111111111" || body.p_subject_id !== "s1") {
                throw new Error("task list was not constrained to the verified owner and requested subject");
            }
            return jsonResponse(200, [
                { id: "t1", subject_id: "s1", title: "Spring deadline", completed_at: "2026-03-08T12:00:00Z", due_at: "2026-03-08T13:00:00+00:00" },
                { id: "t2", subject_id: "s1", title: "Incomplete", completed_at: null, due_at: null },
                { id: "t3", subject_id: null, title: "Deleted subject", completed_at: null, due_at: null },
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
            { id: "t1", subjectId: "s1", title: "Spring deadline", completed: true, dueAt: "2026-03-08T13:00:00.000Z" },
            { id: "t2", subjectId: "s1", title: "Incomplete", completed: false, dueAt: null },
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
