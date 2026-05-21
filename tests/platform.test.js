import assert from "node:assert/strict";
import { once } from "node:events";
import http from "node:http";
import test from "node:test";
import { openCredential } from "../src/security.js";
import { createConfirmedSourceLink, createPublishedPattern, request, withServer } from "./helpers.js";

async function withFakeProvider(responseBody, testFn) {
  const requests = [];
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const rawBody = Buffer.concat(chunks).toString("utf8");
    requests.push({
      method: req.method,
      url: req.url,
      headers: req.headers,
      body: rawBody ? JSON.parse(rawBody) : null
    });
    res.writeHead(200, { "content-type": "application/json" });
    res.end(`${JSON.stringify(responseBody)}\n`);
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const address = server.address();
  try {
    return await testFn({ providerBaseUrl: `http://127.0.0.1:${address.port}`, requests });
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
}

test("core workflow publishes a generated pattern and accepts learner submissions", async () => {
  await withServer(async ({ baseUrl }) => {
    const cardId = await createPublishedPattern(baseUrl);

    const library = await request(baseUrl, "/api/library?organizationId=org-demo", { userId: "u-learner" });
    assert.equal(library.response.status, 200);
    assert.equal(library.json.cards.length, 1);
    assert.equal(library.json.cards[0].id, cardId);

    const detail = await request(baseUrl, `/api/pattern-cards/${cardId}`, { userId: "u-learner" });
    assert.equal(detail.response.status, 200);
    assert.equal(detail.json.patternCard.problems.length, 3);
    assert.equal(detail.json.patternCard.problems[0].referenceAnswer, undefined);

    const problemId = detail.json.patternCard.problems[0].id;
    const submission = await request(baseUrl, `/api/problems/${problemId}/submissions`, {
      userId: "u-learner",
      method: "POST",
      body: {
        textAnswer: "Use this pattern when reusable API timeout handling is needed.",
        resultStatus: "self_marked_complete"
      }
    });
    assert.equal(submission.response.status, 201);
    assert.ok(submission.json.submission.id);
    assert.ok(submission.json.patternCard.problems[0].referenceAnswer);

    const progress = await request(baseUrl, "/api/progress?organizationId=org-demo", { userId: "u-learner" });
    assert.equal(progress.response.status, 200);
    assert.ok(progress.json.proficiency.length > 0);
  });
});

test("secret scanning blocks sensitive evidence without echoing secret values", async () => {
  await withServer(async ({ baseUrl }) => {
    const secret = "sk-testtesttesttesttesttesttesttest";
    const result = await request(baseUrl, "/api/ingest/manual", {
      userId: "u-contributor",
      method: "POST",
      body: {
        organizationId: "org-demo",
        teamId: "team-platform",
        projectId: "project-learning",
        title: "Unsafe evidence",
        content: `const key = "${secret}";`
      }
    });
    assert.equal(result.response.status, 201);
    assert.equal(result.json.bundle.status, "blocked_sensitive");
    assert.equal(JSON.stringify(result.json).includes(secret), false);
  });
});

test("authorization prevents learners from reading raw evidence or review queue", async () => {
  await withServer(async ({ baseUrl }) => {
    const ingest = await request(baseUrl, "/api/ingest/manual", {
      userId: "u-contributor",
      method: "POST",
      body: {
        organizationId: "org-demo",
        teamId: "team-platform",
        projectId: "project-learning",
        title: "Readable only by contributors",
        content: "function example() { return true; }"
      }
    });
    const evidence = await request(baseUrl, `/api/evidence/${ingest.json.bundle.id}`, { userId: "u-learner" });
    assert.equal(evidence.response.status, 403);

    const review = await request(baseUrl, "/api/review/tasks?organizationId=org-demo", { userId: "u-learner" });
    assert.equal(review.response.status, 403);
  });
});

test("user identity cannot be spoofed with x-user-id without a session token", async () => {
  await withServer(async ({ baseUrl }) => {
    const response = await fetch(`${baseUrl}/api/audit?organizationId=org-demo`, {
      headers: { "x-user-id": "u-admin" }
    });
    const body = await response.json();
    assert.equal(response.status, 401);
    assert.equal(body.error.message, "Authentication required");
  });
});

test("provider credentials are stored as references and redacted from API responses", async () => {
  await withServer(async ({ baseUrl, store }) => {
    const secret = "personal-provider-secret-1234";
    const result = await request(baseUrl, "/api/providers", {
      userId: "u-learner",
      method: "POST",
      body: {
        organizationId: "org-demo",
        provider: " OpenAI ",
        model: " example-model ",
        scope: "personal",
        credential: secret,
        retentionMode: "standard"
      }
    });
    assert.equal(result.response.status, 201);
    assert.equal(JSON.stringify(result.json).includes(secret), false);

    const rawProvider = store.db.aiProviders.find((provider) => provider.provider === "openai");
    assert.equal(rawProvider.model, "example-model");
    assert.equal(rawProvider.baseUrl, "https://api.openai.com");
    assert.ok(rawProvider.credentialRef.startsWith("vault://"));
    assert.equal(rawProvider.credentialAlgorithm, "aes-256-gcm");
    assert.ok(rawProvider.credentialCiphertext);
    assert.equal(openCredential(rawProvider), secret);
    assert.equal(JSON.stringify(rawProvider).includes(secret), false);
    assert.equal(JSON.stringify(result.json).includes(rawProvider.credentialCiphertext), false);
  });
});

test("provider registration validates custom base URLs", async () => {
  await withServer(async ({ baseUrl }) => {
    const unsafe = await request(baseUrl, "/api/providers", {
      userId: "u-admin",
      method: "POST",
      body: {
        organizationId: "org-demo",
        provider: "openai",
        model: "example-model",
        scope: "organization",
        credential: "organization-provider-secret",
        orgApproved: true,
        baseUrl: "http://example.com"
      }
    });
    assert.equal(unsafe.response.status, 400);
    assert.match(unsafe.json.error.message, /https/);

    const previous = process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
    process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = "1";
    try {
      const loopback = await request(baseUrl, "/api/providers", {
        userId: "u-admin",
        method: "POST",
        body: {
          organizationId: "org-demo",
          provider: "openai",
          model: "example-model",
          scope: "organization",
          credential: "organization-provider-secret",
          orgApproved: true,
          baseUrl: "http://127.0.0.1:8080/v1?blocked=true"
        }
      });
      assert.equal(loopback.response.status, 400);

      const allowed = await request(baseUrl, "/api/providers", {
        userId: "u-admin",
        method: "POST",
        body: {
          organizationId: "org-demo",
          provider: "openai",
          model: "example-model",
          scope: "organization",
          credential: "organization-provider-secret",
          orgApproved: true,
          baseUrl: "http://127.0.0.1:8080"
        }
      });
      assert.equal(allowed.response.status, 201);
      assert.equal(allowed.json.provider.baseUrl, "http://127.0.0.1:8080");
    } finally {
      if (previous === undefined) {
        delete process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
      } else {
        process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = previous;
      }
    }
  });
});

test("generation calls an OpenAI-compatible provider and stores returned pattern data", async () => {
  const providerOutput = {
    title: "Provider Generated Retry Pattern",
    summary: "A reusable pattern for applying bounded retries and timeouts around service calls while keeping domain data generic.",
    tags: [
      { tagType: "language", name: "TypeScript" },
      { tagType: "pattern", name: "Retry/Timeout" },
      { tagType: "api", name: "HTTP API" }
    ],
    problems: [
      {
        type: "qa",
        prompt: "When should this retry and timeout pattern be applied?",
        referenceAnswer: "Apply it when a service boundary can fail transiently and callers need bounded waiting plus clear failure behavior.",
        difficulty: "easy"
      },
      {
        type: "short_implementation",
        prompt: "Implement a neutral order-service client with bounded retry and timeout behavior.",
        referenceAnswer: "A strong implementation keeps retry limits explicit, avoids infinite waiting, and separates transport errors from domain logic.",
        difficulty: "medium"
      }
    ]
  };
  const previous = process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
  process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = "1";
  try {
    await withFakeProvider({ output_text: JSON.stringify(providerOutput), usage: { input_tokens: 123, output_tokens: 456 } }, async ({ providerBaseUrl, requests }) => {
      await withServer(async ({ baseUrl, store }) => {
        const provider = await request(baseUrl, "/api/providers", {
          userId: "u-admin",
          method: "POST",
          body: {
            organizationId: "org-demo",
            provider: "openai",
            model: "fake-responses-model",
            scope: "organization",
            credential: "organization-provider-secret",
            orgApproved: true,
            baseUrl: providerBaseUrl
          }
        });
        assert.equal(provider.response.status, 201);

        const { linkId } = await createConfirmedSourceLink(baseUrl);
        const generated = await request(baseUrl, "/api/generation/run", {
          userId: "u-contributor",
          method: "POST",
          body: {
            organizationId: "org-demo",
            providerConfigId: provider.json.provider.id,
            sourceLinkIds: [linkId],
            visibility: "organization"
          }
        });

        assert.equal(generated.response.status, 201);
        assert.equal(generated.json.patternCard.title, providerOutput.title);
        assert.equal(generated.json.generationRun.outputTokens, 456);
        assert.equal(requests.length, 1);
        assert.equal(requests[0].method, "POST");
        assert.equal(requests[0].url, "/v1/responses");
        assert.equal(requests[0].headers.authorization, "Bearer organization-provider-secret");
        assert.equal(requests[0].body.model, "fake-responses-model");
        assert.equal(requests[0].body.text.format.type, "json_schema");
        assert.equal(requests[0].body.text.format.name, "learnloop_pattern_generation");
        assert.equal(JSON.stringify(store.db).includes("organization-provider-secret"), false);
      });
    });
  } finally {
    if (previous === undefined) {
      delete process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
    } else {
      process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = previous;
    }
  }
});

test("invalid provider output fails the generation run without partial assets", async () => {
  const previous = process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
  process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = "1";
  try {
    await withFakeProvider({ output_text: "not-json" }, async ({ providerBaseUrl }) => {
      await withServer(async ({ baseUrl, store }) => {
        const provider = await request(baseUrl, "/api/providers", {
          userId: "u-admin",
          method: "POST",
          body: {
            organizationId: "org-demo",
            provider: "openai",
            model: "fake-responses-model",
            scope: "organization",
            credential: "organization-provider-secret",
            orgApproved: true,
            baseUrl: providerBaseUrl
          }
        });
        const { linkId } = await createConfirmedSourceLink(baseUrl);
        const generated = await request(baseUrl, "/api/generation/run", {
          userId: "u-contributor",
          method: "POST",
          body: {
            organizationId: "org-demo",
            providerConfigId: provider.json.provider.id,
            sourceLinkIds: [linkId],
            visibility: "organization"
          }
        });

        assert.equal(generated.response.status, 502);
        assert.equal(generated.json.error.message, "Provider generation failed");
        assert.equal(store.db.generationRuns.length, 1);
        assert.equal(store.db.generationRuns[0].status, "failed");
        assert.equal(store.db.generationRuns[0].failureReason, "provider_output_invalid_json");
        assert.equal(store.db.patternCards.length, 0);
        assert.equal(store.db.problems.length, 0);
        assert.equal(store.db.reviewTasks.length, 0);
        assert.ok(store.db.auditLogs.some((entry) => entry.eventType === "generation.failed"));
        assert.ok(store.db.metrics.some((entry) => entry.name === "generation.failed"));
      });
    });
  } finally {
    if (previous === undefined) {
      delete process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL;
    } else {
      process.env.APP_ALLOW_INSECURE_PROVIDER_BASE_URL = previous;
    }
  }
});

test("personal providers cannot publish organization assets by default", async () => {
  await withServer(async ({ baseUrl }) => {
    const provider = await request(baseUrl, "/api/providers", {
      userId: "u-contributor",
      method: "POST",
      body: {
        organizationId: "org-demo",
        provider: "anthropic",
        model: "example-model",
        scope: "personal",
        credential: "personal-secret-0000",
        retentionMode: "standard"
      }
    });
    assert.equal(provider.response.status, 201);

    const common = {
      organizationId: "org-demo",
      teamId: "team-platform",
      projectId: "project-learning"
    };
    const code = await request(baseUrl, "/api/ingest/manual", {
      userId: "u-contributor",
      method: "POST",
      body: { ...common, title: "Code", content: "function retry() { return true; }" }
    });
    const convo = await request(baseUrl, "/api/ingest/codex-obsidian", {
      userId: "u-contributor",
      method: "POST",
      body: {
        ...common,
        exportData: {
          schemaVersion: 1,
          conversations: [{ messages: [{ role: "user", content: "Generate retry pattern." }] }]
        }
      }
    });
    const suggested = await request(baseUrl, "/api/source-links/suggest", {
      userId: "u-contributor",
      method: "POST",
      body: { conversationBundleId: convo.json.bundle.id, codeBundleId: code.json.bundle.id }
    });
    const linkId = suggested.json.links[0].id;
    await request(baseUrl, `/api/source-links/${linkId}/confirm`, {
      userId: "u-contributor",
      method: "POST",
      body: {}
    });
    const generation = await request(baseUrl, "/api/generation/run", {
      userId: "u-contributor",
      method: "POST",
      body: {
        organizationId: "org-demo",
        providerConfigId: provider.json.provider.id,
        sourceLinkIds: [linkId],
        visibility: "organization"
      }
    });
    assert.equal(generation.response.status, 403);
  });
});
