import { useEffect, useState } from "react";

export type DatabaseConnection = {
  id: string;
  name: string;
  host: string;
  database: string;
  port: string;
  username: string;
};

type Props = {
  value: DatabaseConnection;
  onApply: (value: DatabaseConnection) => void;
  onClose: () => void;
};

export function DatabaseConnectionPanel({ value, onApply, onClose }: Props) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const set = (key: keyof DatabaseConnection, next: string) =>
    setDraft((current) => ({ ...current, [key]: next }));

  return (
    <aside className="config-panel" aria-label="Database connection editor">
      <div className="config-heading">
        <div><strong>Database Connection</strong><span>{value.name}</span></div>
        <button className="icon-button" type="button" aria-label="Close" onClick={onClose}>×</button>
      </div>
      {(["name", "host", "database", "port", "username"] as const).map((key) => (
        <label className="field" key={key}>
          <span>{key === "database" ? "Database" : key[0].toUpperCase() + key.slice(1)}</span>
          <input value={draft[key]} onChange={(event) => set(key, event.target.value)} />
        </label>
      ))}
      <div className="config-actions">
        <button type="button" onClick={onClose}>Cancel</button>
        <button className="primary" type="button" disabled={!draft.name.trim()} onClick={() => onApply(draft)}>Apply</button>
      </div>
    </aside>
  );
}
