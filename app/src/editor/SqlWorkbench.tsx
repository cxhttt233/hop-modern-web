import Editor, { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor";

// Bundle Monaco with the frontend instead of fetching executable editor code
// from a third-party CDN during a project configuration session.
loader.config({ monaco });

export interface SqlWorkbenchProps {
  value: string;
  disabled: boolean;
  onChange: (value: string) => void;
}

/** Optional inline SQL editor; the Hop server remains the source of truth. */
export default function SqlWorkbench({ value, disabled, onChange }: SqlWorkbenchProps) {
  return (
    <div className="config-sql-monaco" aria-label="SQL 专业编辑区域">
      <Editor
        height="260px"
        language="sql"
        theme="vs"
        value={value}
        onChange={(next) => onChange(next ?? "")}
        options={{
          ariaLabel: "SQL 专业编辑器",
          automaticLayout: true,
          minimap: { enabled: false },
          readOnly: disabled,
          fontSize: 13,
          lineNumbers: "on",
          wordWrap: "on",
          scrollBeyondLastLine: false,
          tabSize: 2,
        }}
      />
    </div>
  );
}
