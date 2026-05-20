import assert from "node:assert/strict";
import test from "node:test";
import {
  defaultAdapterFixtures,
  replayAdapterFixturesToLocalSessionBundle
} from "../scripts/local-ai-adapter-fixtures.mjs";

test("adapter fixtures replay into one local session bundle without real local paths or secrets", () => {
  const fixtures = defaultAdapterFixtures();
  const bundle = replayAdapterFixturesToLocalSessionBundle(fixtures, {
    organizationId: "org-demo",
    repoIdentityHash: "fixture-repo-hash"
  });

  assert.equal(fixtures.length, 4);
  assert.equal(bundle.sourceKind, "local_ai_session");
  assert.equal(bundle.organizationId, "org-demo");
  assert.equal(bundle.repositoryDisplayLabel, "fixture/adapter-repo");
  assert.equal(bundle.repositoryUrl, null);
  assert.equal(bundle.toolProvider, "codex-cli");
  assert.equal(bundle.toolSessionId, "fixture-process-event");
  assert.equal(bundle.toolEventId, "fixture-stream-event");
  assert.equal(bundle.idempotencyKey, "fixture:fixture-repo-hash:fixture-process-event+fixture-cli-event+fixture-patch-event+fixture-stream-event");
  assert.equal(bundle.attributionConfidence, 0.94);
  assert.deepEqual(bundle.attributionReasons, [
    "process_signal",
    "cli_transcript",
    "patch_output",
    "json_stream",
    "fixture_replay"
  ]);

  const toolEvents = bundle.artifacts.filter((artifact) => artifact.itemType === "tool_event");
  assert.equal(toolEvents.length, 4);
  assert.ok(toolEvents.every((artifact) => artifact.content === null));
  assert.ok(toolEvents.every((artifact) => artifact.contentHash.length === 64));
  assert.deepEqual(
    toolEvents.map((artifact) => artifact.metadata.capabilityFlags),
    ["process_signal", "cli_transcript", "patch_output", "json_stream"]
  );

  const diff = bundle.artifacts.find((artifact) => artifact.itemType === "diff");
  assert.equal(diff.repoRelativePath, "src/adapter-fixture.ts");
  assert.match(diff.content, /ai_assisted/);
  assert.equal(diff.contentHash.length, 64);

  const serialized = JSON.stringify(bundle);
  assert.equal(serialized.includes("/Users/"), false);
  assert.equal(serialized.includes("sk-"), false);
  assert.equal(serialized.includes("secret"), false);
});

test("adapter fixture replay rejects empty fixture sets", () => {
  assert.throws(
    () => replayAdapterFixturesToLocalSessionBundle([]),
    /fixtures must contain at least one adapter event/
  );
});
