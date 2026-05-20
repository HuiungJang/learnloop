import assert from "node:assert/strict";
import test from "node:test";
import {
  detectCodexAppPresence,
  readCodexAppPresence
} from "../scripts/local-ai-codex-app-adapter.mjs";

test("Codex App adapter reports running from a Codex app process", () => {
  const status = detectCodexAppPresence(snapshotWithProcess({
    pid: 501,
    processName: "Codex",
    executablePath: "/Applications/Codex.app/Contents/MacOS/Codex"
  }));

  assert.equal(status.provider, "codex_app");
  assert.equal(status.status, "running");
  assert.equal(status.reason, null);
  assert.equal(status.matchedProcesses.length, 1);
  assert.equal(status.matchedProcesses[0].processName, "Codex");
});

test("Codex App adapter reports frontmost before recently active", () => {
  const status = detectCodexAppPresence(snapshotWithProcess({
    pid: 502,
    processName: "Codex",
    executablePath: "/Applications/Codex.app/Contents/MacOS/Codex",
    frontmost: true,
    appActive: true
  }));

  assert.equal(status.status, "frontmost");
});

test("Codex App adapter reports recently active from activity hints", () => {
  const status = detectCodexAppPresence(
    snapshotWithProcess({
      pid: 503,
      processName: "Codex Helper",
      executablePath: "/Applications/Codex.app/Contents/Frameworks/Codex Helper.app/Contents/MacOS/Codex Helper"
    }),
    { recentProcessIds: [503] }
  );

  assert.equal(status.status, "recently_active");
});

test("Codex App adapter degrades to unavailable when process snapshots are unavailable", () => {
  const status = detectCodexAppPresence({
    status: "unavailable",
    capturedAt: "2026-05-20T01:00:00.000Z",
    reason: "unsupported_platform",
    processes: []
  });

  assert.equal(status.status, "unavailable");
  assert.equal(status.snapshotStatus, "unavailable");
  assert.equal(status.reason, "unsupported_platform");
});

test("Codex App adapter ignores Codex CLI process-name mismatches", () => {
  const status = detectCodexAppPresence(snapshotWithProcess({
    pid: 504,
    processName: "codex",
    executablePath: "/usr/local/bin/codex"
  }));

  assert.equal(status.status, "unavailable");
  assert.equal(status.reason, "process_not_found");
  assert.deepEqual(status.matchedProcesses, []);
});

test("Codex App adapter can read from an injected snapshot source", async () => {
  const status = await readCodexAppPresence({
    snapshot: snapshotWithProcess({
      pid: 505,
      processName: "Other",
      executablePath: "/Applications/Codex.app/Contents/MacOS/Codex"
    })
  });

  assert.equal(status.status, "running");
});

function snapshotWithProcess(processInfo) {
  return {
    status: "ok",
    capturedAt: "2026-05-20T01:00:00.000Z",
    reason: null,
    processes: [
      {
        parentPid: 1,
        frontmost: null,
        appActive: null,
        ...processInfo
      }
    ]
  };
}
