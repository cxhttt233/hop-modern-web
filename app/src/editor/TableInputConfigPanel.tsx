import { useEffect, useState } from "react";
import { MetadataPicker, type MetadataOption } from "./MetadataPicker";

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
}

export function TableInputConfigPanel({
  transformName,
  value,
  connections,
  onApply,
  onClose,
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
      <label className="field sql-field">
        <span>SQL</span>
        <textarea
          aria-label="SQL"
          spellCheck={false}
          value={draft.sql}
          onChange={(event) => setDraft((current) => ({ ...current, sql: event.target.value }))}
        />
      </label>
      <div className="config-actions">
        <button type="button" onClick={onClose}>Cancel</button>
        <button className="primary" type="button" onClick={() => onApply(draft)}>Apply</button>
      </div>
    </aside>
  );
}
