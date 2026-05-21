import assert from "node:assert/strict";
import test from "node:test";
import {
  parseMacosPsOutput,
  readHostProcessSnapshot,
  readMacosProcessSnapshot
} from "../scripts/local-ai-process-snapshot.mjs";

test("host process snapshot normalizes fake snapshots without raw command data", async () => {
  const snapshot = await readHostProcessSnapshot({
    platform: "darwin",
    homeDir: "/Users/example",
    snapshotReader: async () => ({
      status: "ok",
      processes: [
        {
          pid: 123,
          parentPid: 1,
          processName: "Codex",
          executablePath: "/Users/example/Applications/Codex.app/Contents/MacOS/Codex",
          frontmost: true,
          appActive: true,
          commandLine: "codex --api-key sk-testtesttesttesttesttesttesttest",
          env: { SECRET_TOKEN: "not-collected" }
        }
      ]
    })
  });

  assert.equal(snapshot.status, "ok");
  assert.equal(snapshot.platform, "darwin");
  assert.deepEqual(snapshot.processes, [
    {
      pid: 123,
      parentPid: 1,
      processName: "Codex",
      executablePath: "~/Applications/Codex.app/Contents/MacOS/Codex",
      frontmost: true,
      appActive: true
    }
  ]);
  assert.equal(JSON.stringify(snapshot).includes("sk-"), false);
  assert.equal(JSON.stringify(snapshot).includes("SECRET_TOKEN"), false);
});

test("host process snapshot leaves relative executable names unresolved", async () => {
  const snapshot = await readHostProcessSnapshot({
    platform: "darwin",
    homeDir: "/Users/example",
    snapshotReader: async () => ({
      processes: [
        {
          pid: 234,
          parentPid: 1,
          processName: "codex",
          executablePath: "codex"
        }
      ]
    })
  });

  assert.equal(snapshot.processes[0].executablePath, "codex");
});

test("macOS process snapshot parses bounded ps metadata and redacts home paths", async () => {
  const snapshot = await readMacosProcessSnapshot({
    homeDir: "/Users/example",
    commandRunner: async (command, args, options) => {
      assert.equal(command, "ps");
      assert.deepEqual(args, ["-axo", "pid=,ppid=,comm="]);
      assert.equal(options.timeoutMs, 1000);
      return {
        timedOut: false,
        stdout: [
          "  101     1 /Applications/Codex.app/Contents/MacOS/Codex",
          "  202   101 /Users/example/bin/gemini",
          "not a ps row"
        ].join("\n")
      };
    }
  });

  assert.equal(snapshot.status, "ok");
  assert.deepEqual(snapshot.processes, [
    {
      pid: 101,
      parentPid: 1,
      processName: "Codex",
      executablePath: "/Applications/Codex.app/Contents/MacOS/Codex",
      frontmost: null,
      appActive: null
    },
    {
      pid: 202,
      parentPid: 101,
      processName: "gemini",
      executablePath: "~/bin/gemini",
      frontmost: null,
      appActive: null
    }
  ]);
});

test("macOS process snapshot reports timeout as degraded", async () => {
  const snapshot = await readMacosProcessSnapshot({
    commandRunner: async () => {
      const error = new Error("timeout");
      error.timedOut = true;
      throw error;
    }
  });

  assert.equal(snapshot.status, "degraded");
  assert.equal(snapshot.reason, "process_snapshot_timeout");
  assert.deepEqual(snapshot.processes, []);
});

test("process parser respects max process limit", () => {
  const processes = parseMacosPsOutput("1 0 /bin/one\n2 0 /bin/two\n", { maxProcesses: 1 });

  assert.equal(processes.length, 1);
  assert.equal(processes[0].processName, "one");
});
