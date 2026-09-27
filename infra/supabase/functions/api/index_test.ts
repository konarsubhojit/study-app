const settings = {
    SUPABASE_URL: "http://127.0.0.1:54321",
    SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
    GOOGLE_SERVER_CLIENT_ID: "google-client-id.test",
    API_MINIMUM_CLIENT_VERSION: "1.2.3",
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
