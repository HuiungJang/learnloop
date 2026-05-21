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
  forwardProviderRuntime,
  installProviderShim,
  installCodexShim,
  isLoopbackCompanionUrl,
  repairProviderShim,
  repairCodexShim,
  runProviderShim,
  sendCompanionEvent,
  shouldCaptureProviderOutput,
  statusProviderShim,
  statusCodexShim,
  uninstallProviderShim,
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

test("generic shim installer supports Gemini and Claude providers", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    await writeExecutable(path.join(originalDir, "gemini"), "#!/usr/bin/env sh\necho gemini\n");
    await writeExecutable(path.join(originalDir, "claude"), "#!/usr/bin/env sh\necho claude\n");

    const gemini = await installProviderShim("gemini", { shimDir, pathEnv: originalDir });
    const claude = await installProviderShim("claude", { shimDir, pathEnv: originalDir });

    assert.equal(gemini.provider, "gemini");
    assert.equal(gemini.command, "gemini");
    assert.equal(gemini.originalHash.length, 64);
    assert.match(await readFile(path.join(shimDir, "gemini"), "utf8"), /LEARNLOOP_LOCAL_AI_SHIM/);
    assert.equal(claude.provider, "claude");
    assert.equal(claude.command, "claude");
    assert.equal(claude.originalHash.length, 64);

    const geminiStatus = await statusProviderShim("gemini", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(geminiStatus.installed, true);
    assert.equal(geminiStatus.active, true);

    await rm(path.join(shimDir, "gemini"), { force: true });
    const repaired = await repairProviderShim("gemini", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(repaired.provider, "gemini");
    assert.match(await readFile(path.join(shimDir, "gemini"), "utf8"), /LEARNLOOP_LOCAL_AI_SHIM/);

    const removed = await uninstallProviderShim("claude", { shimDir });
    assert.equal(removed.provider, "claude");
    assert.equal((await statusProviderShim("claude", { shimDir })).installed, false);
  });
});

test("generic shim installer detects recursive shim chains for every provider", async () => {
  await withTempDir(async (dir) => {
    const recursiveShimDir = path.join(dir, "recursive-shims");
    await writeExecutable(path.join(recursiveShimDir, "gemini"), "#!/usr/bin/env sh\n# LEARNLOOP_LOCAL_AI_SHIM\nexit 1\n");

    await assert.rejects(
      installProviderShim("gemini", { shimDir: path.join(dir, "learnloop-shims"), pathEnv: recursiveShimDir }),
      /Original gemini resolves to a LearnLoop shim/
    );
  });
});

test("Claude shim installer handles install, status, repair, missing binary, recursion, and uninstall", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    const originalPath = path.join(originalDir, "claude");
    await writeExecutable(originalPath, "#!/usr/bin/env sh\necho claude\n");

    await assert.rejects(
      installProviderShim("claude", { shimDir: path.join(dir, "missing-shims"), pathEnv: path.join(dir, "missing-bin") }),
      /Original claude was not found in PATH/
    );

    const install = await installProviderShim("claude", { shimDir, pathEnv: originalDir });
    assert.equal(install.command, "claude");
    assert.equal(install.active, false);
    assert.match(install.pathGuidance, /real claude directory/);

    const inactiveStatus = await statusProviderShim("claude", { shimDir, pathEnv: `${originalDir}${path.delimiter}${shimDir}` });
    assert.ok(inactiveStatus.problems.includes("path_precedence"));

    await assert.rejects(
      installProviderShim("claude", { shimDir: path.join(dir, "recursive-shims"), pathEnv: shimDir }),
      /Original claude resolves to a LearnLoop shim/
    );

    await rm(path.join(shimDir, "claude"), { force: true });
    const repaired = await repairProviderShim("claude", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(repaired.command, "claude");
    assert.equal(repaired.originalPath, await realpath(originalPath));

    const removed = await uninstallProviderShim("claude", { shimDir });
    assert.equal(removed.removed, true);
    assert.equal((await statusProviderShim("claude", { shimDir })).installed, false);
  });
});

