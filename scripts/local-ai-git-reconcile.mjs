import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { lstat, readFile, realpath } from "node:fs/promises";
import path from "node:path";

const DEFAULT_TIMEOUT_MS = 2000;
const DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;
const DEFAULT_CACHE_TTL_MS = 5000;
const DEFAULT_MAX_AFTER_SNAPSHOT_BYTES = 200 * 1024;

export class GitReconciliationCache {
  constructor(options = {}) {
    this.ttlMs = positiveInteger(options.ttlMs, DEFAULT_CACHE_TTL_MS);
    this.now = options.now ?? (() => Date.now());
    this.entries = new Map();
  }

  get(repoRoot) {
    const entry = this.entries.get(repoRoot);
    if (!entry || entry.expiresAt <= this.now()) {
      this.entries.delete(repoRoot);
      return null;
    }
    return entry.value;
  }

  set(repoRoot, value) {
    this.entries.set(repoRoot, { value, expiresAt: this.now() + this.ttlMs });
  }
}

export async function reconcileGitRepository(input, options = {}) {
  const repoRoot = safeAbsolutePath(input?.repoRoot);
  if (!repoRoot) {
    return { status: "unavailable", reason: "missing_repo_root", changedFiles: [], diffCandidates: [] };
  }

  const runner = options.commandRunner ?? runGitCommand;
  const timeoutMs = positiveInteger(options.timeoutMs, DEFAULT_TIMEOUT_MS);
  const maxOutputBytes = positiveInteger(options.maxOutputBytes, DEFAULT_MAX_OUTPUT_BYTES);
  const maxAfterSnapshotBytes = positiveInteger(options.maxAfterSnapshotBytes, DEFAULT_MAX_AFTER_SNAPSHOT_BYTES);
  const cache = options.cache ?? new GitReconciliationCache();
  const commandCounts = { total: 0, status: 0, diff: 0 };
  const run = async (name, args, commandOptions = {}) => {
    commandCounts.total += 1;
    if (name === "status" || name === "diff") commandCounts[name] += 1;
    options.onGitCommand?.({ name, args: [...args] });
    return await runner(args, { cwd: repoRoot, timeoutMs, maxOutputBytes, ...commandOptions });
  };

  const metadata = await readRepositoryMetadata(repoRoot, cache, run);
  const statusResult = await run("status", ["status", "--porcelain=v1", "-z"]);
  if (!statusResult.ok) {
    return {
      status: "degraded",
      reason: statusResult.reason,
      branchName: metadata.branchName,
      remoteUrl: metadata.remoteUrl,
      changedFiles: [],
      diffCandidates: [],
      commandCounts
    };
  }

  const changedFiles = parsePorcelainStatus(statusResult.stdout);
  const requestedPaths = new Set((input?.changedPaths ?? []).map(normalizeRepoRelativePath).filter(Boolean));
  const rawDiffCandidates = requestedPaths.size > 0
    ? changedFiles.filter((file) => requestedPaths.has(file.repoRelativePath))
    : changedFiles;
  const { safeFiles: diffCandidates, ignoredFiles } = await filterSafeDiffCandidates(metadata.repoRoot, rawDiffCandidates);
  if (input?.initialScan === true) {
    options.beforeSnapshotCache?.markPreExistingDirty(repoIdentityHash(input, metadata), diffCandidates);
    return {
      status: "ok",
      reason: null,
      repoRoot: metadata.repoRoot,
      branchName: metadata.branchName,
      remoteUrl: metadata.remoteUrl,
      changedFiles,
      diffCandidates: [],
      ignoredFiles,
      beforeSnapshots: [],
      diff: "",
      diffTruncated: false,
      commandCounts
    };
  }
  const beforeSnapshots = options.beforeSnapshotCache
    ? await options.beforeSnapshotCache.captureSnapshots({
        repoIdentityHash: repoIdentityHash(input, metadata),
        files: diffCandidates,
        readSnapshot: (repoRelativePath) => readHeadSnapshot(repoRelativePath, run, options.beforeSnapshotCache.maxSnapshotBytes)
      })
    : [];
  const afterSnapshots = await captureAfterSnapshots(metadata.repoRoot, diffCandidates, maxAfterSnapshotBytes);
  const diffCandidatePaths = new Set(
    afterSnapshots
      .filter((snapshot) => snapshot.status === "captured" || snapshot.reason === "deleted_after_snapshot")
      .map((snapshot) => snapshot.repoRelativePath)
  );
  const finalDiffCandidates = diffCandidates.filter((file) => diffCandidatePaths.has(file.repoRelativePath));
  const diffArgs = ["diff", "--no-ext-diff", "--", ...finalDiffCandidates.map((file) => file.repoRelativePath)];
  const diffResult = finalDiffCandidates.length > 0
    ? await run("diff", diffArgs, { allowTruncated: true })
    : { ok: true, stdout: "", outputTruncated: false };
  const diffSizeBytes = Buffer.byteLength(diffResult.stdout ?? "", "utf8");

  return {
    status: diffResult.ok ? "ok" : "degraded",
    reason: diffResult.ok ? null : diffResult.reason,
    repoRoot: metadata.repoRoot,
    branchName: metadata.branchName,
    remoteUrl: metadata.remoteUrl,
    changedFiles,
    diffCandidates: finalDiffCandidates,
    ignoredFiles,
    beforeSnapshots,
    afterSnapshots,
    metadataOnlyFiles: [
      ...ignoredFiles,
      ...afterSnapshots
        .filter((snapshot) => snapshot.status === "metadata_only")
        .map((snapshot) => ({
          repoRelativePath: snapshot.repoRelativePath,
          reason: snapshot.reason,
          sizeBytes: snapshot.sizeBytes,
          contentHash: snapshot.contentHash
        }))
    ],
    diff: diffResult.stdout,
    diffTruncated: diffResult.outputTruncated === true,
    diffMetadata: {
      sizeBytes: diffSizeBytes,
      limitBytes: maxOutputBytes,
      contentTruncated: diffResult.outputTruncated === true,
      limitReason: diffResult.outputTruncated === true ? "diff_output_limit" : null
    },
    commandCounts
  };
}

