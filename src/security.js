import crypto from "node:crypto";

const CREDENTIAL_ALGORITHM = "aes-256-gcm";
const SECRET_PATTERNS = [
  { type: "openai_key", pattern: /\bsk-[A-Za-z0-9_-]{20,}\b/g },
  { type: "github_token", pattern: /\bghp_[A-Za-z0-9_]{20,}\b/g },
  { type: "aws_access_key", pattern: /\bAKIA[0-9A-Z]{16}\b/g },
  { type: "private_key", pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----/g },
  { type: "assigned_secret", pattern: /\b(?:api[_-]?key|password|secret|token)\s*[:=]\s*["']?[^"'\s]{8,}/gi }
];

export function id(prefix) {
  return `${prefix}_${crypto.randomUUID()}`;
}

export function sha256(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

export function now() {
  return new Date().toISOString();
}

export function scanSecrets(content) {
  const findings = [];
  for (const { type, pattern } of SECRET_PATTERNS) {
    pattern.lastIndex = 0;
    for (const match of content.matchAll(pattern)) {
      findings.push({
        type,
        index: match.index ?? 0,
        fingerprint: sha256(match[0]).slice(0, 16)
      });
    }
  }
  return findings;
}

export function sealCredential(secret) {
  const digest = sha256(secret);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv(CREDENTIAL_ALGORITHM, credentialKey(), iv);
  const credentialCiphertext = Buffer.concat([cipher.update(String(secret), "utf8"), cipher.final()]);
  return {
    credentialRef: `vault://${digest.slice(0, 24)}`,
    credentialFingerprint: digest.slice(0, 16),
    secretPreview: secret.length >= 4 ? `***${secret.slice(-4)}` : "***",
    credentialAlgorithm: CREDENTIAL_ALGORITHM,
    credentialIv: iv.toString("base64url"),
    credentialTag: cipher.getAuthTag().toString("base64url"),
    credentialCiphertext: credentialCiphertext.toString("base64url")
  };
}

function credentialKey() {
  const configuredKey = process.env.APP_CREDENTIAL_ENCRYPTION_KEY;
  if (!configuredKey && process.env.NODE_ENV === "production") {
    throw new Error("APP_CREDENTIAL_ENCRYPTION_KEY is required in production");
  }
  return crypto.createHash("sha256").update(configuredKey ?? "learnloop-local-development-credential-key").digest();
}

export function openCredential(sealed) {
  if (!sealed?.credentialCiphertext || !sealed?.credentialIv || !sealed?.credentialTag) {
    throw new Error("Credential material is unavailable");
  }
  if (sealed.credentialAlgorithm !== CREDENTIAL_ALGORITHM) {
    throw new Error("Unsupported credential algorithm");
  }
  const decipher = crypto.createDecipheriv(
    CREDENTIAL_ALGORITHM,
    credentialKey(),
    Buffer.from(sealed.credentialIv, "base64url")
  );
  decipher.setAuthTag(Buffer.from(sealed.credentialTag, "base64url"));
  return Buffer.concat([
    decipher.update(Buffer.from(sealed.credentialCiphertext, "base64url")),
    decipher.final()
  ]).toString("utf8");
}

export function hashPassword(password, salt = crypto.randomBytes(16).toString("hex")) {
  const hash = crypto.pbkdf2Sync(String(password), salt, 120_000, 32, "sha256").toString("hex");
  return { salt, hash };
}

export function verifyPassword(password, salt, expectedHash) {
  const actual = crypto.pbkdf2Sync(String(password), salt, 120_000, 32, "sha256");
  const expected = Buffer.from(expectedHash, "hex");
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

export function createSessionToken() {
  return crypto.randomBytes(32).toString("base64url");
}

export function redactForApi(value) {
  if (Array.isArray(value)) return value.map(redactForApi);
  if (!value || typeof value !== "object") return value;
  const output = {};
  for (const [key, item] of Object.entries(value)) {
    if (/credential|secret|token|password|apiKey|keyMaterial/i.test(key)) {
      output[key] = item ? "[redacted]" : item;
    } else {
      output[key] = redactForApi(item);
    }
  }
  return output;
}

export function escapeHtml(text) {
  return String(text)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