test("Gemini shim installer handles broken metadata and stays independent from other shims", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    const geminiPath = path.join(originalDir, "gemini");
    await writeExecutable(geminiPath, "#!/usr/bin/env sh\necho gemini\n");
    await writeExecutable(path.join(originalDir, "codex"), "#!/usr/bin/env sh\necho codex\n");
    await writeExecutable(path.join(originalDir, "claude"), "#!/usr/bin/env sh\necho claude\n");

    await assert.rejects(
      installProviderShim("gemini", { shimDir: path.join(dir, "missing-shims"), pathEnv: path.join(dir, "missing-bin") }),
      /Original gemini was not found in PATH/
    );

    const install = await installProviderShim("gemini", { shimDir, pathEnv: originalDir });
    assert.equal(install.provider, "gemini");
    assert.equal(install.command, "gemini");
    assert.equal(install.active, false);
    assert.match(install.pathGuidance, /real gemini directory/);

    const inactiveStatus = await statusProviderShim("gemini", { shimDir, pathEnv: `${originalDir}${path.delimiter}${shimDir}` });
    assert.ok(inactiveStatus.problems.includes("path_precedence"));

    await installProviderShim("codex", { shimDir, pathEnv: originalDir });
    await installProviderShim("claude", { shimDir, pathEnv: originalDir });
    await writeFile(path.join(shimDir, ".metadata", "gemini.json"), "{broken json\n");

    const brokenStatus = await statusProviderShim("gemini", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(brokenStatus.installed, false);
    assert.ok(brokenStatus.problems.includes("not_installed"));

    await rm(path.join(shimDir, "gemini"), { force: true });
    const repaired = await repairProviderShim("gemini", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` });
    assert.equal(repaired.provider, "gemini");
    assert.equal(repaired.originalPath, await realpath(geminiPath));
    assert.match(await readFile(path.join(shimDir, "gemini"), "utf8"), /LEARNLOOP_LOCAL_AI_SHIM/);

    const removed = await uninstallProviderShim("gemini", { shimDir });
    assert.equal(removed.removed, true);
    assert.equal((await statusProviderShim("gemini", { shimDir })).installed, false);
    assert.equal((await statusProviderShim("codex", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` })).installed, true);
    assert.equal((await statusProviderShim("claude", { shimDir, pathEnv: `${shimDir}${path.delimiter}${originalDir}` })).installed, true);
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

test("generic provider runtime preserves args and emits provider-specific events", async () => {
  await withTempDir(async (dir) => {
    const originalPath = path.join(dir, "gemini");
    const stdout = captureStream();
    const stderr = captureStream();
    const events = [];
    await writeFakeCodex(originalPath);

    const result = await forwardProviderRuntime("gemini", ["success", "ARG"], {
      originalPath,
      stdin: "ignore",
      stdout,
      stderr,
      eventSender: (event) => events.push(event)
    });

    assert.equal(result.exitCode, 0);
    assert.equal(stdout.value(), "out:ARG\n");
    assert.equal(stderr.value(), "err:ARG\n");
    assert.equal(events[0].provider, "gemini_cli");
    assert.equal(events.at(-1).provider, "gemini_cli");
  });
});

