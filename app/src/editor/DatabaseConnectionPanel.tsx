import { useEffect, useState } from "react";

/** Browser draft using DatabaseMeta/BaseDatabaseMeta transport property paths. */
export type DatabaseConnection = {
  name: string;
  rdbms: {
    hostname: string;
    databaseName: string;
    port: string;
    username: string;
    password?: string;
    manualUrl?: string;
  };
};

type Props = {
  value: DatabaseConnection;
  onApply: (value: DatabaseConnection) => void;
  onClose: () => void;
};

export function DatabaseConnectionPanel({ value, onApply, onClose }: Props) {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);
  const setName = (name: string) => setDraft((current) => ({ ...current, name }));
  const setRdbms = (key: keyof DatabaseConnection["rdbms"], next: string) =>
    setDraft((current) => ({ ...current, rdbms: { ...current.rdbms, [key]: next } }));

  const fields: Array<[keyof DatabaseConnection["rdbms"], string]> = [
    ["hostname", "Host name"],
    ["databaseName", "Database name"],
    ["port", "Port"],
    ["username", "User name"],
    ["manualUrl", "JDBC URL"],
  ];

  return (
    <aside className="config-panel" aria-label="Database connection editor">
      <div className="config-heading">
        <div><strong>Database Connection</strong><span>{value.name}</span></div>
        <button className="icon-button" type="button" aria-label="Close" onClick={onClose}>×</button>
      </div>
      <label className="field">
        <span>Connection name</span>
        <input value={draft.name} onChange={(event) => setName(event.target.value)} />
      </label>
      {fields.map(([key, label]) => (
        <label className="field" key={key}>
          <span>{label}</span>
          <input value={draft.rdbms[key] ?? ""} onChange={(event) => setRdbms(key, event.target.value)} />
        </label>
      ))}
      <div className="config-actions">
        <button type="button" onClick={onClose}>Cancel</button>
        <button className="primary" type="button" disabled={!draft.name.trim()} onClick={() => onApply(draft)}>Apply</button>
      </div>
    </aside>
  );
}
