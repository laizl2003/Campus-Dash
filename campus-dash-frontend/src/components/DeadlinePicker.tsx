import { useEffect, useRef, useState } from 'react';
import Icon from './Icon';

/**
 * 截止时间选择器。
 *
 * 用原生 datetime-local 的三个问题：
 *   1. 用户可以选到过去的日期，提交时才报错；
 *   2. 只有输入框本身可点，外面的空白点了没反应；
 *   3. 没选之前输入框里挂着 "年/月/日 --:--" 这种占位串，很难看。
 *
 * 所以自己做一个：整栏可点 → 弹出日历 + 时分选择，过去日期直接置灰。
 */

/** 只保留年月日，便于比较"是否早于今天" */
const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();

const pad = (n: number) => String(n).padStart(2, '0');

function formatValue(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** "2026-11-15 18:00" -> "2026 年 11 月 15 日"，时间部分单独展示 */
function formatDateLabel(value: string): string {
  const [datePart] = value.split(' ');
  const [y, m, d] = datePart.split('-');
  if (!y || !m || !d) return datePart;
  return `${Number(y)} 年 ${Number(m)} 月 ${Number(d)} 日`;
}

interface Props {
  value: string;
  onChange: (value: string) => void;
  onError?: (message: string | null) => void;
  disabled?: boolean;
}

export default function DeadlinePicker({ value, onChange, onError, disabled }: Props) {
  const [open, setOpen] = useState(false);
  // 日历展示的月份，默认当月
  const [cursor, setCursor] = useState(() => {
    const base = value ? new Date(value.replace(' ', 'T')) : new Date();
    return new Date(base.getFullYear(), base.getMonth(), 1);
  });
  // 未选时间时默认 18:00，比 00:00 更符合"当天结束前"的直觉
  const [hour, setHour] = useState(() => (value ? Number(value.slice(11, 13)) : 18));
  const [minute, setMinute] = useState(() => (value ? Number(value.slice(14, 16)) : 0));
  const wrapRef = useRef<HTMLDivElement>(null);

  // 点外部关闭
  useEffect(() => {
    if (!open) return;
    const onDown = (event: MouseEvent) => {
      if (!wrapRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const today = startOfDay(new Date());
  const year = cursor.getFullYear();
  const month = cursor.getMonth();
  // 周一为一周起点
  const firstWeekday = (new Date(year, month, 1).getDay() + 6) % 7;
  const daysInMonth = new Date(year, month + 1, 0).getDate();
  const cells: (number | null)[] = [
    ...Array.from({ length: firstWeekday }, () => null),
    ...Array.from({ length: daysInMonth }, (_, i) => i + 1),
  ];

  const selectedDay = value ? startOfDay(new Date(value.replace(' ', 'T'))) : null;

  const pickDay = (day: number) => {
    const candidate = new Date(year, month, day, hour, minute, 0, 0);
    if (candidate.getTime() <= Date.now()) {
      onError?.('请选择当前时间之后的截止时间。');
      return;
    }
    onError?.(null);
    onChange(formatValue(candidate));
  };

  const applyTime = (nextHour: number, nextMinute: number) => {
    setHour(nextHour);
    setMinute(nextMinute);
    if (selectedDay === null) return;
    const day = new Date(selectedDay);
    const candidate = new Date(day.getFullYear(), day.getMonth(), day.getDate(), nextHour, nextMinute, 0, 0);
    if (candidate.getTime() <= Date.now()) {
      onError?.('请选择当前时间之后的截止时间。');
      return;
    }
    onError?.(null);
    onChange(formatValue(candidate));
  };

  // 不能翻到"当前月份之前"，否则整屏都是不可选日期
  const canPrev = startOfDay(cursor) > startOfDay(new Date(new Date().getFullYear(), new Date().getMonth(), 1));

  return (
    <div className={`deadline-picker${open ? ' is-open' : ''}${disabled ? ' is-disabled' : ''}`} ref={wrapRef}>
      {/* 整条都是触发器：用户点哪都能展开 */}
      <button
        type="button"
        className="deadline-trigger"
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => { setOpen((v) => !v); onError?.(null); }}
      >
        <span className="deadline-trigger-icon"><Icon name="calendar" size={16} /></span>
        <span className="deadline-trigger-text">
          {value
            ? <strong>{formatDateLabel(value)}</strong>
            : <span className="deadline-placeholder">选择一个截止日期</span>}
        </span>
        {value
          ? <span className="deadline-trigger-time">{value.slice(11, 16)}</span>
          : <span className="deadline-trigger-hint">选填 · 不选则永久有效</span>}
        <span className={`deadline-trigger-caret${open ? ' is-open' : ''}`}><Icon name="chevron-down" size={14} /></span>
      </button>

      {open && <div className="deadline-pop" role="dialog" aria-label="选择截止日期">
        <div className="deadline-pop-head">
          <button type="button" className="deadline-nav" disabled={!canPrev}
                  onClick={() => setCursor(new Date(year, month - 1, 1))} aria-label="上个月">
            <Icon name="arrow-left" size={14} />
          </button>
          <strong>{year} 年 {month + 1} 月</strong>
          <button type="button" className="deadline-nav"
                  onClick={() => setCursor(new Date(year, month + 1, 1))} aria-label="下个月">
            <Icon name="arrow-right" size={14} />
          </button>
        </div>

        <div className="deadline-weekdays">
          {['一', '二', '三', '四', '五', '六', '日'].map((d) => <span key={d}>{d}</span>)}
        </div>

        <div className="deadline-grid">
          {cells.map((day, index) => {
            if (day === null) return <span key={`gap-${index}`} className="deadline-day is-gap" />;
            const time = new Date(year, month, day).getTime();
            const isPast = time < today;
            const isToday = time === today;
            const isSelected = selectedDay === time;
            return (
              <button
                key={day}
                type="button"
                className={`deadline-day${isPast ? ' is-past' : ''}${isToday ? ' is-today' : ''}${isSelected ? ' is-selected' : ''}`}
                disabled={isPast}
                onClick={() => pickDay(day)}
              >{day}</button>
            );
          })}
        </div>

        <div className="deadline-time-row">
          <span className="deadline-time-label"><Icon name="clock" size={14} />时间</span>
          <select value={hour} onChange={(e) => applyTime(Number(e.target.value), minute)} aria-label="小时">
            {Array.from({ length: 24 }, (_, h) => <option key={h} value={h}>{pad(h)} 时</option>)}
          </select>
          <span className="deadline-time-sep">:</span>
          <select value={minute} onChange={(e) => applyTime(hour, Number(e.target.value))} aria-label="分钟">
            {Array.from({ length: 60 }, (_, m) => <option key={m} value={m}>{pad(m)} 分</option>)}
          </select>
        </div>

        <div className="deadline-pop-foot">
          <button type="button" className="btn btn-ghost"
                  onClick={() => { onChange(''); setOpen(false); onError?.(null); }}>清除</button>
          <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>完成</button>
        </div>
      </div>}
    </div>
  );
}