test("Claude shim runtime forwards interactive sessions and captures only redacted print output", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    const pathEnv = `${shimDir}${path.delimiter}${originalDir}`;
    await writeFakeClaude(path.join(originalDir, "claude"));
    await installProviderShim("claude", { shimDir, pathEnv: originalDir });

    const tty = { isTTY: true };
    assert.equal(shouldCaptureProviderOutput({ stdin: tty, stdout: tty, stderr: tty }), false);

    const interactiveEvents = [];
    const interactive = await runProviderShim("claude", ["interactive"], {
      shimDir,
      pathEnv,
      captureOutput: false,
      eventSender: (event) => interactiveEvents.push(event)
    });
    assert.equal(interactive.exitCode, 0);
    assert.equal(interactiveEvents.at(-1).stdoutExcerpt, null);

    const printEvents = [];
    const printStdout = captureStream();
    const printStderr = captureStream();
    const printed = await runProviderShim("claude", ["--print", "safe"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: printStdout,
      stderr: printStderr,
      eventSender: (event) => printEvents.push(event)
    });
    assert.equal(printed.exitCode, 0);
    assert.match(printStdout.value(), /sk-claudeprintsecret/);
    assert.match(printStderr.value(), /sk-claudestderrsecret/);
    const printEnd = printEvents.find((event) => event.type === "shim_end");
    assert.equal(printEnd.provider, "claude_code");
    assert.match(printEnd.stdoutExcerpt, /token=\[redacted\]/);
    assert.equal(printEnd.stderrExcerpt, null);
    assert.equal(printEnd.stderrSuppressed, true);
    assert.equal(printEnd.excerptLimitBytes, 4096);
    assert.doesNotMatch(JSON.stringify(printEvents), /sk-claude(?:print|stderr)secret/);

    const chatEvents = [];
    const chat = await runProviderShim("claude", ["chat"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: captureStream(),
      stderr: captureStream(),
      eventSender: (event) => chatEvents.push(event)
    });
    assert.equal(chat.exitCode, 0);
    const chatEnd = chatEvents.find((event) => event.type === "shim_end");
    assert.equal(chatEnd.stdoutExcerpt, null);
    assert.equal(chatEnd.stdoutSuppressed, true);

    const largeEvents = [];
    const largeStdout = captureStream();
    const large = await runProviderShim("claude", ["--print", "large"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: largeStdout,
      stderr: captureStream(),
      eventSender: (event) => largeEvents.push(event)
    });
    assert.equal(large.exitCode, 0);
    assert.match(largeStdout.value(), /line-799/);
    const largeEnd = largeEvents.find((event) => event.type === "shim_end");
    assert.ok(Buffer.byteLength(largeEnd.stdoutExcerpt, "utf8") <= 4096);
    assert.equal(largeEnd.stdoutSuppressed, true);

    const down = await runProviderShim("claude", ["--print", "safe"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: captureStream(),
      stderr: captureStream(),
      eventSender: () => new Promise(() => {}),
      eventTimeoutMs: 1
    });
    assert.equal(down.exitCode, 0);
  });
});

