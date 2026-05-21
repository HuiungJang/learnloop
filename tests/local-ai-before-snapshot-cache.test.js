import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, unlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { BeforeSnapshotCache } from "../scripts/local-ai-before-snapshot-cache.mjs";
import { reconcileGitRepository } from "../scripts/local-ai-git-reconcile.mjs";

test("before snapshot cache captures clean-to-dirty and deleted files from HEAD", async () => {
  await withTempDir(async (repoRoot) => {
    await initBeforeSnapshotRepo(repoRoot);
    const cache = new BeforeSnapshotCache();
    await writeFile(path.join(repoRoot, "src", "clean.ts"), "export const clean = 2;\n");
    await unlink(path.join(repoRoot, "src", "deleted.ts"));

    const result = await reconcileGitRepository(
      { repoRoot, repoIdentityHash: "repo-before" },
      { beforeSnapshotCache: cache, timeoutMs: 5000 }
    );

    assertSnapshot(result.beforeSnapshots, "src/clean.ts", "captured", "export const clean = 1;\n");
    assertSnapshot(result.beforeSnapshots, "src/deleted.ts", "captured", "export const deleted = 1;\n");
  });
});

test("before snapshot cache marks files already dirty when watcher starts as unavailable", async () => {
  await withTempDir(async (repoRoot) => {
    await initBeforeSnapshotRepo(repoRoot);
    const cache = new BeforeSnapshotCache();
    await writeFile(path.join(repoRoot, "src", "dirty.ts"), "export const dirty = 2;\n");

    await reconcileGitRepository(
      { repoRoot, repoIdentityHash: "repo-before", initialScan: true },
      { beforeSnapshotCache: cache, timeoutMs: 5000 }
    );
    const result = await reconcileGitRepository(
      { repoRoot, repoIdentityHash: "repo-before" },
      { beforeSnapshotCache: cache, timeoutMs: 5000 }
    );

    const snapshot = result.beforeSnapshots.find((entry) => entry.repoRelativePath === "src/dirty.ts");
    assert.equal(snapshot.status, "unavailable");
    assert.equal(snapshot.reason, "pre_existing_dirty");
    assert.equal(snapshot.contentText, null);
  });
});

test("before snapshot cache uses LRU eviction and metadata-only oversized entries", async () => {
  const cache = new BeforeSnapshotCache({ maxEntriesPerRepo: 2, maxBytesPerRepo: 100, maxSnapshotBytes: 4 });
  const repoIdentityHash = "repo-cache";
  await cache.captureSnapshots({
    repoIdentityHash,
    files: [{ repoRelativePath: "a.ts" }, { repoRelativePath: "b.ts" }],
    readSnapshot: async (repoRelativePath) => ({ ok: true, content: `${repoRelativePath}:1` })
  });
  await cache.captureSnapshots({
    repoIdentityHash,
    files: [{ repoRelativePath: "a.ts" }],
    readSnapshot: async () => {
      throw new Error("cached entries should not be reread");
    }
  });
  await cache.captureSnapshots({
    repoIdentityHash,
    files: [{ repoRelativePath: "c.ts" }],
    readSnapshot: async () => ({ ok: true, content: "c" })
  });
  assert.deepEqual(cache.list(repoIdentityHash).map((entry) => entry.repoRelativePath), ["a.ts", "c.ts"]);

  const oversizedCache = new BeforeSnapshotCache({ maxSnapshotBytes: 4 });
  const [oversized] = await oversizedCache.captureSnapshots({
    repoIdentityHash: "repo-oversized",
    files: [{ repoRelativePath: "large.ts" }],
    readSnapshot: async () => ({ ok: true, content: "12345" })
  });
  assert.equal(oversized.status, "metadata_only");
  assert.equal(oversized.reason, "oversized_before_snapshot");
  assert.equal(oversized.contentText, null);
  assert.equal(oversized.sizeBytes, 5);
});

async function initBeforeSnapshotRepo(repoRoot) {
  await runGit(repoRoot, ["init"]);
  await runGit(repoRoot, ["config", "user.email", "learnloop@example.local"]);
  await runGit(repoRoot, ["config", "user.name", "LearnLoop"]);
  await mkdir(path.join(repoRoot, "src"), { recursive: true });
  await writeFile(path.join(repoRoot, "src", "clean.ts"), "export const clean = 1;\n");
  await writeFile(path.join(repoRoot, "src", "deleted.ts"), "export const deleted = 1;\n");
  await writeFile(path.join(repoRoot, "src", "dirty.ts"), "export const dirty = 1;\n");
  await runGit(repoRoot, ["add", "."]);
  await runGit(repoRoot, ["commit", "-m", "init"]);
}

function runGit(cwd, args) {
  return new Promise((resolve, reject) => {
    const child = spawn("git", args, { cwd, stdio: ["ignore", "pipe", "pipe"] });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`git ${args.join(" ")} failed: ${stderr}`));
      }
    });
  });
}

async function withTempDir(fn) {
  const dir = await mkdtemp(path.join(tmpdir(), "learnloop-before-snapshot-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

function assertSnapshot(snapshots, repoRelativePath, status, contentText) {
  const snapshot = snapshots.find((entry) => entry.repoRelativePath === repoRelativePath);
  assert.equal(snapshot.status, status);
  assert.equal(snapshot.contentText, contentText);
}
