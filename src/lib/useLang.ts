'use client';

import { useEffect, useState } from 'react';

type Lang = 'ru' | 'kk' | 'en';

export function useLang() {
  const [lang, setLangState] = useState<Lang>('ru');

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const stored = localStorage.getItem('lang') as Lang | null;
    if (stored) setLangState(stored);
  }, []);

  useEffect(() => {
    const handler = (event: Event) => {
      const detail = (event as CustomEvent).detail as Lang | undefined;
      if (detail) setLangState(detail);
    };
    window.addEventListener('lang-change', handler as EventListener);
    return () => window.removeEventListener('lang-change', handler as EventListener);
  }, []);

  const setLang = (value: Lang) => {
    setLangState(value);
    if (typeof window !== 'undefined') {
      localStorage.setItem('lang', value);
      window.dispatchEvent(new CustomEvent('lang-change', { detail: value }));
    }
  };

  return { lang, setLang } as const;
}
