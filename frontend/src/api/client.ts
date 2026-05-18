export type HealthResponse = {
  status: string;
};

export type Membership = {
  organizationId: string;
  teamId: string | null;
  projectId: string | null;
  role: string;
};

export type AuthUser = {
  id: string;
  email: string;
  displayName: string;
  memberships: Membership[];
};

export type SessionResponse = {
  token: string;
  user: AuthUser;
  expiresAt: string;
};

export type ProviderResponse = {
  id: string;
  provider: string;
  model: string;
  scope: string;
  status: string;
  orgApproved: boolean;
};

export type SourceBundleResponse = {
  id: string;
  title: string;
  sourceKind: string;
  status: string;
};

export type SourceLinkResponse = {
  id: string;
  status: string;
  confidence: number;
};

export type ReviewTaskResponse = {
  id: string;
  patternCardId: string;
  status: string;
};

export type PatternCardResponse = {
  id: string;
  title: string;
  summary: string;
  publicationStatus: string;
  tags: Array<{ tagType: string; name: string }>;
  problems: Array<{
    id: string;
    type: string;
    prompt: string;
    referenceAnswer: string | null;
    difficulty: string;
  }>;
};

export type PracticeFileResponse = {
  path: string;
  language: string;
  role: string;
  content: string;
  readOnly: boolean;
  sortOrder: number;
};

export type PracticeHintResponse = {
  id: string;
  revealOrder: number;
  label: string;
  content: string | null;
  revealed: boolean;
};

export type PracticeProvenanceResponse = {
  sourceType: string;
  sourceLabel: string;
  redactedExcerpt: string;
  evidenceItemId: string | null;
};

export type PracticeAttemptFileRequest = {
  path: string;
  content: string;
};

export type PracticeAttemptFileResponse = {
  path: string;
  content: string;
};

export type PracticeRunTestResponse = {
  name: string;
  status: string;
  message: string | null;
  durationMs: number | null;
};

export type PracticeRunResultResponse = {
  id: string;
  status: string;
  runnerKind: string;
  durationMs: number | null;
  tests: PracticeRunTestResponse[];
  stdoutExcerpt: string | null;
  stderrExcerpt: string | null;
  failedDiff: string | null;
  failureReason: string | null;
  createdAt: string;
};

export type PracticeAttemptResponse = {
  id: string;
  problemId: string;
  clientAttemptId: string;
  assetRevision: string;
  language: string;
  status: string;
  files: PracticeAttemptFileResponse[];
  score: number | null;
  resultStatus: string | null;
  updatedAt: string;
  submittedAt: string | null;
};

export type PracticeProblemResponse = {
  id: string;
  patternCardId: string;
  title: string;
  prompt: string;
  difficulty: string;
  assetRevision: string;
  files: PracticeFileResponse[];
  hints: PracticeHintResponse[];
  provenance: PracticeProvenanceResponse[];
  attempt: PracticeAttemptResponse | null;
  latestRun: PracticeRunResultResponse | null;
};

export type PracticeAttemptSyncRequest = {
  clientAttemptId: string;
  assetRevision: string;
  language: string;
  intent: "draft";
  files: PracticeAttemptFileRequest[];
  localUpdatedAt: string;
};

export type PracticeSubmissionRequest = {
  textAnswer?: string;
  resultStatus?: string;
  clientAttemptId?: string;
  assetRevision?: string;
  language?: string;
  files?: PracticeAttemptFileRequest[];
};

export type SubmissionResponse = {
  id: string;
  problemId: string;
  userId: string;
  resultStatus: string;
  createdAt: string;
};

export type PracticeSubmissionResponse = {
  submission: SubmissionResponse;
  patternCard: PatternCardResponse;
};

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";

export class ApiRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string | null
  ) {
    super(message);
    this.name = "ApiRequestError";
  }
}

export function isConflictError(error: unknown): boolean {
  return error instanceof ApiRequestError && error.status === 409;
}

export async function fetchHealth(): Promise<HealthResponse> {
  return request<HealthResponse>("/api/health");
}

export async function createSession(email: string, password: string): Promise<SessionResponse> {
  return request<SessionResponse>("/api/session", {
    method: "POST",
    body: {
      email,
      password
    }
  });
}

export async function registerUser(
  email: string,
  displayName: string,
  password: string
): Promise<SessionResponse> {
  return request<SessionResponse>("/api/register", {
    method: "POST",
    body: {
      email,
      displayName,
      password
    }
  });
}

export async function listProviders(token: string, organizationId: string): Promise<ProviderResponse[]> {
  const response = await request<{ providers: ProviderResponse[] }>(`/api/providers?organizationId=${encodeURIComponent(organizationId)}`, { token });
  return response.providers;
}

