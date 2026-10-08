import { useEffect, useId, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { search } from '../market/api';
import styles from './SymbolSearch.module.css';

/** Header search: symbol or company name, debounced, keyboard navigable (combobox pattern). */
export function SymbolSearch() {
  const [text, setText] = useState('');
  const [term, setTerm] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const navigate = useNavigate();
  const listId = useId();

  useEffect(() => {
    const t = setTimeout(() => setTerm(text.trim()), 150);
    return () => clearTimeout(t);
  }, [text]);

  const results = useQuery({
    queryKey: ['search', term],
    queryFn: ({ signal }) => search(term, signal),
    enabled: term.length > 0,
    staleTime: 60_000,
  });
  const items = term.length > 0 ? (results.data ?? []).slice(0, 8) : [];

  function go(symbol: string) {
    setText('');
    setOpen(false);
    navigate(`/stocks/${symbol}`);
  }

  function onKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setActive((a) => Math.min(a + 1, items.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((a) => Math.max(a - 1, 0));
    } else if (e.key === 'Enter' && items[active]) {
      e.preventDefault();
      go(items[active].symbol);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  }

  return (
    <div className={styles.search}>
      <input className={styles.input} type="search" placeholder="Search" aria-label="Search stocks" role="combobox"
             aria-expanded={open && items.length > 0} aria-controls={listId} autoComplete="off" value={text}
             onChange={(e) => {
               setText(e.target.value);
               setOpen(true);
               setActive(0);
             }}
             onFocus={() => setOpen(true)} onBlur={() => setTimeout(() => setOpen(false), 150)} onKeyDown={onKeyDown} />
      {open && items.length > 0 && (
        <ul className={styles.results} role="listbox" id={listId}>
          {items.map((r, i) => (
            <li key={r.symbol} role="option" aria-selected={i === active}
                className={`${styles.result} ${i === active ? styles.active : ''}`}
                onMouseDown={(e) => e.preventDefault()} onClick={() => go(r.symbol)}>
              <span className={styles.symbol}>{r.symbol}</span>
              <span className={styles.name}>{r.name}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
