import { useEffect, useState } from "react";
import { MetadataPicker, type MetadataOption } from "./MetadataPicker";
import { MonacoEditor } from "./MonacoEditor";

export interface TableInputConfig {
  connectionId: string;
  sql: string;
}

interface TableInputConfigPanelProps {
  transformName: string;
  value: TableInputConfig;
  connections: MetadataOption[];
  onApply: (value: TableInputConfig) => void;
  onClose: () => void;
  onEditConnection: (connectionId: string) => void;
}

export function TableInputConfigPanel({
  transformName,
  value,
  connections,
  onApply,
  onClose,
  onEditConnection,
}: TableInputConfigPanelProps) {
  const [draft, setDraft] = useState(value);

  useEffect(() => setDraft(value), [value]);

  return (
    <aside className="config-panel" aria-label={`${transformName} configuration`}>
      <div className="config-heading">
        <div>
          <strong>{transformName}</strong>
          <span>Table Input</span>
        </div>
        <button className="icon-button" type="button" onClick={onClose} aria-label="Close configuration">
          ×
        </button>
      </div>
      <MetadataPicker
        label="Database connection"
        value={draft.connectionId}
        options={connections}
        onChange={(connectionId) => setDraft((current) => ({ ...current, connectionId }))}
      />
      <button
        type="button"
        disabled={!draft.connectionId}
        onClick={() => onEditConnection(draft.connectionId)}
      >
        Edit connection
      </button>
      <div className="field sql-field">
        <span>SQL</span>
        <MonacoEditor value={draft.sql} onChange={(sql) => setDraft((current) => ({ ...current, sql }))} />
      </div>
      <div className="config-actions">
        <button type="button" onClick={onClose}>Cancel</button>
        <button className="primary" type="button" onClick={() => onApply(draft)}>Apply</button>
      </div>
    </aside>
  );
}
