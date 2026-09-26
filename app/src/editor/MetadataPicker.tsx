export interface MetadataOption {
  id: string;
  name: string;
}

interface MetadataPickerProps {
  label: string;
  value: string;
  options: MetadataOption[];
  onChange: (value: string) => void;
}

export function MetadataPicker({ label, value, options, onChange }: MetadataPickerProps) {
  const visibleOptions = value && !options.some((option) => option.id === value)
    ? [{ id: value, name: value }, ...options]
    : options;
  return (
    <label className="field">
      <span>{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Select connection</option>
        {visibleOptions.map((option) => (
          <option key={option.id} value={option.id}>
            {option.name}
          </option>
        ))}
      </select>
    </label>
  );
}