async function readHeadSnapshot(repoRelativePath, run, maxSnapshotBytes) {
  const result = await run("show", ["show", `HEAD:${repoRelativePath}`], {
    allowFailure: true,
    maxOutputBytes: maxSnapshotBytes + 1
  });
  return result.ok && result.stdout.length > 0
    ? { ok: true, content: result.stdout }
    : { ok: false };
}

function repoIdentityHash(input, metadata) {
  return input?.repoIdentityHash || metadata.repoRoot;
}

async function captureAfterSnapshots(repoRoot, files, maxAfterSnapshotBytes) {
  const snapshots = [];
  for (const file of files) {
    const absolutePath = path.resolve(repoRoot, file.repoRelativePath);
    const stats = await lstat(absolutePath).catch((error) => {
      if (error?.code === "ENOENT") return null;
      throw error;
    });
    if (stats === null) {
      snapshots.push({
        repoRelativePath: file.repoRelativePath,
        status: "unavailable",
        reason: "deleted_after_snapshot",
        contentText: null,
        sizeBytes: null,
        contentHash: null
      });
      continue;
    }
    if (!stats.isFile()) {
      snapshots.push({
        repoRelativePath: file.repoRelativePath,
        status: "unavailable",
        reason: "after_snapshot_unavailable",
        contentText: null,
        sizeBytes: stats.size,
        contentHash: null
      });
      continue;
    }
    if (stats.size > maxAfterSnapshotBytes) {
      snapshots.push({
        repoRelativePath: file.repoRelativePath,
        status: "metadata_only",
        reason: "oversized_after_snapshot",
        contentText: null,
        sizeBytes: stats.size,
        contentHash: null
      });
      continue;
    }
    const content = await readFile(absolutePath, "utf8").catch(() => null);
    if (content === null) {
      snapshots.push({
        repoRelativePath: file.repoRelativePath,
        status: "metadata_only",
        reason: "after_snapshot_unreadable",
        contentText: null,
        sizeBytes: stats.size,
        contentHash: null
      });
      continue;
    }
    snapshots.push({
      repoRelativePath: file.repoRelativePath,
      status: "captured",
      reason: null,
      contentText: content,
      sizeBytes: Buffer.byteLength(content, "utf8"),
      contentHash: createHash("sha256").update(content).digest("hex")
    });
  }
  return snapshots;
}

