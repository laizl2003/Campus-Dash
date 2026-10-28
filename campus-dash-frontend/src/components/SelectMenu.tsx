import { useEffect, useRef, useState } from 'react';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

/** 与账号切换同款的下拉选择器，替代原生 select */
export default function SelectMenu({ value, options, onChange, ariaLabel, className = '' }: {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  ariaLabel: string;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const current = options.find(option => option.value === value);

  return <div className={`select-menu-wrap ${className}`} ref={wrapRef}>
    <button type="button" className="select-menu-trigger" aria-haspopup="listbox" aria-expanded={open}
            aria-label={ariaLabel} onClick={() => setOpen(v => !v)}>
      <span>{current?.label ?? '请选择'}</span>
      <span className="select-menu-caret" aria-hidden="true">⌄</span>
    </button>
    {open && <div className="account-menu select-menu" role="listbox" aria-label={ariaLabel}>
      {options.map(option => <button key={option.value} type="button" role="option"
              aria-selected={option.value === value} disabled={option.disabled}
              className={option.value === value ? 'is-current' : ''}
              onClick={() => { setOpen(false); if (option.value !== value) onChange(option.value); }}>
        <span className="menu-account"><strong>{option.label}</strong></span>
        {option.value === value && <span className="select-menu-check">✓</span>}
      </button>)}
    </div>}
  </div>;
}
