export type HealthResponse = {
  status: string;
};

export type DemoUser = "admin" | "contributor" | "reviewer" | "learner";

export type SessionResponse = {
  token: string;
  user: {
    id: string;
    email: string;
    displayName: string;
  };
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

export type ProgressResponse = {
  proficiency: Array<{ tagName: string; score: number }>;
};

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";

const userEmails: Record<DemoUser, string> = {
  admin: "admin@example.com",
  contributor: "contributor@example.com",
  reviewer: "reviewer@example.com",
  learner: "learner@example.com"
};

export async function fetchHealth(): Promise<HealthResponse> {
  return request<HealthResponse>("/api/health");
}

export async function createSession(user: DemoUser): Promise<SessionResponse> {
  return request<SessionResponse>("/api/session", {
    method: "POST",
    body: {
      email: userEmails[user],
      password: "demo-password"
    }
  });
}

export async function listProviders(token: string): Promise<ProviderResponse[]> {
  const response = await request<{ providers: ProviderResponse[] }>("/api/providers?organizationId=org-demo", { token });
  return response.providers;
}

export async function runLearningDemo(): Promise<{
  providers: ProviderResponse[];
  codeBundle: SourceBundleResponse;
  conversationBundle: SourceBundleResponse;
  sourceLink: SourceLinkResponse;
  reviewTask: ReviewTaskResponse;
  patternCard: PatternCardResponse;
  progress: ProgressResponse;
}> {
  const contributor = await createSession("contributor");
  const reviewer = await createSession("reviewer");
  const learner = await createSession("learner");
  const providers = await listProviders(contributor.token);

  const code = await request<{ bundle: SourceBundleResponse }>("/api/ingest/manual", {
    token: contributor.token,
    method: "POST",
    body: {
      organizationId: "org-demo",
      teamId: "team-platform",
      projectId: "project-learning",
      title: "React Query timeout evidence",
      sourceKind: "code",
      repositoryUrl: "https://github.com/example/learning-platform",
      filePaths: ["frontend/src/api/client.ts"],
      provenance: { source: "frontend-demo" },
      content: "function retryTimeout(queryClient) { return queryClient.invalidateQueries({ queryKey: ['timeout'] }); }"
    }
  });

  const conversation = await request<{ bundle: SourceBundleResponse }>("/api/ingest/codex-obsidian", {
    token: contributor.token,
    method: "POST",
    body: {
      organizationId: "org-demo",
      teamId: "team-platform",
      projectId: "project-learning",
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
    token: contributor.token,
    method: "POST",
    body: {
      conversationBundleId: conversation.bundle.id,
      codeBundleId: code.bundle.id
    }
  });
  const sourceLink = await request<SourceLinkResponse>(`/api/source-links/${sourceLinks.links[0].id}/confirm`, {
    token: contributor.token,
    method: "POST",
    body: {}
  });

  const generated = await request<{
    reviewTask: ReviewTaskResponse;
    patternCard: PatternCardResponse;
  }>("/api/generation/run", {
    token: contributor.token,
    method: "POST",
    body: {
      organizationId: "org-demo",
      providerConfigId: "provider-local-mock",
      sourceLinkIds: [sourceLink.id],
      visibility: "organization"
    }
  });

  await request(`/api/review/tasks/${generated.reviewTask.id}/decision`, {
    token: reviewer.token,
    method: "POST",
    body: {
      decision: "approve",
      comment: "Looks safe."
    }
  });

  const detail = await request<{ patternCard: PatternCardResponse }>(`/api/pattern-cards/${generated.patternCard.id}`, {
    token: learner.token
  });
  const problemId = detail.patternCard.problems[0].id;
  const submission = await request<{ patternCard: PatternCardResponse }>(`/api/problems/${problemId}/submissions`, {
    token: learner.token,
    method: "POST",
    body: {
      textAnswer: "Use explicit query keys and invalidate the smallest practical cache boundary.",
      resultStatus: "self_marked_complete"
    }
  });
  const progress = await request<ProgressResponse>("/api/progress?organizationId=org-demo", { token: learner.token });

  return {
    providers,
    codeBundle: code.bundle,
    conversationBundle: conversation.bundle,
    sourceLink,
    reviewTask: generated.reviewTask,
    patternCard: submission.patternCard,
    progress
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
