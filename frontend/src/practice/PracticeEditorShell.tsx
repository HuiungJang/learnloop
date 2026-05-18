import * as monaco from "monaco-editor";
import type { PracticeFileResponse } from "../api/client";
import "./monacoEnvironment";

type PracticeEditorShellProps = {
  file: PracticeFileResponse | undefined;
};

export function PracticeEditorShell({ file }: PracticeEditorShellProps) {
  const languageLabel = file === undefined ? "plaintext" : monacoLanguage(file);

  return (
    <div className="monaco-lazy-shell" data-language={languageLabel}>
      <pre>{file?.content ?? "// No starter file available yet."}</pre>
    </div>
  );
}

function monacoLanguage(file: PracticeFileResponse): string {
  if (file.language === "kotlin") return "kotlin";
  if (file.language === "java") return "java";
  if (file.path.endsWith(".json")) return "json";
  if (file.path.endsWith(".md")) return "markdown";
  if (file.language === "typescript") return "typescript";
  return monaco.languages.getLanguages().some((language) => language.id === file.language) ? file.language : "plaintext";
}
