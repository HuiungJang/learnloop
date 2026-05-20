import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import { chmod, mkdtemp, readFile, realpath, rm, stat, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { Writable } from "node:stream";
import test from "node:test";
import {
  buildEndEvent,
  createOutputCollector,
  installCodexShim,
  isLoopbackCompanionUrl,
  repairCodexShim,
  runProviderShim,
  sendCompanionEvent,
  shouldCaptureProviderOutput,
  statusCodexShim,
  uninstallCodexShim
} from "../scripts/local-ai-shim.mjs";

test("codex shim installs, repairs, reports PATH precedence, and uninstalls without overwriting the original", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    const originalPath = path.join(originalDir, "codex");
    await writeExecutable(originalPath, "#!/usr/bin/env sh\necho original\n");
    await mkdirp(shimDir);
    await chmod(shimDir, 0o777);

    const install = await installCodexShim({ shimDir, pathEnv: originalDir });
    assert.equal(install.originalPath, await realpath(originalPath));
    assert.equal(await readFile(originalPath, "utf8"), "#!/usr/bin/env sh\necho original\n");
    assert.match(await readFile(path.join(shimDir, "codex"), "utf8"), /LEARNLOOP_CODEX_SHIM/);
    assert.equal((await stat(shimDir)).mode & 0o777, 0o700);
    assert.equal((await stat(path.join(shimDir, ".metadata"))).mode & 0o777, 0o700);

    const inactiveStatus = await statusCodexShim({ shimDir, pathEnv: `${originalDir}${path.delimiter}${shimDir}` });
    assert.equal(inactiveStatus.installed, true);
    assert.equal(inactiveStatus.active, false);
    assert.ok(inactiveStatus.problems.includes("path_precedence"));

    const activeStatus = await statusCodexShim({ shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(activeStatus.active, true);

    await rm(path.join(shimDir, "codex"), { force: true });
    const repaired = await repairCodexShim({ shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(repaired.originalPath, await realpath(originalPath));
    assert.match(await readFile(path.join(shimDir, "codex"), "utf8"), /LEARNLOOP_CODEX_SHIM/);

    const uninstalled = await uninstallCodexShim({ shimDir });
    assert.equal(uninstalled.removed, true);
    const finalStatus = await statusCodexShim({ shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(finalStatus.installed, false);
  });
});

test("codex shim detects recursive install candidates and changed original paths", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const nextOriginalDir = path.join(dir, "next-original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    await writeExecutable(path.join(originalDir, "codex"), "#!/usr/bin/env sh\necho original\n");
    await writeExecutable(path.join(nextOriginalDir, "codex"), "#!/usr/bin/env sh\necho next\n");

    await installCodexShim({ shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    const changed = await statusCodexShim({ shimDir, pathEnv: `${shimDir}${path.delimiter}${nextOriginalDir}` });
    assert.ok(changed.warnings.includes("original_path_changed"));

    const recursiveShimDir = path.join(dir, "recursive-shim");
    await writeExecutable(path.join(recursiveShimDir, "codex"), "#!/usr/bin/env sh\n# LEARNLOOP_CODEX_SHIM\nexit 1\n");
    await assert.rejects(
      installCodexShim({ shimDir: path.join(dir, "new-shims"), pathEnv: recursiveShimDir }),
      /Original codex resolves to a LearnLoop shim/
    );
  });
});

test("codex shim refuses unmanaged directories, symlink targets, and non-marker uninstall targets", async () => {
  await withTempDir(async (dir) => {
    const realBinDir = path.join(dir, "real-bin");
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    await writeExecutable(path.join(realBinDir, "codex"), "#!/usr/bin/env sh\necho real\n");
    await writeExecutable(path.join(originalDir, "codex"), "#!/usr/bin/env sh\necho original\n");

    await assert.rejects(
      installCodexShim({ shimDir: realBinDir, pathEnv: originalDir }),
      /non-empty unmanaged LearnLoop shim directory/
    );

    await installCodexShim({ shimDir, pathEnv: originalDir });
    await uninstallCodexShim({ shimDir });
    await symlink(path.join(realBinDir, "codex"), path.join(shimDir, "codex"));
    await assert.rejects(
      installCodexShim({ shimDir, pathEnv: originalDir }),
      /unsafe LearnLoop shim file/
    );
    assert.equal(await readFile(path.join(realBinDir, "codex"), "utf8"), "#!/usr/bin/env sh\necho real\n");

    await rm(path.join(shimDir, "codex"));
    await writeExecutable(path.join(shimDir, "codex"), "#!/usr/bin/env sh\necho user-owned\n");
    await assert.rejects(
      uninstallCodexShim({ shimDir }),
      /non-LearnLoop shim file/
    );
  });
});

test("codex shim preserves stdout, stderr, stdin, args, and exit code when companion is down", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    await writeFakeCodex(path.join(originalDir, "codex"));
    await installCodexShim({ shimDir, pathEnv: originalDir });

    const success = await spawnFile(path.join(shimDir, "codex"), ["success", "ARG"], {
      env: {
        ...process.env,
        LEARNLOOP_SHIM_DIR: shimDir,
        LEARNLOOP_LOCAL_AI_COMPANION_URL: "http://127.0.0.1:9"
      }
    });
    assert.equal(success.code, 0);
    assert.equal(success.stdout, "out:ARG\n");
    assert.equal(success.stderr, "err:ARG\n");

    const piped = await spawnFile(path.join(shimDir, "codex"), ["cat"], {
      input: "stdin-value",
      env: { ...process.env, LEARNLOOP_SHIM_DIR: shimDir }
    });
    assert.equal(piped.code, 0);
    assert.equal(piped.stdout, "stdin-value");

    const failed = await spawnFile(path.join(shimDir, "codex"), ["fail"], {
      env: { ...process.env, LEARNLOOP_SHIM_DIR: shimDir }
    });
    assert.equal(failed.code, 37);
    assert.equal(failed.stdout, "failed-out\n");
    assert.equal(failed.stderr, "failed-err\n");
  });
});

test("codex shim forwards large output and child signals", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    await writeFakeCodex(path.join(originalDir, "codex"));
    await installCodexShim({ shimDir, pathEnv: originalDir });

    const large = await spawnFile(path.join(shimDir, "codex"), ["large"], {
      env: { ...process.env, LEARNLOOP_SHIM_DIR: shimDir }
    });
    assert.equal(large.code, 0);
    assert.ok(large.stdout.includes("line-199"));

    const signaled = await spawnAndSignal(path.join(shimDir, "codex"), ["signal"], {
      env: { ...process.env, LEARNLOOP_SHIM_DIR: shimDir }
    });
    assert.equal(signaled.code, 143);
    assert.match(signaled.stdout, /got-term/);

    const selfQuit = await spawnFile(path.join(shimDir, "codex"), ["self-quit"], {
      env: { ...process.env, LEARNLOOP_SHIM_DIR: shimDir }
    });
    assert.equal(selfQuit.code, 131);
  });
});

