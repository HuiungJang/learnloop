import { spawn } from "node:child_process";
import { closeSync, openSync } from "node:fs";
import path from "node:path";

const [, , companionScript, logFile] = process.argv;

if (!companionScript || !logFile) {
  console.error("Usage: local-ai-companion-launcher.mjs <companion-script> <log-file>");
  process.exit(2);
}

const logFd = openSync(logFile, "w", 0o600);
try {
  const child = spawn(process.execPath, [path.resolve(companionScript)], {
    cwd: process.cwd(),
    detached: true,
    env: process.env,
    stdio: ["ignore", logFd, logFd]
  });

  child.unref();
  process.stdout.write(`${child.pid}\n`);
} finally {
  closeSync(logFd);
}
