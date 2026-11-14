import { useEffect, useState } from "react";

interface Props {
  id: string;
  value: unknown;
  disabled?: boolean;
  onChange: (value: number) => void;
}

/**
 * Keep incomplete numeric text local until it becomes a finite number.
 * In particular, clearing an input to replace its contents must not write
 * undefined, zero or NaN to Hop's authoritative configuration.
 */
export function NativeScalarNumberInput({ id, value, disabled, onChange }: Props) {
  const originalText = value == null ? "" : String(value);
  const [draft, setDraft] = useState(originalText);
  useEffect(() => setDraft(originalText), [originalText]);
  const commit = () => {
    const trimmed = draft.trim();
    const number = trimmed === "" ? NaN : Number(trimmed);
    if (Number.isFinite(number)) {
      if (!Object.is(number, value)) onChange(number);
      setDraft(String(number));
    } else {
      setDraft(originalText);
    }
  };
  return <input id={id} disabled={disabled} type="number" step="any"
    value={draft} onChange={event => setDraft(event.target.value)}
    onBlur={commit} onKeyDown={event => {
      if (event.key === "Enter") {
        event.preventDefault();
        commit();
      } else if (event.key === "Escape") {
        event.preventDefault();
        setDraft(originalText);
        event.currentTarget.blur();
      }
    }} />;
}
