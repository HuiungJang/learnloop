import type { PracticeFileResponse } from "../api/client";

export function languageForFile(file: Pick<PracticeFileResponse, "path" | "language">): string {
  if (file.language === "typescript") return "typescript";
  if (file.language === "kotlin") return "kotlin";
  if (file.language === "java") return "java";
  if (file.path.endsWith(".json")) return "json";
  if (file.path.endsWith(".md")) return "markdown";
  if (file.path.endsWith(".ts") || file.path.endsWith(".tsx")) return "typescript";
  if (file.path.endsWith(".kt")) return "kotlin";
  if (file.path.endsWith(".java")) return "java";
  return "plaintext";
}
