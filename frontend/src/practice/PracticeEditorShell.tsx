import type { PracticeAttemptFileRequest, PracticeFileResponse } from "../api/client";
import { CodeEditor } from "./CodeEditor";

type PracticeEditorShellProps = {
  files: PracticeFileResponse[];
  activePath: string | null;
  onSave?: (files: PracticeAttemptFileRequest[]) => void;
  theme: "vs" | "vs-dark";
};

export function PracticeEditorShell({ files, activePath, onSave, theme }: PracticeEditorShellProps) {
  return <CodeEditor activePath={activePath} files={files} onSave={onSave} theme={theme} />;
}
