import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  bootstrapDemo,
  bootstrapPayload,
  decideSourceLink,
  generatePatternDraft,
  getEvidenceSummary,
  getPatternCardDetail,
  getProgress,
  getRecommendations,
  importCodexObsidian,
  ingestManual,
  listLibrary,
  listReviewTasks,
  registerProvider,
  reviewPatternCard,
  submitProblem,
  suggestSourceLinks
} from "./platform.js";
import { assertRole } from "./authz.js";
import { redactForApi } from "./security.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, "..", "public");
const MAX_JSON_BYTES = 1_000_000;
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX_REQUESTS = 180;

function userIdFrom(req) {
  return req.headers["x-user-id"] || "u-admin";
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(redactForApi(payload), null, 2);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
  });
  res.end(`${body}\n`);
}

function sendError(res, error) {
  const status = error.status || 500;
  sendJson(res, status, {
    error: {
      message: status === 500 ? "Internal server error" : error.message,
      status
    }
  });
}

async function parseJson(req) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_JSON_BYTES) throw Object.assign(new Error("JSON body too large"), { status: 413 });
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("Invalid JSON body"), { status: 400 });
  }
}

async function sendStatic(req, res, pathname) {
  const target = pathname === "/" ? "/index.html" : pathname;
  const safeTarget = path.normalize(target).replace(/^(\.\.[/\\])+/, "");
  const filePath = path.join(PUBLIC_DIR, safeTarget);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    res.end("Forbidden");
    return;
  }
  try {
    const info = await stat(filePath);
    if (!info.isFile()) throw new Error("not file");
    const contentType = filePath.endsWith(".css")
      ? "text/css; charset=utf-8"
      : filePath.endsWith(".js")
        ? "text/javascript; charset=utf-8"
        : "text/html; charset=utf-8";
    res.writeHead(200, {
      "content-type": contentType,
      "x-content-type-options": "nosniff",
      "content-security-policy": "default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    });
    createReadStream(filePath).pipe(res);
  } catch {
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("Not found");
  }
}

function organizationIdFrom(url) {
  return url.searchParams.get("organizationId") || "org-demo";
}

export async function createApp(store) {
  await bootstrapDemo(store);
  const requestBuckets = new Map();

  return http.createServer(async (req, res) => {
    const url = new URL(req.url ?? "/", "http://localhost");
    const actorUserId = userIdFrom(req);

    try {
      if (!url.pathname.startsWith("/api/")) {
        await sendStatic(req, res, url.pathname);
        return;
      }

      const bucketKey = `${actorUserId}:${url.pathname}`;
      const currentTime = Date.now();
      const bucket = requestBuckets.get(bucketKey) ?? { count: 0, resetAt: currentTime + RATE_LIMIT_WINDOW_MS };
      if (currentTime > bucket.resetAt) {
        bucket.count = 0;
        bucket.resetAt = currentTime + RATE_LIMIT_WINDOW_MS;
      }
      bucket.count += 1;
      requestBuckets.set(bucketKey, bucket);
      if (bucket.count > RATE_LIMIT_MAX_REQUESTS) {
        sendJson(res, 429, { error: { message: "Rate limit exceeded", status: 429 } });
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/health") {
        sendJson(res, 200, { ok: true, time: new Date().toISOString() });
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/bootstrap") {
        sendJson(res, 200, bootstrapPayload(store));
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/providers") {
        const organizationId = organizationIdFrom(url);
        const providers = store.db.aiProviders.filter((provider) => {
          return provider.organizationId === organizationId && (provider.scope === "organization" || provider.ownerUserId === actorUserId);
        });
        sendJson(res, 200, { providers });
        return;
      }

      if (req.method === "POST" && url.pathname === "/api/providers") {
        const payload = await parseJson(req);
        sendJson(res, 201, { provider: await registerProvider(store, actorUserId, payload) });
        return;
      }

      if (req.method === "POST" && url.pathname === "/api/ingest/manual") {
        sendJson(res, 201, await ingestManual(store, actorUserId, await parseJson(req)));
        return;
      }

      if (req.method === "POST" && url.pathname === "/api/ingest/codex-obsidian") {
        sendJson(res, 201, await importCodexObsidian(store, actorUserId, await parseJson(req)));
        return;
      }

      const evidenceMatch = url.pathname.match(/^\/api\/evidence\/([^/]+)$/);
      if (req.method === "GET" && evidenceMatch) {
        sendJson(res, 200, await getEvidenceSummary(store, actorUserId, evidenceMatch[1]));
        return;
      }

      if (req.method === "POST" && url.pathname === "/api/source-links/suggest") {
        sendJson(res, 201, await suggestSourceLinks(store, actorUserId, await parseJson(req)));
        return;
      }

      const sourceLinkDecision = url.pathname.match(/^\/api\/source-links\/([^/]+)\/(confirm|reject)$/);
      if (req.method === "POST" && sourceLinkDecision) {
        const status = sourceLinkDecision[2] === "confirm" ? "confirmed" : "rejected";
        sendJson(res, 200, { link: await decideSourceLink(store, actorUserId, sourceLinkDecision[1], status) });
        return;
      }

      if (req.method === "POST" && url.pathname === "/api/generation/run") {
        sendJson(res, 201, await generatePatternDraft(store, actorUserId, await parseJson(req)));
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/review/tasks") {
        sendJson(res, 200, { reviewTasks: await listReviewTasks(store, actorUserId, organizationIdFrom(url)) });
        return;
      }

      const reviewDecision = url.pathname.match(/^\/api\/review\/tasks\/([^/]+)\/decision$/);
      if (req.method === "POST" && reviewDecision) {
        sendJson(res, 200, await reviewPatternCard(store, actorUserId, reviewDecision[1], await parseJson(req)));
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/library") {
        sendJson(res, 200, {
          cards: await listLibrary(store, actorUserId, organizationIdFrom(url), {
            status: url.searchParams.get("status") || "published",
            tag: url.searchParams.get("tag") || "",
            difficulty: url.searchParams.get("difficulty") || ""
          })
        });
        return;
      }

      const cardMatch = url.pathname.match(/^\/api\/pattern-cards\/([^/]+)$/);
      if (req.method === "GET" && cardMatch) {
        sendJson(res, 200, { patternCard: await getPatternCardDetail(store, actorUserId, cardMatch[1]) });
        return;
      }

      const submissionMatch = url.pathname.match(/^\/api\/problems\/([^/]+)\/submissions$/);
      if (req.method === "POST" && submissionMatch) {
        sendJson(res, 201, await submitProblem(store, actorUserId, submissionMatch[1], await parseJson(req)));
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/progress") {
        sendJson(res, 200, { proficiency: await getProgress(store, actorUserId, organizationIdFrom(url)) });
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/recommendations") {
        sendJson(res, 200, { cards: await getRecommendations(store, actorUserId, organizationIdFrom(url)) });
        return;
      }

      if (req.method === "GET" && url.pathname === "/api/audit") {
        const organizationId = organizationIdFrom(url);
        assertRole(store.db, actorUserId, organizationId, "admin");
        sendJson(res, 200, {
          auditLogs: store.db.auditLogs.filter((log) => log.organizationId === organizationId).slice(-100)
        });
        return;
      }

      sendJson(res, 404, { error: { message: "Route not found", status: 404 } });
    } catch (error) {
      sendError(res, error);
    }
  });
}
