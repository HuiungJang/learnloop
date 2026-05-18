import { useEffect, useMemo, useRef } from "react";
import * as monaco from "monaco-editor";
import type { PracticeFileResponse } from "../api/client";
import { languageForFile } from "./editorLanguages";
import "./monacoEnvironment";

type CodeEditorProps = {
  files: PracticeFileResponse[];
  activePath: string | null;
  theme: "vs" | "vs-dark";
};

export function CodeEditor({ files, activePath, theme }: CodeEditorProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const modelsRef = useRef<Map<string, monaco.editor.ITextModel>>(new Map());
  const filesByPath = useMemo(() => new Map(files.map((file) => [file.path, file])), [files]);

  useEffect(() => {
    if (containerRef.current === null || editorRef.current !== null) return;

    editorRef.current = monaco.editor.create(containerRef.current, {
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

    return () => {
      editorRef.current?.dispose();
      editorRef.current = null;
      modelsRef.current.forEach((model) => model.dispose());
      modelsRef.current.clear();
    };
  }, []);

  useEffect(() => {
    const editor = editorRef.current;
    if (editor === null) return;

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
