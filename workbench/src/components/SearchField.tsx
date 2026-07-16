import { Search, X } from 'lucide-react';

type Props = {
  value: string;
  label: string;
  placeholder: string;
  onChange: (value: string) => void;
};

export function SearchField({ value, label, placeholder, onChange }: Props) {
  return (
    <label className="search-field">
      <span className="sr-only">{label}</span>
      <Search size={16} aria-hidden="true" />
      <input aria-label={label} type="search" value={value} placeholder={placeholder} onChange={(event) => onChange(event.target.value)} />
      {value && <button type="button" aria-label={`清空${label}`} onClick={() => onChange('')}><X size={15} aria-hidden="true" /></button>}
    </label>
  );
}