test("Gemini shim runtime captures redacted output and reports missing or broken runtimes", async () => {
  await withTempDir(async (dir) => {
    const originalDir = path.join(dir, "original-bin");
    const shimDir = path.join(dir, "learnloop-shims");
    const pathEnv = `${shimDir}${path.delimiter}${originalDir}`;
    const geminiPath = path.join(originalDir, "gemini");
    await writeFakeGemini(geminiPath);
    await writeFakeCodex(path.join(originalDir, "codex"));
    await installProviderShim("gemini", { shimDir, pathEnv: originalDir });
    await installCodexShim({ shimDir, pathEnv: originalDir });

    const healthyEvents = [];
    const healthyStdout = captureStream();
    const healthyStderr = captureStream();
    const healthy = await runProviderShim("gemini", ["success"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: healthyStdout,
      stderr: healthyStderr,
      eventSender: (event) => healthyEvents.push(event)
    });
    assert.equal(healthy.exitCode, 0);
    assert.match(healthyStdout.value(), /sk-geministdoutsecret/);
    assert.match(healthyStderr.value(), /sk-geministderrsecret/);
    const healthyEnd = healthyEvents.find((event) => event.type === "shim_end");
    assert.equal(healthyEnd.provider, "gemini_cli");
    assert.match(healthyEnd.stdoutExcerpt, /token=\[redacted\]/);
    assert.match(healthyEnd.stderrExcerpt, /token=\[redacted\]/);
    assert.equal(healthyEnd.excerptLimitBytes, 4096);
    assert.doesNotMatch(JSON.stringify(healthyEvents), /sk-gemini(?:stdout|stderr)secret/);

    const largeEvents = [];
    const largeStdout = captureStream();
    const large = await runProviderShim("gemini", ["large"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: largeStdout,
      stderr: captureStream(),
      eventSender: (event) => largeEvents.push(event)
    });
    assert.equal(large.exitCode, 0);
    assert.match(largeStdout.value(), /gemini-line-799/);
    const largeEnd = largeEvents.find((event) => event.type === "shim_end");
    assert.ok(Buffer.byteLength(largeEnd.stdoutExcerpt, "utf8") <= 4096);
    assert.equal(largeEnd.stdoutSuppressed, true);

    const down = await runProviderShim("gemini", ["success"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: captureStream(),
      stderr: captureStream(),
      eventSender: () => new Promise(() => {}),
      eventTimeoutMs: 1
    });
    assert.equal(down.exitCode, 0);

    const missingEvents = [];
    await assert.rejects(
      runProviderShim("gemini", ["success"], {
        shimDir: path.join(dir, "missing-shims"),
        pathEnv: path.join(dir, "missing-bin"),
        eventSender: (event) => missingEvents.push(event),
        eventTimeoutMs: 1
      }),
      /Original gemini was not found/
    );
    const missingHealth = missingEvents.find((event) => event.type === "shim_health");
    assert.equal(missingHealth.runtimeStatus, "missing");
    assert.equal(missingHealth.runtimeProblem, "original_missing");

    await writeExecutable(geminiPath, "#!/definitely/missing/gemini\n");
    const brokenEvents = [];
    await assert.rejects(
      runProviderShim("gemini", ["success"], {
        shimDir,
        pathEnv,
        stdin: "ignore",
        stdout: captureStream(),
        stderr: captureStream(),
        eventSender: (event) => brokenEvents.push(event),
        eventTimeoutMs: 1
      })
    );
    const brokenHealth = brokenEvents.find((event) => event.type === "shim_health");
    assert.equal(brokenHealth.runtimeStatus, "broken");
    assert.equal(brokenHealth.runtimeProblem, "spawn_error");

    const codex = await runProviderShim("codex", ["success", "OK"], {
      shimDir,
      pathEnv,
      stdin: "ignore",
      stdout: captureStream(),
      stderr: captureStream(),
      eventSender: () => {}
    });
    assert.equal(codex.exitCode, 0);
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

test("codex shim default companion URL honors IPv6 loopback host settings", async () => {
  const received = [];
  const server = http.createServer((req, res) => {
    received.push(req.url);
    req.resume();
    res.writeHead(202, { "content-type": "application/json" });
    res.end('{"status":"accepted"}\n');
  });
  await new Promise((resolve) => server.listen(0, "::1", resolve));
  const { port } = server.address();
  const previousHost = process.env.LEARNLOOP_LOCAL_AI_HOST;
  const previousPort = process.env.LEARNLOOP_LOCAL_AI_PORT;
  try {
    process.env.LEARNLOOP_LOCAL_AI_HOST = "::1";
    process.env.LEARNLOOP_LOCAL_AI_PORT = String(port);
    await sendCompanionEvent({ type: "shim_start", provider: "codex_cli" }, { unref: false });
  } finally {
    if (previousHost === undefined) {
      delete process.env.LEARNLOOP_LOCAL_AI_HOST;
    } else {
      process.env.LEARNLOOP_LOCAL_AI_HOST = previousHost;
    }
    if (previousPort === undefined) {
      delete process.env.LEARNLOOP_LOCAL_AI_PORT;
    } else {
      process.env.LEARNLOOP_LOCAL_AI_PORT = previousPort;
    }
    await new Promise((resolve) => server.close(resolve));
  }
  assert.deepEqual(received, ["/shim/events"]);
});

test("local companion accepts shim events on the real receiver path", async () => {
  await withCompanion(async ({ baseUrl, token, stderr }) => {
    const tokenResponse = await fetch(`${baseUrl}/auth/token`, {
      headers: { Origin: "http://localhost:8080" }
    });
    assert.equal(tokenResponse.status, 200);
    const oauthStartToken = (await tokenResponse.json()).token;
    assert.equal(typeof oauthStartToken, "string");
    assert.notEqual(oauthStartToken, token);

    const tokenWithoutOrigin = await fetch(`${baseUrl}/auth/token`);
    assert.equal(tokenWithoutOrigin.status, 403);

    const post = await fetch(`${baseUrl}/shim/events`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-learnloop-local-token": token
      },
      body: JSON.stringify({
        type: "shim_start",
        provider: "codex_cli",
        invocationId: "test",
        cwd: "/Users/example/private-repo",
        stdoutExcerpt: "must-not-be-returned"
      })
    });
    assert.equal(post.status, 202);

    const status = await fetch(`${baseUrl}/shim/events/status`).then((response) => response.json());
    assert.deepEqual(status, {
      queued: 1,
      lastEventType: "shim_start",
      lastProvider: "codex_cli",
      lastRuntimeStatus: null,
      lastRuntimeProblem: null
    });

    const adapterError = await fetch(`${baseUrl}/shim/events`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-learnloop-local-token": token
      },
      body: JSON.stringify({
        type: "adapter_error",
        provider: "gemini_cli",
        runtimeStatus: "failed",
        runtimeProblem: "parser_failed",
        errorCode: "E_PARSE",
        stdoutExcerpt: "raw prompt and code must not be returned"
      })
    });
    assert.equal(adapterError.status, 202);

    const codexEnd = await fetch(`${baseUrl}/shim/events`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-learnloop-local-token": token
      },
      body: JSON.stringify({
        type: "shim_end",
        provider: "codex_cli",
        invocationId: "test",
        command: "codex",
        exitCode: 0,
        endedAt: "2026-05-20T00:00:01.000Z"
      })
    });
    assert.equal(codexEnd.status, 202);

    const adapterStatus = await fetch(`${baseUrl}/adapters/status`, {
      headers: { "x-learnloop-local-token": token }
    }).then((response) => response.json());
    assert.equal(adapterStatus.status, "ok");
    assert.equal(adapterStatus.adapters.find((adapter) => adapter.provider === "codex_cli").status, "ok");
    assert.equal(adapterStatus.adapters.find((adapter) => adapter.provider === "gemini_cli").status, "failed");
    assert.equal(JSON.stringify(adapterStatus).includes("raw prompt"), false);
    assert.equal(stderr(), "");
  });
});