async function filterSafeDiffCandidates(repoRoot, files) {
  const safeFiles = [];
  const ignoredFiles = [];
  for (const file of files) {
    const reason = await unsafeWatchPathReason(repoRoot, file.repoRelativePath);
    if (reason) {
      ignoredFiles.push({ repoRelativePath: file.repoRelativePath, reason });
    } else {
      safeFiles.push(file);
    }
  }
  return { safeFiles, ignoredFiles };
}

async function unsafeWatchPathReason(repoRoot, repoRelativePath) {
  const normalized = normalizeRepoRelativePath(repoRelativePath);
  if (!normalized) return "unsafe_repo_relative_path";
  const ignoredReason = ignoredPathReason(normalized);
  if (ignoredReason) return ignoredReason;

  const absolutePath = path.resolve(repoRoot, normalized);
  if (!isPathInside(absolutePath, repoRoot)) return "path_traversal";

  const stats = await lstat(absolutePath).catch((error) => {
    if (error?.code === "ENOENT") return null;
    throw error;
  });
  if (stats?.isSymbolicLink()) {
    const target = await realpath(absolutePath).catch(() => null);
    if (!target || !isPathInside(target, repoRoot)) return "symlink_escape";
  }
  return null;
}

function ignoredPathReason(repoRelativePath) {
  const segments = repoRelativePath.split("/").filter(Boolean);
  const lowerSegments = segments.map((segment) => segment.toLowerCase());
  const name = lowerSegments.at(-1) ?? "";
  if (name.startsWith(".env")) return "sensitive_file";
  if (lowerSegments.some((segment) => segment.startsWith("."))) return "hidden_path";
  if (lowerSegments.some((segment) => IGNORED_DIRECTORIES.has(segment))) return "ignored_directory";
  if (SENSITIVE_FILE_PATTERN.test(name)) return "sensitive_file";
  if (BINARY_OR_ARCHIVE_PATTERN.test(name)) return "binary_or_archive";
  return null;
}

async function readRepositoryMetadata(repoRoot, cache, run) {
  const cached = cache.get(repoRoot);
  if (cached) return cached;

  const rootResult = await run("rev-parse", ["rev-parse", "--show-toplevel"]);
  const resolvedRoot = rootResult.ok ? safeAbsolutePath(rootResult.stdout.trim()) ?? repoRoot : repoRoot;
  const branchResult = await run("branch", ["branch", "--show-current"]);
  const remoteResult = await run("remote", ["remote", "get-url", "origin"], { allowFailure: true });
  const metadata = {
    repoRoot: resolvedRoot,
    branchName: safeMetadataToken(branchResult.ok ? branchResult.stdout.trim() : "") ?? null,
    remoteUrl: sanitizeRemoteUrl(remoteResult.ok ? remoteResult.stdout.trim() : "")
  };
  cache.set(repoRoot, metadata);
  return metadata;
}

export function parsePorcelainStatus(output) {
  const entries = String(output).split("\0");
  const files = [];
  for (let index = 0; index < entries.length; index += 1) {
    const entry = entries[index];
    if (entry.length < 4) continue;
    const statusCode = entry.slice(0, 2);
    const repoRelativePath = normalizeRepoRelativePath(entry.slice(3));
    if (!repoRelativePath) continue;
    files.push({ repoRelativePath, statusCode });
    if (statusCode.includes("R") || statusCode.includes("C")) {
      index += 1;
    }
  }
  return files;
}

