export const DEFAULT_DATA_DIR = ".local-data";
export const DEFAULT_PORT = 4173;

export const EMPTY_DB = {
  organizations: [],
  users: [],
  teams: [],
  projects: [],
  memberships: [],
  aiProviders: [],
  sourceBundles: [],
  evidenceItems: [],
  sourceLinks: [],
  generationRuns: [],
  patternCards: [],
  patternCardVersions: [],
  patternEvidenceLinks: [],
  patternTags: [],
  patternTagLinks: [],
  problems: [],
  problemVersions: [],
  reviewTasks: [],
  reviewDecisions: [],
  submissions: [],
  proficiencyScores: [],
  auditLogs: [],
  sessionTokens: [],
  jobs: [],
  metrics: []
};

export const ROLE_ORDER = ["learner", "contributor", "reviewer", "admin"];

export const PROVIDER_TASKS = [
  "pattern_extraction",
  "tagging",
  "problem_generation",
  "answer_generation",
  "review_assistance"
];