test("local companion rejects unsafe host, origin, token, size, control, and bind cases", async () => {
  await withCompanion(async ({ baseUrl, port, token }) => {
    const goodHost = `127.0.0.1:${port}`;
    const badHost = await httpRequest(port, "/status", { host: "example.com" });
    assert.equal(badHost.status, 403);

    const nonLoopbackHost = await httpRequest(port, "/status", { host: `192.168.1.20:${port}` });
    assert.equal(nonLoopbackHost.status, 403);

    const badOrigin = await httpRequest(port, "/status", {
      host: goodHost,
      headers: { Origin: "http://evil.example" }
    });
    assert.equal(badOrigin.status, 403);

    const missingToken = await httpRequest(port, "/shim/events", {
      method: "POST",
      host: goodHost,
      body: "{}"
    });
    assert.equal(missingToken.status, 401);

    const unauthenticatedOauth = await httpRequest(port, "/oauth/start", {
      method: "POST",
      host: goodHost,
      body: JSON.stringify({ provider: "codex" })
    });
    assert.equal(unauthenticatedOauth.status, 401);

    const oauthToken = await fetch(`${baseUrl}/auth/token`, {
      headers: { Origin: "http://localhost:8080" }
    }).then((response) => response.json()).then((payload) => payload.token);
    const acceptedOauth = await httpRequest(port, "/oauth/start", {
      method: "POST",
      host: goodHost,
      token: oauthToken,
      body: JSON.stringify({ provider: "codex" })
    });
    assert.equal(acceptedOauth.status, 202);
    const reusedOauth = await httpRequest(port, "/oauth/start", {
      method: "POST",
      host: goodHost,
      token: oauthToken,
      body: JSON.stringify({ provider: "codex" })
    });
    assert.equal(reusedOauth.status, 401);

    const oversized = await httpRequest(port, "/shim/events", {
      method: "POST",
      host: goodHost,
      token,
      body: JSON.stringify({ payload: "x".repeat(11_000) })
    });
    assert.equal(oversized.status, 413);

    const unauthenticatedRevoke = await httpRequest(port, "/consent/revoke", {
      method: "POST",
      host: goodHost,
      body: "{}"
    });
    assert.equal(unauthenticatedRevoke.status, 401);

    const unauthenticatedPurge = await httpRequest(port, "/consent/purge-raw", {
      method: "POST",
      host: goodHost,
      body: "{}"
    });
    assert.equal(unauthenticatedPurge.status, 401);

    const unauthenticatedProcesses = await httpRequest(port, "/host/processes", {
      host: goodHost
    });
    assert.equal(unauthenticatedProcesses.status, 401);

    const acceptedProcesses = await httpRequest(port, "/host/processes", {
      host: goodHost,
      token
    });
    assert.equal(acceptedProcesses.status, 200);
    const processSnapshot = JSON.parse(acceptedProcesses.body);
    assert.ok(["ok", "degraded", "unavailable"].includes(processSnapshot.status));
    assert.ok(Array.isArray(processSnapshot.processes));
    assert.equal(JSON.stringify(processSnapshot).includes("sk-"), false);

    const unauthenticatedCodexApp = await httpRequest(port, "/adapters/codex-app/status", {
      host: goodHost
    });
    assert.equal(unauthenticatedCodexApp.status, 401);

    const acceptedCodexApp = await httpRequest(port, "/adapters/codex-app/status", {
      host: goodHost,
      token
    });
    assert.equal(acceptedCodexApp.status, 200);
    const codexAppStatus = JSON.parse(acceptedCodexApp.body);
    assert.equal(codexAppStatus.provider, "codex_app");
    assert.ok(["running", "frontmost", "recently_active", "unavailable"].includes(codexAppStatus.status));
    assert.equal(JSON.stringify(codexAppStatus).includes("sk-"), false);

    const unauthenticatedWatchers = await httpRequest(port, "/watchers/status", {
      host: goodHost
    });
    assert.equal(unauthenticatedWatchers.status, 401);

    const acceptedRevoke = await httpRequest(port, "/consent/revoke", {
      method: "POST",
      host: goodHost,
      token,
      body: JSON.stringify({ repoIdentityHash: "a".repeat(64), repositoryDisplayLabel: "repo" })
    });
    assert.equal(acceptedRevoke.status, 202);
  });

  await withTempDir(async (dir) => {
    const port = await findOpenPort();
    const badHost = await companionStartupFailure({
      LEARNLOOP_LOCAL_AI_PORT: String(port),
      LEARNLOOP_LOCAL_AI_HOST: "0.0.0.0",
      LEARNLOOP_LOCAL_AI_CONFIG_DIR: path.join(dir, ".learnloop")
    });
    assert.notEqual(badHost.code, 0);
    assert.match(badHost.stderr, /LEARNLOOP_LOCAL_AI_HOST must be/);

    const unsafeOrigin = await companionStartupFailure({
      LEARNLOOP_LOCAL_AI_PORT: String(await findOpenPort()),
      LEARNLOOP_LOCAL_AI_ALLOWED_ORIGINS: "https://example.com",
      LEARNLOOP_LOCAL_AI_CONFIG_DIR: path.join(dir, ".learnloop")
    });
    assert.notEqual(unsafeOrigin.code, 0);
    assert.match(unsafeOrigin.stderr, /ALLOWED_ORIGINS may only contain loopback/);

    const outsideConfigToken = await companionStartupFailure({
      LEARNLOOP_LOCAL_AI_PORT: String(await findOpenPort()),
      LEARNLOOP_LOCAL_AI_CONFIG_DIR: path.join(dir, ".learnloop"),
      LEARNLOOP_LOCAL_AI_TOKEN_FILE: path.join(dir, "token")
    });
    assert.notEqual(outsideConfigToken.code, 0);
    assert.match(outsideConfigToken.stderr, /inside the LearnLoop config directory/);

    const unmanagedDir = path.join(dir, "existing-config");
    await mkdirp(unmanagedDir);
    const unmanagedTokenDirectory = await companionStartupFailure({
      LEARNLOOP_LOCAL_AI_PORT: String(await findOpenPort()),
      LEARNLOOP_LOCAL_AI_CONFIG_DIR: unmanagedDir,
      LEARNLOOP_LOCAL_AI_TOKEN_FILE: path.join(unmanagedDir, "token")
    });
    assert.notEqual(unmanagedTokenDirectory.code, 0);
    assert.match(unmanagedTokenDirectory.stderr, /parent must be a LearnLoop-managed directory/);

    const repoRoot = path.join(dir, "repo");
    await mkdirp(path.join(repoRoot, ".git"));
    const repoToken = await companionStartupFailure({
      LEARNLOOP_LOCAL_AI_PORT: String(await findOpenPort()),
      LEARNLOOP_LOCAL_AI_CONFIG_DIR: path.join(repoRoot, ".learnloop")
    });
    assert.notEqual(repoToken.code, 0);
    assert.match(repoToken.stderr, /outside application and repository directories/);
  });
});