test("codex shim events omit env vars and suppress stdout and stderr content", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    const events = [];
    await writeFakeCodex(path.join(originalDir, "codex"));
    await installCodexShim({ shimDir, pathEnv: originalDir });

    const stdout = captureStream();
    const stderr = captureStream();
    const result = await runProviderShim("codex", ["secret"], {
      shimDir,
      pathEnv: `${shimDir}${path.delimiter}${originalDir}`,
      env: { ...process.env, SECRET_SHOULD_NOT_APPEAR: "sk-envsecret123456" },
      stdin: "ignore",
      stdout,
      stderr,
      eventSender: (event) => {
        events.push(event);
      }
    });

    assert.equal(result.exitCode, 0);
    assert.equal(stdout.value(), "token=sk-testsecret1234567890\n");
    assert.equal(stderr.value(), "stderr-secret=sk-stderrsecret1234567890\n");
    const serializedEvents = JSON.stringify(events);
    assert.doesNotMatch(serializedEvents, /SECRET_SHOULD_NOT_APPEAR|sk-envsecret/);
    assert.doesNotMatch(serializedEvents, /sk-testsecret1234567890|sk-stderrsecret1234567890/);
    const endEvent = events.find((event) => event.type === "shim_end");
    assert.equal(endEvent.stdoutExcerpt, null);
    assert.equal(endEvent.stdoutSuppressed, true);
    assert.equal(endEvent.stderrExcerpt, null);
    assert.equal(endEvent.stderrSuppressed, true);
  });
});