export function sanitizeRemoteUrl(value) {
  const raw = String(value).trim();
  if (!raw || raw.startsWith("/") || raw.startsWith("file:") || raw.includes("\0")) return null;
  if (/^[A-Za-z]:[\\/]/.test(raw) || raw.startsWith("\\\\")) return null;
  try {
    const url = new URL(raw);
    if (url.protocol !== "http:" && url.protocol !== "https:" && url.protocol !== "ssh:") return null;
    url.username = "";
    url.password = "";
    url.search = "";
    url.hash = "";
    return url.toString().slice(0, 240);
  } catch {
    if (/^[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[A-Za-z0-9_.\/-]+$/.test(raw)) {
      return raw.slice(0, 240);
    }
    return null;
  }
}

export function runGitCommand(args, options) {
  return new Promise((resolve) => {
    const child = spawn("git", args, {
      cwd: options.cwd,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let stdout = Buffer.alloc(0);
    let stderr = Buffer.alloc(0);
    let outputTruncated = false;
    let settled = false;
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      finish({ ok: false, reason: "git_timeout", stdout: stdout.toString("utf8"), stderr: stderr.toString("utf8"), outputTruncated });
    }, options.timeoutMs);

    child.stdout.on("data", (chunk) => {
      const next = appendBounded(stdout, chunk, options.maxOutputBytes);
      stdout = next.buffer;
      outputTruncated = outputTruncated || next.truncated;
      if (outputTruncated) {
        child.kill("SIGKILL");
        if (options.allowTruncated === true) {
          finish({ ok: true, reason: "output_truncated", stdout: stdout.toString("utf8"), stderr: stderr.toString("utf8"), outputTruncated });
        }
      }
    });
    child.stderr.on("data", (chunk) => {
      const next = appendBounded(stderr, chunk, 8192);
      stderr = next.buffer;
      outputTruncated = outputTruncated || next.truncated;
    });
    child.on("error", () => {
      finish({ ok: false, reason: "git_spawn_failed", stdout: stdout.toString("utf8"), stderr: stderr.toString("utf8"), outputTruncated });
    });
    child.on("close", (code) => {
      finish({
        ok: code === 0 || options.allowFailure === true,
        reason: code === 0 || options.allowFailure === true ? null : "git_command_failed",
        stdout: stdout.toString("utf8"),
        stderr: stderr.toString("utf8"),
        outputTruncated
      });
    });

    function finish(result) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    }
  });
}

function appendBounded(current, chunk, maxBytes) {
  const combined = Buffer.concat([current, chunk]);
  if (combined.length <= maxBytes) return { buffer: combined, truncated: false };
  return { buffer: combined.subarray(0, maxBytes), truncated: true };
}

function normalizeRepoRelativePath(value) {
  if (typeof value !== "string" || value.length === 0 || value.includes("\0")) return null;
  const normalized = path.posix.normalize(value.replace(/\\/g, "/"));
  if (normalized === "." || normalized === ".." || normalized.startsWith("../") || path.posix.isAbsolute(normalized)) {
    return null;
  }
  return normalized;
}

function safeAbsolutePath(value) {
  if (typeof value !== "string" || value.trim().length === 0) return null;
  const trimmed = value.trim();
  if (!path.isAbsolute(trimmed)) return null;
  return path.resolve(trimmed);
}

function safeMetadataToken(value) {
  return value && /^[A-Za-z0-9][A-Za-z0-9_.\/#@+-]{0,119}$/.test(value) ? value : null;
}

function isPathInside(candidate, parent) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === "" || (relative.length > 0 && !relative.startsWith("..") && !path.isAbsolute(relative));
}

function positiveInteger(value, fallback) {
  return Number.isFinite(value) ? Math.max(1, Math.round(value)) : fallback;
}

const IGNORED_DIRECTORIES = new Set(["node_modules", "vendor", "dist", "build", "target", ".gradle", ".git"]);
const SENSITIVE_FILE_PATTERN = /\.(?:pem|key|crt|cer|p12|pfx|jks|keystore)$/;
const BINARY_OR_ARCHIVE_PATTERN = /\.(?:png|jpe?g|gif|webp|ico|pdf|zip|tar|gz|tgz|jar|class|so|dylib|dll|exe|bin|mp4|mov|mp3|wav)$/;
