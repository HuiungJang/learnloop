import { useEffect, useMemo, useRef } from "react";
import * as monaco from "monaco-editor";
import type { PracticeAttemptFileRequest, PracticeFileResponse } from "../api/client";
import { languageForFile } from "./editorLanguages";
import "./monacoEnvironment";

type CodeEditorProps = {
  files: PracticeFileResponse[];
  activePath: string | null;
  onSave?: (files: PracticeAttemptFileRequest[]) => void;
  theme: "vs" | "vs-dark";
};

export function CodeEditor({ files, activePath, onSave, theme }: CodeEditorProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const filesRef = useRef<PracticeFileResponse[]>(files);
  const modelsRef = useRef<Map<string, monaco.editor.ITextModel>>(new Map());
  const onSaveRef = useRef(onSave);
  const filesByPath = useMemo(() => new Map(files.map((file) => [file.path, file])), [files]);

  useEffect(() => {
    filesRef.current = files;
  }, [files]);

  useEffect(() => {
    onSaveRef.current = onSave;
  }, [onSave]);

  useEffect(() => {
    if (containerRef.current === null || editorRef.current !== null) return;

    const editor = monaco.editor.create(containerRef.current, {
      automaticLayout: true,
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
      fontSize: 13,
      lineHeight: 21,
      minimap: { enabled: false },
      readOnly: true,
      scrollBeyondLastLine: false,
      theme,
      wordWrap: "on"
    });
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
      onSaveRef.current?.(snapshotFiles(filesRef.current, modelsRef.current));
    });
    editorRef.current = editor;

    return () => {
      editorRef.current?.dispose();
      editorRef.current = null;
      modelsRef.current.forEach((model) => model.dispose());
      modelsRef.current.clear();
    };
  }, []);

  useEffect(() => {
    const nextPaths = new Set(files.map((file) => file.path));
    modelsRef.current.forEach((model, path) => {
      if (!nextPaths.has(path)) {
        model.dispose();
        modelsRef.current.delete(path);
      }
    });

    files.forEach((file) => {
      const existing = modelsRef.current.get(file.path);
      const language = languageForFile(file);
      if (existing === undefined) {
        modelsRef.current.set(
          file.path,
          monaco.editor.createModel(file.content, language, monaco.Uri.from({ scheme: "learnloop", path: `/${file.path}` }))
        );
      } else {
        monaco.editor.setModelLanguage(existing, language);
        if (existing.getValue() !== file.content) {
          existing.setValue(file.content);
        }
      }
    });
  }, [files]);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) return;
    const selectedPath = activePath ?? files[0]?.path ?? null;
    const selectedModel = selectedPath === null ? null : modelsRef.current.get(selectedPath) ?? null;
    editor.setModel(selectedModel);
    editor.updateOptions({ readOnly: selectedPath === null ? true : filesByPath.get(selectedPath)?.readOnly ?? true });
  }, [activePath, files, filesByPath]);

  useEffect(() => {
    monaco.editor.setTheme(theme);
  }, [theme]);

  return <div className="code-editor-root" ref={containerRef} />;
}

function snapshotFiles(files: PracticeFileResponse[], models: Map<string, monaco.editor.ITextModel>): PracticeAttemptFileRequest[] {
  return files.map((file) => ({
    path: file.path,
    content: models.get(file.path)?.getValue() ?? file.content
  }));
}
