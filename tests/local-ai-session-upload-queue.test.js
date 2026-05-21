import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import {
  LocalAiSessionUploadQueue,
  uploadLocalAiSession
} from "../scripts/local-ai-session-upload-queue.mjs";

test("session upload queue keeps backend-down sessions and schedules retry", async () => {
  await withTempDir(async (dir) => {
    const storeFile = path.join(dir, "queue.json");
    let now = new Date("2026-05-20T01:00:00Z");
    const queue = await LocalAiSessionUploadQueue.open({
      storeFile,
      now: () => now,
      retryBaseMs: 1000,
      uploader: async () => {
        throw new Error("backend down at /Users/example/repo sk-secretsecret");
      }
    });

    await queue.enqueue(payload("repo-1", "session-1"));
    const result = await queue.uploadDue();

    assert.deepEqual(result.uploaded, []);
    assert.equal(result.failed.length, 1);
    const [item] = queue.listMetadata();
    assert.equal(item.status, "failed");
    assert.equal(item.attempts, 1);
    assert.equal(item.nextAttemptAt, "2026-05-20T01:00:01.000Z");
    assert.equal(item.lastError.includes("/Users/"), false);
    assert.equal(item.lastError.includes("sk-secret"), false);
    assert.equal(JSON.stringify(queue.listMetadata()).includes("export const generated"), false);

    now = new Date("2026-05-20T01:00:00.500Z");
    const early = await queue.uploadDue({ uploader: async () => assert.fail("not due yet") });
    assert.deepEqual(early, { uploaded: [], failed: [], discarded: [] });
  });
});

test("session upload queue retries persisted items after restart", async () => {
  await withTempDir(async (dir) => {
    const storeFile = path.join(dir, "queue.json");
    let now = new Date("2026-05-20T01:00:00Z");
    const first = await LocalAiSessionUploadQueue.open({
      storeFile,
      now: () => now,
      retryBaseMs: 1000,
      uploader: async () => {
        throw new Error("backend down");
      }
    });
    await first.enqueue(payload("repo-1", "session-1"));
    await first.uploadDue();

    now = new Date("2026-05-20T01:00:01Z");
    const uploaded = [];
    const second = await LocalAiSessionUploadQueue.open({
      storeFile,
      now: () => now,
      retryBaseMs: 1000,
      uploader: async (session) => {
        uploaded.push(session.idempotencyKey);
      }
    });
    const result = await second.uploadDue();

    assert.deepEqual(uploaded, ["session-1"]);
    assert.equal(result.uploaded.length, 1);
    assert.deepEqual(second.listMetadata(), []);
  });
});

test("session upload queue cancels queued uploads when repository approval is revoked", async () => {
  await withTempDir(async (dir) => {
    const queue = await LocalAiSessionUploadQueue.open({ storeFile: path.join(dir, "queue.json") });
    await queue.enqueue(payload("repo-1", "session-1"));
    await queue.enqueue(payload("repo-2", "session-2"));

    const removed = await queue.cancelRepository("repo-1");

    assert.equal(removed, 1);
    assert.deepEqual(queue.listMetadata().map((item) => item.repoIdentityHash), ["repo-2"]);
  });
});

test("session upload queue enforces max size by discarding oldest items", async () => {
  await withTempDir(async (dir) => {
    let tick = 0;
    const queue = await LocalAiSessionUploadQueue.open({
      storeFile: path.join(dir, "queue.json"),
      maxItems: 2,
      now: () => new Date(Date.parse("2026-05-20T01:00:00Z") + tick++ * 1000)
    });

    await queue.enqueue(payload("repo-1", "session-1"));
    await queue.enqueue(payload("repo-1", "session-2"));
    await queue.enqueue(payload("repo-1", "session-3"));

    assert.equal(queue.listMetadata().length, 2);
    assert.deepEqual(
      queue.listMetadata().map((item) => item.title),
      ["Session session-3", "Session session-2"]
    );
    assert.equal(queue.stats().discardedCount, 1);
  });
});

test("session upload queue discards items after the retry limit", async () => {
  await withTempDir(async (dir) => {
    const queue = await LocalAiSessionUploadQueue.open({
      storeFile: path.join(dir, "queue.json"),
      maxAttempts: 1,
      uploader: async () => {
        throw new Error("backend still down");
      }
    });
    await queue.enqueue(payload("repo-1", "session-1"));

    const result = await queue.uploadDue();

    assert.deepEqual(result.failed, []);
    assert.equal(result.discarded.length, 1);
    assert.deepEqual(queue.listMetadata(), []);
    assert.equal(queue.stats().discardedCount, 1);
  });
});

test("local AI session uploader posts to the backend ingest endpoint", async () => {
  const calls = [];
  const response = await uploadLocalAiSession(payload("repo-1", "session-1"), {
    backendUrl: "http://127.0.0.1:8080/",
    authToken: "token-1",
    fetchFn: async (url, options) => {
      calls.push({ url, options });
      return {
        ok: true,
        status: 201,
        json: async () => ({ duplicate: false })
      };
    }
  });

  assert.deepEqual(response, { duplicate: false });
  assert.equal(calls[0].url, "http://127.0.0.1:8080/api/ingest/local-ai-session");
  assert.equal(calls[0].options.headers.authorization, "Bearer token-1");
});

async function withTempDir(fn) {
  const dir = await mkdtemp(path.join(tmpdir(), "learnloop-upload-queue-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

function payload(repoIdentityHash, idempotencyKey) {
  return {
    organizationId: "org-demo",
    title: `Session ${idempotencyKey}`,
    sourceKind: "local_ai_session",
    repoIdentityHash,
    repositoryDisplayLabel: `Repo ${repoIdentityHash}`,
    toolProvider: "watcher",
    timestampBucket: "2026-05-20T01:00Z",
    idempotencyKey,
    autoAttribution: "manual_or_unknown",
    attributionConfidence: 0.2,
    attributionReasons: ["repo_changed"],
    artifacts: [
      {
        itemType: "file_after",
        repoRelativePath: "src/service.ts",
        sizeBytes: 25,
        metadata: {},
        contentHash: "a".repeat(64),
        contentTruncated: false,
        limitReason: null,
        content: "export const generated = true;\n"
      }
    ]
  };
}
