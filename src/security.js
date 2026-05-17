import crypto from "node:crypto";

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
  return {
    credentialRef: `vault://${digest.slice(0, 24)}`,
    credentialFingerprint: digest.slice(0, 16),
    secretPreview: secret.length >= 4 ? `***${secret.slice(-4)}` : "***"
  };
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
