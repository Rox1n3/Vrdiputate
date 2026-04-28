'use client';

import { useEffect, useState, useCallback } from 'react';

export function useTts() {
  // Начинаем с false, но иконку показываем только после ready, чтобы избежать гидрации.
  const [enabled, setEnabled] = useState<boolean>(false);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const stored = localStorage.getItem('ttsEnabled');
    setEnabled(stored === '1');
    setReady(true);

    const onCustom = (e: Event) => {
      const detail = (e as CustomEvent).detail as boolean | undefined;
      if (typeof detail === 'boolean') setEnabled(detail);
    };
    const onStorage = (e: StorageEvent) => {
      if (e.key === 'ttsEnabled') setEnabled(e.newValue === '1');
    };
    window.addEventListener('tts-change', onCustom as EventListener);
    window.addEventListener('storage', onStorage);
    return () => {
      window.removeEventListener('tts-change', onCustom as EventListener);
      window.removeEventListener('storage', onStorage);
    };
  }, []);

  useEffect(() => {
    if (!ready || typeof window === 'undefined') return;
    localStorage.setItem('ttsEnabled', enabled ? '1' : '0');
    window.dispatchEvent(new CustomEvent('tts-change', { detail: enabled }));
  }, [enabled, ready]);

  // Если озвучка выключена — сразу останавливаем текущую речь.
  useEffect(() => {
    if (!ready) return;
    if (!enabled && typeof window !== 'undefined' && window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
  }, [enabled, ready]);

  const toggle = useCallback(() => setEnabled((v) => !v), []);

  return { enabled, toggle, setEnabled, ready } as const;
}
