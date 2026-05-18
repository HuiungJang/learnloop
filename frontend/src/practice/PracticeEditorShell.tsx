import type { PracticeFileResponse } from "../api/client";
import { CodeEditor } from "./CodeEditor";

type PracticeEditorShellProps = {
  files: PracticeFileResponse[];
  activePath: string | null;
  theme: "vs" | "vs-dark";
};

export function PracticeEditorShell({ files, activePath, theme }: PracticeEditorShellProps) {
  return <CodeEditor activePath={activePath} files={files} theme={theme} />;
}
