'use client';

import { useEffect, useState } from 'react';

export type Theme = 'light' | 'dark';

export function useTheme() {
  const [theme, setThemeState] = useState<Theme>('light');

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const stored = localStorage.getItem('theme') as Theme | null;
    apply(stored || 'light');
  }, []);

  const apply = (value: Theme) => {
    setThemeState(value);
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = value;
      document.documentElement.classList.toggle('dark', value === 'dark');
    }
    if (typeof window !== 'undefined') localStorage.setItem('theme', value);
  };

  return { theme, setTheme: apply } as const;
}
