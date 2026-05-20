import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
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
    const reconciliationResults = [];
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
          }).then((result) => {
            reconciliationResults.push(result);
            return result;
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
    const [result] = reconciliationResults;
    assert.equal(result.afterSnapshots.length, 100);
    assert.equal(
      result.afterSnapshots.find((snapshot) => snapshot.repoRelativePath === "src/file-99.txt").contentText,
      "changed-99\n"
    );
    assert.match(result.diff, /changed-99/);
  });
});

test("git reconciliation rejects traversal paths before building diff args", async () => {
  const commandLog = [];
  const commandRunner = async (args) => {
    commandLog.push(args);
    if (args[0] === "rev-parse") return ok("/tmp/repo\n");
    if (args[0] === "branch") return ok("main\n");
    if (args[0] === "remote") return ok("");
    if (args[0] === "status") return ok(" M ../secret.txt\0 M src/app.ts\0");
    if (args[0] === "diff") return ok("diff --git a/src/app.ts b/src/app.ts\n");
    return ok("");
  };

  const result = await reconcileGitRepository({ repoRoot: "/tmp/repo" }, { commandRunner });
  const diffArgs = commandLog.find((args) => args[0] === "diff");
  assert.deepEqual(result.diffCandidates.map((file) => file.repoRelativePath), ["src/app.ts"]);
  assert.equal(diffArgs.includes("../secret.txt"), false);
});

test("git reconciliation filters ignored, sensitive, binary, and symlink-escape paths before diff", async () => {
  await withTempDir(async (dir) => {
    const repoRoot = path.join(dir, "repo");
    const outsideTarget = path.join(dir, "outside-secret.txt");
    await mkdir(repoRoot, { recursive: true });
    await initPathSafetyRepo(repoRoot);
    await writeFile(outsideTarget, "outside\n");
    await writeFile(path.join(repoRoot, "src", "safe.ts"), "export const safe = 2;\n");
    await writeFile(path.join(repoRoot, ".env"), "TOKEN=sk-should-not-diff\n");
    await writeFile(path.join(repoRoot, "secrets", "private.key"), "private-key\n");
    await writeFile(path.join(repoRoot, "assets", "logo.png"), "not really png\n");
    await writeFile(path.join(repoRoot, "dist", "generated.js"), "generated\n");
    await symlink(outsideTarget, path.join(repoRoot, "link-outside.txt"));

    const commandLog = [];
    const result = await reconcileGitRepository(
      { repoRoot },
      {
        timeoutMs: 5000,
        onGitCommand: (event) => commandLog.push(event)
      }
    );

    assert.equal(result.remoteUrl, "https://example.com/org/repo.git");
    assert.deepEqual(result.diffCandidates.map((file) => file.repoRelativePath), ["src/safe.ts"]);
    assert.equal(result.diff.includes("sk-should-not-diff"), false);
    assert.equal(commandLog.find((event) => event.name === "diff").args.includes(".env"), false);
    assertIgnored(result.ignoredFiles, ".env", "sensitive_file");
    assertIgnored(result.ignoredFiles, "secrets/private.key", "sensitive_file");
    assertIgnored(result.ignoredFiles, "assets/logo.png", "binary_or_archive");
    assertIgnored(result.ignoredFiles, "dist/generated.js", "ignored_directory");
    assertIgnored(result.ignoredFiles, "link-outside.txt", "symlink_escape");
    assertIgnored(result.metadataOnlyFiles, "assets/logo.png", "binary_or_archive");
  });
});

test("git reconciliation keeps oversized after snapshots metadata-only and excludes them from diff", async () => {
  await withTempDir(async (repoRoot) => {
    await initSingleFileRepo(repoRoot, "src/large.ts", "small\n");
    await writeFile(path.join(repoRoot, "src", "large.ts"), "x".repeat(20));

    const result = await reconcileGitRepository(
      { repoRoot },
      {
        timeoutMs: 5000,
        maxAfterSnapshotBytes: 5
      }
    );

    assert.deepEqual(result.diffCandidates, []);
    const [snapshot] = result.afterSnapshots;
    assert.equal(snapshot.repoRelativePath, "src/large.ts");
    assert.equal(snapshot.status, "metadata_only");
    assert.equal(snapshot.reason, "oversized_after_snapshot");
    assert.equal(snapshot.contentText, null);
    assert.equal(snapshot.sizeBytes, 20);
    assertIgnored(result.metadataOnlyFiles, "src/large.ts", "oversized_after_snapshot");
    assert.equal(result.diff, "");
  });
});

test("git reconciliation reports diff truncation metadata when capped", async () => {
  await withTempDir(async (repoRoot) => {
    await initSingleFileRepo(repoRoot, "src/noisy.ts", "initial\n");
    await writeFile(path.join(repoRoot, "src", "noisy.ts"), `${"changed\n".repeat(400)}`);

    const result = await reconcileGitRepository(
      { repoRoot },
      {
        timeoutMs: 5000,
        maxOutputBytes: 200
      }
    );

    assert.equal(result.status, "ok");
    assert.equal(result.diffTruncated, true);
    assert.equal(result.diffMetadata.contentTruncated, true);
    assert.equal(result.diffMetadata.limitReason, "diff_output_limit");
    assert.equal(result.diffMetadata.limitBytes, 200);
    assert.ok(result.diffMetadata.sizeBytes <= 200);
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

async function initPathSafetyRepo(repoRoot) {
  await runGit(repoRoot, ["init"]);
  await runGit(repoRoot, ["config", "user.email", "learnloop@example.local"]);
  await runGit(repoRoot, ["config", "user.name", "LearnLoop"]);
  await runGit(repoRoot, ["remote", "add", "origin", "https://user:secret@example.com/org/repo.git"]);
  await mkdir(path.join(repoRoot, "src"), { recursive: true });
  await mkdir(path.join(repoRoot, "secrets"), { recursive: true });
  await mkdir(path.join(repoRoot, "assets"), { recursive: true });
  await mkdir(path.join(repoRoot, "dist"), { recursive: true });
  await writeFile(path.join(repoRoot, "src", "safe.ts"), "export const safe = 1;\n");
  await writeFile(path.join(repoRoot, ".env"), "TOKEN=initial\n");
  await writeFile(path.join(repoRoot, "secrets", "private.key"), "initial-key\n");
  await writeFile(path.join(repoRoot, "assets", "logo.png"), "initial-png\n");
  await writeFile(path.join(repoRoot, "dist", "generated.js"), "initial-generated\n");
  await runGit(repoRoot, ["add", "-A"]);
  await runGit(repoRoot, ["commit", "-m", "init"]);
}

async function initSingleFileRepo(repoRoot, repoRelativePath, content) {
  await runGit(repoRoot, ["init"]);
  await runGit(repoRoot, ["config", "user.email", "learnloop@example.local"]);
  await runGit(repoRoot, ["config", "user.name", "LearnLoop"]);
  await mkdir(path.dirname(path.join(repoRoot, repoRelativePath)), { recursive: true });
  await writeFile(path.join(repoRoot, repoRelativePath), content);
  await runGit(repoRoot, ["add", "-A"]);
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

function assertIgnored(ignoredFiles, repoRelativePath, reason) {
  assert.ok(
    ignoredFiles.some((file) => file.repoRelativePath === repoRelativePath && file.reason === reason),
    `expected ${repoRelativePath} to be ignored as ${reason}`
  );
}