test("local companion watcher registry updates approved repositories and resets on restart", async () => {
  await withTempDir(async (dir) => {
    const repoRoot = path.join(dir, "repo");
    await mkdirp(repoRoot);

    await withCompanion(async ({ port, token }) => {
      const goodHost = `127.0.0.1:${port}`;
      const approved = await httpRequest(port, "/watchers/repositories", {
        method: "POST",
        host: goodHost,
        token,
        body: JSON.stringify({
          repoIdentityHash: "repo-watch-1",
          repositoryDisplayLabel: "fixture/repo",
          repoRoot,
          status: "approved"
        })
      });
      assert.equal(approved.status, 200);
      const approvedBody = JSON.parse(approved.body);
      assert.equal(approvedBody.watcher.state, "active");
      assert.equal(approvedBody.collectionEnabled, true);
      assert.equal(approvedBody.uploadQueue.queued, 0);
      assert.equal(JSON.stringify(approvedBody).includes(repoRoot), false);

      const disabled = await httpRequest(port, "/watchers/settings", {
        method: "POST",
        host: goodHost,
        token,
        body: JSON.stringify({ enabled: false })
      });
      assert.equal(disabled.status, 200);
      const disabledBody = JSON.parse(disabled.body);
      assert.equal(disabledBody.collectionEnabled, false);
      assert.equal(disabledBody.watchers[0].state, "stopped");
      assert.equal(disabledBody.watchers[0].reason, "collection_disabled");

      const enabled = await httpRequest(port, "/watchers/settings", {
        method: "POST",
        host: goodHost,
        token,
        body: JSON.stringify({ enabled: true })
      });
      assert.equal(enabled.status, 200);
      assert.equal(JSON.parse(enabled.body).collectionEnabled, true);

      const revoked = await httpRequest(port, "/watchers/repositories", {
        method: "POST",
        host: goodHost,
        token,
        body: JSON.stringify({
          repoIdentityHash: "repo-watch-1",
          repositoryDisplayLabel: "fixture/repo",
          status: "revoked"
        })
      });
      assert.equal(revoked.status, 200);
      assert.equal(JSON.parse(revoked.body).watcher.state, "stopped");

      const status = await httpRequest(port, "/watchers/status", {
        host: goodHost,
        token
      });
      assert.equal(status.status, 200);
      const statusBody = JSON.parse(status.body);
      assert.equal(statusBody.collectionEnabled, true);
      assert.equal(statusBody.uploadQueue.queued, 0);
      assert.equal(statusBody.watcherCounts.stopped, 1);
      assert.equal(statusBody.watcherCounts.active, 0);
    });

    await withCompanion(async ({ port, token }) => {
      const restarted = await httpRequest(port, "/watchers/status", {
        host: `127.0.0.1:${port}`,
        token
      });
      assert.equal(restarted.status, 200);
      const restartedBody = JSON.parse(restarted.body);
      assert.deepEqual(restartedBody.watchers, []);
      assert.equal(restartedBody.watcherCounts.active, 0);
    });
  });
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

async function writeFakeClaude(filePath) {
  await writeExecutable(
    filePath,
    `#!/usr/bin/env sh
case "$1" in
  interactive)
    exit 0
    ;;
  --print)
    case "$2" in
      safe)
        printf 'summary token=sk-claudeprintsecret123456\\n'
        printf 'diagnostic token=sk-claudestderrsecret123456\\n' >&2
        exit 0
        ;;
      large)
        i=0
        while [ "$i" -lt 800 ]; do
          printf 'line-%03d generated code context\\n' "$i"
          i=$((i + 1))
        done
        exit 0
        ;;
    esac
    ;;
  chat)
    printf 'chat output token=sk-claudechatsecret123456\\n'
    exit 0
    ;;
esac
exit 0
`
  );
}

async function writeFakeGemini(filePath) {
  await writeExecutable(
    filePath,
    `#!/usr/bin/env sh
case "$1" in
  success)
    printf 'gemini token=sk-geministdoutsecret123456\\n'
    printf 'gemini stderr token=sk-geministderrsecret123456\\n' >&2
    exit 0
    ;;
  large)
    i=0
    while [ "$i" -lt 800 ]; do
      printf 'gemini-line-%03d generated output\\n' "$i"
      i=$((i + 1))
    done
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

async function withCompanion(fn) {
  await withTempDir(async (dir) => {
    const port = await findOpenPort();
    const configDir = path.join(dir, ".learnloop");
    const tokenFile = path.join(configDir, "local-api-token");
    const child = spawn(process.execPath, ["scripts/local-ai-companion.mjs"], {
      env: {
        ...process.env,
        LEARNLOOP_LOCAL_AI_PORT: String(port),
        LEARNLOOP_LOCAL_AI_CONFIG_DIR: configDir,
        LEARNLOOP_CODEX_STATUS_COMMAND: "/usr/bin/false",
        LEARNLOOP_CODEX_LOGIN_COMMAND: "/usr/bin/true"
      },
      stdio: ["ignore", "ignore", "pipe"]
    });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });

    try {
      const baseUrl = `http://127.0.0.1:${port}`;
      await waitForHealth(`${baseUrl}/health`);
      const token = (await readFile(tokenFile, "utf8")).trim();
      await fn({ baseUrl, port, token, stderr: () => stderr });
    } finally {
      child.kill("SIGTERM");
      await new Promise((resolve) => child.once("close", resolve));
    }
  });
}

function companionStartupFailure(env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["scripts/local-ai-companion.mjs"], {
      env: { ...process.env, ...env },
      stdio: ["ignore", "ignore", "pipe"]
    });
    let stderr = "";
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString("utf8");
    });
    child.on("error", reject);
    child.once("close", (code) => resolve({ code, stderr }));
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

function httpRequest(port, requestPath, options = {}) {
  const body = options.body ?? "";
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: "127.0.0.1",
        port,
        path: requestPath,
        method: options.method ?? "GET",
        headers: {
          Host: options.host ?? `127.0.0.1:${port}`,
          ...(body ? { "content-type": "application/json", "content-length": Buffer.byteLength(body) } : {}),
          ...(options.token ? { "x-learnloop-local-token": options.token } : {}),
          ...(options.headers ?? {})
        }
      },
      (res) => {
        let responseBody = "";
        res.on("data", (chunk) => {
          responseBody += chunk.toString("utf8");
        });
        res.on("end", () => resolve({ status: res.statusCode, body: responseBody }));
      }
    );
    req.on("error", reject);
    req.end(body);
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
