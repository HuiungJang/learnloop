import type { PracticeAttemptFileRequest, PracticeFileResponse } from "../api/client";
import { CodeEditor } from "./CodeEditor";

type PracticeEditorShellProps = {
  files: PracticeFileResponse[];
  activePath: string | null;
  onCommandPalette?: () => void;
  onOpenQuickFile?: () => void;
  onSave?: (files: PracticeAttemptFileRequest[]) => void;
  onSnapshotReady?: (snapshotter: (() => PracticeAttemptFileRequest[]) | null) => void;
  theme: "vs" | "vs-dark";
};

export function PracticeEditorShell({
  files,
  activePath,
  onCommandPalette,
  onOpenQuickFile,
  onSave,
  onSnapshotReady,
  theme
}: PracticeEditorShellProps) {
  return (
    <CodeEditor
      activePath={activePath}
      files={files}
      onCommandPalette={onCommandPalette}
      onOpenQuickFile={onOpenQuickFile}
      onSave={onSave}
      onSnapshotReady={onSnapshotReady}
      theme={theme}
    />
  );
}
