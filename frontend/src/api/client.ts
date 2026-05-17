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

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";

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
    const errorPayload = payload as { error?: { message?: string } };
    const message = errorPayload.error?.message ?? `Request failed with ${response.status}`;
    throw new Error(message);
  }

  return payload as T;
}