test("codex shim only posts companion events to loopback receivers", async () => {
  assert.equal(isLoopbackCompanionUrl("http://127.0.0.1:4317"), true);
  assert.equal(isLoopbackCompanionUrl("http://127.2.3.4:4317"), true);
  assert.equal(isLoopbackCompanionUrl("http://localhost:4317"), true);
  assert.equal(isLoopbackCompanionUrl("http://[::1]:4317"), true);
  assert.equal(isLoopbackCompanionUrl("https://example.com"), false);
  assert.equal(isLoopbackCompanionUrl("http://192.168.1.2:4317"), false);

  const received = [];
  const server = http.createServer((req, res) => {
    received.push(req.url);
    req.resume();
    res.writeHead(202, { "content-type": "application/json" });
    res.end('{"status":"accepted"}\n');
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    await sendCompanionEvent({ type: "shim_start", provider: "codex_cli" }, { baseUrl: `http://127.0.0.1:${port}`, unref: false });
    await sendCompanionEvent({ type: "shim_start", provider: "codex_cli" }, { baseUrl: "https://example.com", unref: false });
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
  assert.deepEqual(received, ["/shim/events"]);
});

test("local companion accepts shim events on the real receiver path", async () => {
  const port = await findOpenPort();
  const child = spawn(process.execPath, ["scripts/local-ai-companion.mjs"], {
    env: { ...process.env, LEARNLOOP_LOCAL_AI_PORT: String(port) },
    stdio: ["ignore", "ignore", "pipe"]
  });
  let stderr = "";
  child.stderr.on("data", (chunk) => {
    stderr += chunk.toString("utf8");
  });

  try {
    await waitForHealth(`http://127.0.0.1:${port}/health`);
    const post = await fetch(`http://127.0.0.1:${port}/shim/events`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        type: "shim_start",
        provider: "codex_cli",
        invocationId: "test",
        cwd: "/Users/example/private-repo",
        stdoutExcerpt: "must-not-be-returned"
      })
    });
    assert.equal(post.status, 202);

    const status = await fetch(`http://127.0.0.1:${port}/shim/events/status`).then((response) => response.json());
    assert.deepEqual(status, {
      queued: 1,
      lastEventType: "shim_start",
      lastProvider: "codex_cli"
    });
  } finally {
    child.kill("SIGTERM");
    await new Promise((resolve) => child.once("close", resolve));
  }

  assert.equal(stderr, "");
});

test("codex shim inherits TTY streams for interactive provider sessions", () => {
  const tty = { isTTY: true };
  assert.equal(shouldCaptureProviderOutput({ stdin: tty, stdout: tty, stderr: tty }), false);
  assert.equal(shouldCaptureProviderOutput({ stdin: tty, stdout: captureStream(), stderr: tty }), true);
  assert.equal(shouldCaptureProviderOutput({ stdin: tty, stdout: tty, stderr: tty, forceCapture: true }), true);
});

test("codex shim event latency stays within healthy and companion-down budgets", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    await writeFakeCodex(path.join(originalDir, "codex"));
    await installCodexShim({ shimDir, pathEnv: originalDir });

    const healthyMeasurements = [];
    for (let i = 0; i < 15; i += 1) {
      const started = performance.now();
      const result = await runProviderShim("codex", ["noop"], {
        shimDir,
        pathEnv: `${shimDir}${path.delimiter}${originalDir}`,
        stdin: "ignore",
        stdout: captureStream(),
        stderr: captureStream(),
        eventSender: () => Promise.resolve()
      });
      assert.equal(result.exitCode, 0);
      healthyMeasurements.push(performance.now() - started);
    }
    healthyMeasurements.sort((a, b) => a - b);
    const healthyP95 = healthyMeasurements[Math.floor(healthyMeasurements.length * 0.95) - 1];
    assert.ok(healthyP95 < 100, `expected healthy p95 below 100ms, got ${healthyP95.toFixed(2)}ms`);

    const downMeasurements = [];
    for (let i = 0; i < 15; i += 1) {
      const started = performance.now();
      const result = await runProviderShim("codex", ["noop"], {
        shimDir,
        pathEnv: `${shimDir}${path.delimiter}${originalDir}`,
        stdin: "ignore",
        stdout: captureStream(),
        stderr: captureStream(),
        eventSender: () => new Promise(() => {})
      });
      assert.equal(result.exitCode, 0);
      downMeasurements.push(performance.now() - started);
    }
    downMeasurements.sort((a, b) => a - b);
    const downP95 = downMeasurements[Math.floor(downMeasurements.length * 0.95) - 1];
    assert.ok(downP95 < 50, `expected companion-down p95 below 50ms, got ${downP95.toFixed(2)}ms`);
  });
});

