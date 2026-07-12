import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { SystemProfile } from '../api/client';

type Props = {
  systems: SystemProfile[];
  value: string;
  onChange: (systemId: string) => void;
  disabled?: boolean;
  label?: string;
};

export function SystemSelect({ systems, value, onChange, disabled = false, label = '系统' }: Props) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listId = useId();
  const selected = systems.find((system) => system.systemId === value);
  const filtered = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return systems;
    return systems.filter((system) => (system.name || system.systemId).toLowerCase().includes(keyword)
      || system.systemId.toLowerCase().includes(keyword));
  }, [query, systems]);

  useEffect(() => {
    const close = (event: MouseEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) closeMenu();
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);

  function closeMenu() {
    setOpen(false);
    setQuery('');
  }

  function select(systemId: string) {
    onChange(systemId);
    closeMenu();
    triggerRef.current?.focus();
  }

  return (
    <div className="system-select inline-field" ref={rootRef}>
      <span className="system-select-label">{label}</span>
      <button
        ref={triggerRef}
        type="button"
        className="system-select-trigger"
        aria-label={label}
        aria-expanded={open}
        aria-controls={listId}
        aria-haspopup="listbox"
        disabled={disabled}
        onClick={() => open ? closeMenu() : setOpen(true)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setOpen(true);
          }
          if (event.key === 'Escape') closeMenu();
        }}
      >
        <span>{selected?.name || selected?.systemId || '请选择系统'}</span>
        <span className={'system-select-chevron ' + (open ? 'open' : '')} aria-hidden="true" />
      </button>
      {open && (
        <div className="system-select-menu">
          <input
            autoFocus
            className="system-select-search"
            aria-label="搜索系统"
            placeholder="搜索系统"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => { if (event.key === 'Escape') { closeMenu(); triggerRef.current?.focus(); } }}
          />
          <div className="system-select-options" id={listId} role="listbox" aria-label="系统列表">
            {filtered.map((system) => (
              <button
                type="button"
                role="option"
                aria-selected={system.systemId === value}
                className={'system-select-option ' + (system.systemId === value ? 'selected' : '')}
                key={system.systemId}
                onClick={() => select(system.systemId)}
              >
                <span>{system.name || system.systemId}</span>
                {system.systemId === value && <span className="system-select-check" aria-hidden="true">✓</span>}
              </button>
            ))}
            {filtered.length === 0 && <div className="system-select-empty">未找到系统</div>}
          </div>
        </div>
      )}
    </div>
  );
}

export function firstSystemId(systems: SystemProfile[]) {
  // 系统列表来自后端权限过滤结果，默认选第一项即可。
  return systems[0]?.systemId ?? '';
}
