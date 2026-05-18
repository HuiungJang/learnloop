import { useEffect, useRef } from "react";
import * as monaco from "monaco-editor";
import { languageForFile } from "./editorLanguages";
import "./monacoEnvironment";

type PracticeAnswerDiffProps = {
  language: string;
  path: string;
  referenceAnswer: string;
  submittedAnswer: string;
  theme: "vs" | "vs-dark";
};

export function PracticeAnswerDiff({ language, path, referenceAnswer, submittedAnswer, theme }: PracticeAnswerDiffProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (containerRef.current === null) return;

    let editor: monaco.editor.IStandaloneDiffEditor | null = null;
    let originalModel: monaco.editor.ITextModel | null = null;
    let modifiedModel: monaco.editor.ITextModel | null = null;
    // Let React StrictMode cleanup cancel the first mount before Monaco starts diff work.
    const timeoutId = window.setTimeout(() => {
      if (containerRef.current === null) return;

      editor = monaco.editor.createDiffEditor(containerRef.current, {
        automaticLayout: true,
        diffAlgorithm: "legacy",
        fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
        fontSize: 13,
        lineHeight: 21,
        minimap: { enabled: false },
        originalEditable: false,
        readOnly: true,
        renderSideBySide: false,
        scrollBeyondLastLine: false,
        theme,
        wordWrap: "on"
      });
      const monacoLanguage = languageForFile({ path, language });
      const modelKey = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      originalModel = monaco.editor.createModel(
        referenceAnswer,
        monacoLanguage,
        monaco.Uri.from({ scheme: "learnloop-diff", path: `/reference/${modelKey}/${path}` })
      );
      modifiedModel = monaco.editor.createModel(
        submittedAnswer,
        monacoLanguage,
        monaco.Uri.from({ scheme: "learnloop-diff", path: `/submitted/${modelKey}/${path}` })
      );
      editor.setModel({ original: originalModel, modified: modifiedModel });
    }, 0);

    return () => {
      window.clearTimeout(timeoutId);
      editor?.dispose();
      originalModel?.dispose();
      modifiedModel?.dispose();
    };
  }, [language, path, referenceAnswer, submittedAnswer, theme]);

  return (
    <div className="answer-diff-shell">
      <div className="answer-diff-header">
        <strong>Answer diff</strong>
        <small>{path}</small>
      </div>
      <div aria-label="Submitted answer and reference answer diff" className="answer-diff-editor" ref={containerRef} />
    </div>
  );
}