test("codex shim end event builder bounds excerpts", () => {
  const output = createOutputCollector();
  output.pushStdout(Buffer.from("a".repeat(5000)));
  output.pushStderr(Buffer.from("secret=sk-hidden1234567890"));
  const event = buildEndEvent({
    provider: "codex_cli",
    invocationId: "invocation",
    cwd: "/repo",
    command: "codex",
    startedAt: new Date("2026-05-20T00:00:00.000Z"),
    endedAt: new Date("2026-05-20T00:00:01.000Z"),
    code: 0,
    signal: null,
    output
  });
  assert.equal(event.stdoutExcerpt, null);
  assert.equal(event.stdoutSuppressed, true);
  assert.equal(event.stderrExcerpt, null);
  assert.equal(event.stderrSuppressed, true);
  assert.equal(event.excerptLimitBytes, 0);
});

async function withTempDir(fn) {
  const dir = await mkdtemp(path.join(tmpdir(), "learnloop-codex-shim-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

async function writeExecutable(filePath, content) {
  await mkdirp(path.dirname(filePath));
  await writeFile(filePath, content, { mode: 0o700 });
}

async function writeFakeCodex(filePath) {
  await writeExecutable(
    filePath,
    `#!/usr/bin/env sh
case "$1" in
  success)
    printf 'out:%s\\n' "$2"
    printf 'err:%s\\n' "$2" >&2
    exit 0
    ;;
  fail)
    printf 'failed-out\\n'
    printf 'failed-err\\n' >&2
    exit 37
    ;;
  cat)
    cat
    exit 0
    ;;
  large)
    i=0
    while [ "$i" -lt 200 ]; do
      printf 'line-%03d\\n' "$i"
      i=$((i + 1))
    done
    exit 0
    ;;
  signal)
    trap 'printf "got-term\\n"; exit 143' TERM
    while :; do sleep 1; done
    ;;
  self-quit)
    kill -QUIT $$
    sleep 1
    ;;
  secret)
    printf 'token=sk-testsecret1234567890\\n'
    printf 'stderr-secret=sk-stderrsecret1234567890\\n' >&2
    exit 0
    ;;
  noop)
    exit 0
    ;;
esac
exit 0
`
  );
}

async function mkdirp(dir) {
  await import("node:fs/promises").then(({ mkdir }) => mkdir(dir, { recursive: true }));
}

function spawnFile(filePath, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(filePath, args, {
      env: options.env ?? process.env,
      stdio: ["pipe", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.on("close", (code, signal) => resolve({ code, signal, stdout, stderr }));
    if (options.input !== undefined) {
      child.stdin.end(options.input);
    } else {
      child.stdin.end();
    }
  });
}

function spawnAndSignal(filePath, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(filePath, args, {
      env: options.env ?? process.env,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString("utf8");
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.on("close", (code, signal) => resolve({ code, signal, stdout, stderr }));
    setTimeout(() => child.kill("SIGTERM"), 100);
  });
}

function findOpenPort() {
  return new Promise((resolve, reject) => {
    const server = http.createServer();
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

async function waitForHealth(url) {
  let lastError;
  for (let i = 0; i < 50; i += 1) {
    try {
      const response = await fetch(url);
      if (response.ok) return;
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  throw lastError ?? new Error("health check timed out");
}

function captureStream() {
  let content = "";
  const stream = new Writable({
    write(chunk, _encoding, callback) {
      content += chunk.toString("utf8");
      callback();
    }
  });
  stream.value = () => content;
  return stream;
}