export async function runLearningDemo(token: string, membership: Membership): Promise<{
  providers: ProviderResponse[];
  codeBundle: SourceBundleResponse;
  conversationBundle: SourceBundleResponse;
  sourceLink: SourceLinkResponse;
  reviewTask: ReviewTaskResponse;
  patternCard: PatternCardResponse;
}> {
  const organizationId = membership.organizationId;
  const teamId = membership.teamId;
  const projectId = membership.projectId;
  const providers = await listProviders(token, organizationId);

  const code = await request<{ bundle: SourceBundleResponse }>("/api/ingest/manual", {
    token,
    method: "POST",
    body: {
      organizationId,
      teamId,
      projectId,
      title: "React Query timeout evidence",
      sourceKind: "code",
      repositoryUrl: "https://github.com/example/learning-platform",
      filePaths: ["frontend/src/api/client.ts"],
      provenance: { source: "frontend-demo" },
      content: "function retryTimeout(queryClient) { return queryClient.invalidateQueries({ queryKey: ['timeout'] }); }"
    }
  });

  const conversation = await request<{ bundle: SourceBundleResponse }>("/api/ingest/codex-obsidian", {
    token,
    method: "POST",
    body: {
      organizationId,
      teamId,
      projectId,
      exportData: {
        schemaVersion: 1,
        title: "React Query guidance",
        conversations: [
          {
            messages: [
              { role: "user", content: "Create a reusable React Query invalidation pattern." },
              { role: "assistant", content: "Use explicit query keys and keep timeout behavior isolated." }
            ]
          }
        ]
      }
    }
  });

  const sourceLinks = await request<{ links: SourceLinkResponse[] }>("/api/source-links/suggest", {
    token,
    method: "POST",
    body: {
      conversationBundleId: conversation.bundle.id,
      codeBundleId: code.bundle.id
    }
  });
  const sourceLink = await request<SourceLinkResponse>(`/api/source-links/${sourceLinks.links[0].id}/confirm`, {
    token,
    method: "POST",
    body: {}
  });

  const generated = await request<{
    reviewTask: ReviewTaskResponse;
    patternCard: PatternCardResponse;
  }>("/api/generation/run", {
    token,
    method: "POST",
    body: {
      organizationId,
      providerConfigId: "provider-local-mock",
      sourceLinkIds: [sourceLink.id],
      visibility: "organization"
    }
  });

  const detail = await request<{ patternCard: PatternCardResponse }>(`/api/pattern-cards/${generated.patternCard.id}`, {
    token
  });

  return {
    providers,
    codeBundle: code.bundle,
    conversationBundle: conversation.bundle,
    sourceLink,
    reviewTask: generated.reviewTask,
    patternCard: detail.patternCard
  };
}

export async function getLibrary(
  token: string,
  organizationId: string,
  filters: { language?: string; tag?: string; difficulty?: string; page?: number; pageSize?: number } = {}
): Promise<PatternCardResponse[]> {
  const params = new URLSearchParams({ organizationId });
  if (filters.language !== undefined && filters.language.trim().length > 0) params.set("language", filters.language.trim());
  if (filters.tag !== undefined && filters.tag.trim().length > 0) params.set("tag", filters.tag.trim());
  if (filters.difficulty !== undefined && filters.difficulty.trim().length > 0) params.set("difficulty", filters.difficulty.trim());
  if (filters.page !== undefined) params.set("page", String(filters.page));
  if (filters.pageSize !== undefined) params.set("pageSize", String(filters.pageSize));
  const response = await request<{ cards: PatternCardResponse[] }>(`/api/library?${params.toString()}`, { token });
  return response.cards;
}

export async function getPracticeProblem(token: string, problemId: string): Promise<PracticeProblemResponse> {
  const response = await request<{ problem: PracticeProblemResponse }>(`/api/problems/${encodeURIComponent(problemId)}/practice`, { token });
  return response.problem;
}

export async function getCurrentPracticeAttempts(token: string, problemId: string): Promise<PracticeAttemptResponse[]> {
  const response = await request<{ attempts: PracticeAttemptResponse[] }>(`/api/problems/${encodeURIComponent(problemId)}/attempts/me`, { token });
  return response.attempts;
}

export async function syncLocalPracticeAttempt(
  token: string,
  problemId: string,
  body: PracticeAttemptSyncRequest
): Promise<PracticeAttemptResponse> {
  const response = await request<{ attempt: PracticeAttemptResponse }>(`/api/problems/${encodeURIComponent(problemId)}/attempts/local-sync`, {
    token,
    method: "POST",
    body
  });
  return response.attempt;
}

export async function submitPracticeAttempt(
  token: string,
  problemId: string,
  body: PracticeSubmissionRequest
): Promise<PracticeSubmissionResponse> {
  return request<PracticeSubmissionResponse>(`/api/problems/${encodeURIComponent(problemId)}/submissions`, {
    token,
    method: "POST",
    body
  });
}

async function request<T>(
  path: string,
  options: { method?: string; token?: string; body?: unknown } = {}
): Promise<T> {
  const response = await fetch(`${apiBaseUrl}${path}`, {
    method: options.method ?? "GET",
    headers: {
      Accept: "application/json",
      ...(options.body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(options.token === undefined ? {} : { Authorization: `Bearer ${options.token}` })
    },
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });

  const payload = (await response.json()) as unknown;
  if (!response.ok) {
    const errorPayload = payload as { error?: { message?: string; code?: string } };
    const message = errorPayload.error?.message ?? `Request failed with ${response.status}`;
    throw new ApiRequestError(message, response.status, errorPayload.error?.code ?? null);
  }

  return payload as T;
}
