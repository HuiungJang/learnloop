import assert from "node:assert/strict";
import test from "node:test";
import { createPublishedPattern, request, withServer } from "./helpers.js";

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
        provider: "openai",
        model: "example-model",
        scope: "personal",
        credential: secret,
        retentionMode: "standard"
      }
    });
    assert.equal(result.response.status, 201);
    assert.equal(JSON.stringify(result.json).includes(secret), false);

    const rawProvider = store.db.aiProviders.find((provider) => provider.provider === "openai");
    assert.ok(rawProvider.credentialRef.startsWith("vault://"));
    assert.equal(JSON.stringify(rawProvider).includes(secret), false);
  });
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
