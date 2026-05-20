import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import { GitReconciliationCache, reconcileGitRepository } from "../scripts/local-ai-git-reconcile.mjs";
import { LocalAiWatcherRegistry } from "../scripts/local-ai-watcher-registry.mjs";

test("git reconciliation caches metadata and sanitizes remote URLs", async () => {
  const commands = [];
  const commandOptions = [];
  const cache = new GitReconciliationCache({ ttlMs: 5000, now: () => 1000 });
  const commandRunner = async (args, options) => {
    commands.push(args[0]);
    commandOptions.push({ timeoutMs: options.timeoutMs, maxOutputBytes: options.maxOutputBytes });
    if (args[0] === "rev-parse") return ok("/tmp/repo\n");
    if (args[0] === "branch") return ok("main\n");
    if (args[0] === "remote") return ok("https://user:secret@example.com/org/repo.git\n");
    if (args[0] === "status") return ok(" M src/app.ts\0");
    if (args[0] === "diff") return ok("diff --git a/src/app.ts b/src/app.ts\n");
    return ok("");
  };

  const first = await reconcileGitRepository(
    { repoRoot: "/tmp/repo", changedPaths: ["src/app.ts"] },
    { cache, commandRunner, timeoutMs: 1234, maxOutputBytes: 4321 }
  );
  const second = await reconcileGitRepository(
    { repoRoot: "/tmp/repo", changedPaths: ["src/app.ts"] },
    { cache, commandRunner, timeoutMs: 1234, maxOutputBytes: 4321 }
  );

  assert.equal(first.remoteUrl, "https://example.com/org/repo.git");
  assert.equal(second.branchName, "main");
  assert.equal(commands.filter((command) => command === "rev-parse").length, 1);
  assert.equal(commands.filter((command) => command === "branch").length, 1);
  assert.equal(commands.filter((command) => command === "remote").length, 1);
  assert.equal(commands.filter((command) => command === "status").length, 2);
  assert.equal(commands.filter((command) => command === "diff").length, 2);
  assert.equal(commandOptions.every((options) => options.timeoutMs === 1234 && options.maxOutputBytes === 4321), true);
});

test("watcher debounce triggers one bounded status pass and one final diff pass for rapid saves", async () => {
  await withTempDir(async (repoRoot) => {
    await initRepo(repoRoot);
    const commandLog = [];
    const cache = new GitReconciliationCache({ ttlMs: 5000 });
    let listener = null;
    const scheduled = [];
    const registry =
      new LocalAiWatcherRegistry({
        debounceMs: 250,
        watchFactory: (_repoRoot, nextListener) => {
          listener = nextListener;
          return { close: () => {} };
        },
        setTimeoutFn: (callback) => {
          const handle = { callback, unref: () => {} };
          scheduled.push(handle);
          return handle;
        },
        clearTimeoutFn: () => {},
        reconcileRepository: (input) =>
          reconcileGitRepository(input, {
            cache,
            timeoutMs: 5000,
            maxOutputBytes: 1024 * 1024,
            onGitCommand: (event) => commandLog.push(event)
          })
      });

    registry.updateRepository({
      repoIdentityHash: "repo-git-rapid",
      repositoryDisplayLabel: "rapid",
      repoRoot,
      status: "approved"
    });

    for (let index = 0; index < 100; index += 1) {
      await writeFile(path.join(repoRoot, "src", `file-${index}.txt`), `changed-${index}\n`);
      listener("change", `src/file-${index}.txt`);
    }

    scheduled.at(-1).callback();
    await registry.drainReconciliations();

    assert.equal(commandLog.filter((event) => event.name === "status").length, 1);
    assert.equal(commandLog.filter((event) => event.name === "diff").length, 1);
    assert.deepEqual(commandLog.find((event) => event.name === "status").args, ["status", "--porcelain=v1", "-z"]);
    const [status] = registry.list();
    assert.equal(status.lastReconciliationStatus, "ok");
    assert.equal(status.lastReconciliationChangedFileCount, 100);
    assert.equal(status.lastReconciliationDiffCandidateCount, 100);
  });
});

async function initRepo(repoRoot) {
  await runGit(repoRoot, ["init"]);
  await runGit(repoRoot, ["config", "user.email", "learnloop@example.local"]);
  await runGit(repoRoot, ["config", "user.name", "LearnLoop"]);
  await runGit(repoRoot, ["remote", "add", "origin", "https://user:secret@example.com/org/repo.git"]);
  await mkdir(path.join(repoRoot, "src"), { recursive: true });
  for (let index = 0; index < 100; index += 1) {
    await writeFile(path.join(repoRoot, "src", `file-${index}.txt`), `initial-${index}\n`);
  }
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
  const dir = await mkdtemp(path.join(tmpdir(), "learnloop-git-reconcile-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

function ok(stdout) {
  return { ok: true, stdout, stderr: "", outputTruncated: false };
}
