#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import {
  mkdirSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { join } from "node:path";

const workspace = process.env.LEARNLOOP_WORKSPACE || "/workspace";
const metadataDir = join(workspace, ".learnloop");
const outDir = join(metadataDir, "out");
const tsconfigPath = join(metadataDir, "tsconfig.runner.json");
const tscPath = "/opt/learnloop-runner/node_modules/typescript/bin/tsc";

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

writeFileSync(
  tsconfigPath,
  `${JSON.stringify(
    {
      compilerOptions: {
        target: "ES2022",
        module: "CommonJS",
        moduleResolution: "node10",
        rootDir: "..",
        outDir: "out",
        strict: true,
        esModuleInterop: true,
        skipLibCheck: true,
        noEmitOnError: true,
        forceConsistentCasingInFileNames: true,
        types: ["node"],
        typeRoots: ["/opt/learnloop-runner/node_modules/@types"],
      },
      include: ["../**/*.ts"],
      exclude: ["out", "../node_modules", "../.learnloop/out"],
    },
    null,
    2,
  )}\n`,
);

const compile = spawnSync(process.execPath, [tscPath, "--project", tsconfigPath, "--pretty", "false"], {
  cwd: workspace,
  stdio: "inherit",
  env: runnerEnv(),
});

if (compile.status !== 0) {
  process.exit(compile.status ?? 1);
}

const testFiles = listFiles(outDir)
  .filter((path) => path.endsWith(".test.js") || path.endsWith(".spec.js"))
  .sort();

if (testFiles.length === 0) {
  console.error("No compiled TypeScript test files found. Add *.test.ts or *.spec.ts.");
  process.exit(1);
}

const testRun = spawnSync(process.execPath, ["--test", ...testFiles], {
  cwd: workspace,
  stdio: "inherit",
  env: runnerEnv(),
});

process.exit(testRun.status ?? 1);

function listFiles(root) {
  const files = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      files.push(...listFiles(path));
    } else if (entry.isFile()) {
      files.push(path);
    }
  }
  return files;
}

function runnerEnv() {
  return {
    ...process.env,
    npm_config_audit: "false",
    npm_config_fund: "false",
    npm_config_update_notifier: "false",
  };
}
