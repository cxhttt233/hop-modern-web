import Editor from "@monaco-editor/react";

interface MonacoEditorProps {
  value: string;
  language?: string;
  onChange: (value: string) => void;
}

export function MonacoEditor({ value, language = "sql", onChange }: MonacoEditorProps) {
  return (
    <div className="monaco-field" aria-label={`${language} editor`}>
      <Editor
        height="100%"
        language={language}
        value={value}
        onChange={(next) => onChange(next ?? "")}
        options={{
          minimap: { enabled: false },
          fontSize: 13,
          lineNumbersMinChars: 3,
          scrollBeyondLastLine: false,
          automaticLayout: true,
          tabSize: 2,
        }}
      />
    </div>
  );
}
