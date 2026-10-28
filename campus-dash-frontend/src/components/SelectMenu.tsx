import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { createPortal } from 'react-dom';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

/** 与账号切换同款的下拉选择器；菜单用 fixed 浮层渲染，避免被父级 overflow 裁切 */
export default function SelectMenu({ value, options, onChange, ariaLabel, className = '' }: {
  value: string;
  options: SelectOption[];
  onChange: (value: string) => void;
  ariaLabel: string;
  className?: string;
}) {
  const [open, setOpen] = useState(false);
  const [menuStyle, setMenuStyle] = useState<CSSProperties>({});
  const wrapRef = useRef<HTMLDivElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (wrapRef.current?.contains(target) || menuRef.current?.contains(target)) return;
      setOpen(false);
    };
    const close = () => setOpen(false);
    const onScroll = (event: Event) => {
      // 菜单自身滚动（滚轮 / 拖动滚动条）不关闭；只关闭页面滚动
      if (menuRef.current?.contains(event.target as Node)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    window.addEventListener('resize', close);
    window.addEventListener('scroll', onScroll, true);
    return () => {
      document.removeEventListener('mousedown', onDown);
      window.removeEventListener('resize', close);
      window.removeEventListener('scroll', onScroll, true);
    };
  }, [open]);

  const toggle = () => {
    if (open) { setOpen(false); return; }
    const rect = wrapRef.current?.getBoundingClientRect();
    if (!rect) return;
    const spaceBelow = window.innerHeight - rect.bottom;
    const width = Math.max(rect.width, 200);
    const style: CSSProperties = { position: 'fixed', left: rect.left, width };
    if (spaceBelow < 220 && rect.top > spaceBelow) {
      style.bottom = window.innerHeight - rect.top + 6;
      style.top = 'auto';
    } else {
      style.top = rect.bottom + 6;
      style.bottom = 'auto';
    }
    setMenuStyle(style);
    setOpen(true);
  };

  const current = options.find(option => option.value === value);

  return <div className={`select-menu-wrap ${className}`} ref={wrapRef}>
    <button type="button" className="select-menu-trigger" aria-haspopup="listbox" aria-expanded={open}
            aria-label={ariaLabel} onClick={toggle}>
      <span>{current?.label ?? '请选择'}</span>
      <span className="select-menu-caret" aria-hidden="true">⌄</span>
    </button>
    {open && createPortal(<div ref={menuRef} className="account-menu select-menu" style={menuStyle}
                                role="listbox" aria-label={ariaLabel}>
      {options.map(option => <button key={option.value} type="button" role="option"
              aria-selected={option.value === value} disabled={option.disabled}
              className={option.value === value ? 'is-current' : ''}
              onClick={() => { setOpen(false); if (option.value !== value) onChange(option.value); }}>
        <span className="menu-account"><strong>{option.label}</strong></span>
        {option.value === value && <span className="select-menu-check">✓</span>}
      </button>)}
    </div>, document.body)}
  </div>;
}
