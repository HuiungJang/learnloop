import type { PracticeAttemptFileRequest, PracticeFileResponse } from "../api/client";
import { CodeEditor } from "./CodeEditor";

type PracticeEditorShellProps = {
  files: PracticeFileResponse[];
  activePath: string | null;
  onCommandPalette?: () => void;
  onOpenQuickFile?: () => void;
  onRun?: (files: PracticeAttemptFileRequest[]) => void;
  onSave?: (files: PracticeAttemptFileRequest[]) => void;
  onSnapshotReady?: (snapshotter: (() => PracticeAttemptFileRequest[]) | null) => void;
  onStatus?: (message: string) => void;
  onSubmit?: (files: PracticeAttemptFileRequest[]) => void;
  onToggleDiff?: () => void;
  onToggleTheme?: () => void;
  theme: "vs" | "vs-dark";
};

export function PracticeEditorShell({
  files,
  activePath,
  onCommandPalette,
  onOpenQuickFile,
  onRun,
  onSave,
  onSnapshotReady,
  onStatus,
  onSubmit,
  onToggleDiff,
  onToggleTheme,
  theme
}: PracticeEditorShellProps) {
  return (
    <CodeEditor
      activePath={activePath}
      files={files}
      onCommandPalette={onCommandPalette}
      onOpenQuickFile={onOpenQuickFile}
      onRun={onRun}
      onSave={onSave}
      onSnapshotReady={onSnapshotReady}
      onStatus={onStatus}
      onSubmit={onSubmit}
      onToggleDiff={onToggleDiff}
      onToggleTheme={onToggleTheme}
      theme={theme}
    />
  );
}
