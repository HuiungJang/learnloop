const DEFAULT_ADAPTERS = Object.freeze([
  { provider: "codex_cli", label: "Codex CLI" },
  { provider: "codex_app", label: "Codex App" },
  { provider: "claude_code", label: "Claude Code" },
  { provider: "gemini_cli", label: "Gemini CLI" }
]);

export class AdapterStatusRegistry {
  constructor(adapters = DEFAULT_ADAPTERS) {
    this.adapters = adapters.map((adapter) => ({
      provider: safeToken(adapter.provider) || "unknown",
      label: safeLabel(adapter.label) || safeToken(adapter.provider) || "Unknown adapter"
    }));
    this.states = new Map(this.adapters.map((adapter) => [adapter.provider, idleState(adapter)]));
  }

  record(event) {
    try {
      const input = typeof event === "object" && event !== null ? event : {};
      const provider = safeToken(input.provider);
      if (!provider) return;
      const state = this.states.get(provider) ?? idleState({ provider, label: provider });
      this.states.set(provider, updateState(state, input));
    } catch {
      // Adapter status must not affect collection.
    }
  }

  snapshot() {
    const orderedProviders = this.adapters.map((adapter) => adapter.provider);
    const dynamicProviders = [...this.states.keys()].filter((provider) => !orderedProviders.includes(provider)).sort();
    return [...orderedProviders, ...dynamicProviders].map((provider) => this.states.get(provider)).filter(Boolean);
  }
}

export function createAdapterStatusRegistry(adapters) {
  return new AdapterStatusRegistry(adapters);
}

function updateState(state, input) {
  const type = safeToken(input.type) || "unknown";
  const base = {
    ...state,
    lastEventType: type,
    lastEventAt: safeTimestamp(input.endedAt) ?? safeTimestamp(input.checkedAt) ?? safeTimestamp(input.startedAt) ?? new Date().toISOString()
  };

  if (type === "shim_start") {
    return {
      ...base,
      status: "running",
      reason: null,
      errorCode: null
    };
  }

  if (type === "shim_end") {
    const exitCode = safeNumber(input.exitCode);
    return {
      ...base,
      status: exitCode === null || exitCode === 0 ? "ok" : "failed",
      reason: exitCode === null || exitCode === 0 ? null : "non_zero_exit",
      errorCode: null
    };
  }

  if (type === "shim_health" || type === "adapter_error") {
    return {
      ...base,
      status: input.runtimeStatus === "missing" ? "missing" : "failed",
      reason: safeToken(input.runtimeProblem) || safeToken(input.reason) || type,
      errorCode: safeToken(input.errorCode)
    };
  }

  return base;
}

function idleState(adapter) {
  return {
    provider: adapter.provider,
    label: adapter.label,
    status: "idle",
    reason: null,
    errorCode: null,
    lastEventType: null,
    lastEventAt: null
  };
}

function safeToken(value) {
  return typeof value === "string" && value.trim() !== "" ? value.trim().replace(/[^A-Za-z0-9_.:#@+-]/g, "_").slice(0, 120) : null;
}

function safeLabel(value) {
  return typeof value === "string" && value.trim() !== "" ? value.trim().slice(0, 80) : null;
}

function safeTimestamp(value) {
  if (typeof value !== "string" || value.trim() === "") return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function safeNumber(value) {
  return Number.isFinite(value) ? value : null;
}
