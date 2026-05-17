import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createApp } from "../src/app.js";
import { JsonStore } from "../src/store.js";

export async function withServer(testFn) {
  const dataDir = await mkdtemp(path.join(tmpdir(), "ai-code-learning-"));
  const store = new JsonStore(dataDir);
  await store.init();
  const server = await createApp(store);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const baseUrl = `http://127.0.0.1:${address.port}`;
  try {
    return await testFn({ baseUrl, store });
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await rm(dataDir, { recursive: true, force: true });
  }
}

export async function request(baseUrl, path, { userId = "u-admin", method = "GET", body } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      "content-type": "application/json",
      "x-user-id": userId
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const json = await response.json();
  return { response, json };
}

export async function createPublishedPattern(baseUrl) {
  const common = {
    organizationId: "org-demo",
    teamId: "team-platform",
    projectId: "project-learning"
  };
  const code = await request(baseUrl, "/api/ingest/manual", {
    userId: "u-contributor",
    method: "POST",
    body: {
      ...common,
      title: "Timeout client code",
      evidenceType: "code",
      content: "async function fetchOrder(client, id) { return client.get(`/orders/${id}`, { timeout: 3000 }); }",
      provenance: { repo: "demo/repo", filePath: "src/client.js" }
    }
  });
  const conversation = await request(baseUrl, "/api/ingest/codex-obsidian", {
    userId: "u-contributor",
    method: "POST",
    body: {
      ...common,
      exportData: {
        schemaVersion: 1,
        title: "Codex timeout session",
        conversations: [
          {
            title: "Timeout guidance",
            messages: [
              { role: "user", content: "Add timeout handling to API client code." },
              { role: "assistant", content: "Use explicit timeout behavior and preserve the API boundary." }
            ]
          }
        ]
      }
    }
  });
  const links = await request(baseUrl, "/api/source-links/suggest", {
    userId: "u-contributor",
    method: "POST",
    body: {
      conversationBundleId: conversation.json.bundle.id,
      codeBundleId: code.json.bundle.id
    }
  });
  const linkId = links.json.links[0].id;
  await request(baseUrl, `/api/source-links/${linkId}/confirm`, {
    userId: "u-contributor",
    method: "POST",
    body: {}
  });
  const generated = await request(baseUrl, "/api/generation/run", {
    userId: "u-contributor",
    method: "POST",
    body: {
      organizationId: "org-demo",
      providerConfigId: "provider-local-mock",
      sourceLinkIds: [linkId],
      visibility: "organization"
    }
  });
  const reviewTaskId = generated.json.reviewTask.id;
  await request(baseUrl, `/api/review/tasks/${reviewTaskId}/decision`, {
    userId: "u-reviewer",
    method: "POST",
    body: { decision: "approve", comment: "Looks safe." }
  });
  return generated.json.patternCard.id;
}
