'use client';

import { useEffect, useState, useCallback } from 'react';

export type SessionUser = { uid: string; email: string; name: string } | null;

let cacheUser: SessionUser = null;
const listeners = new Set<(u: SessionUser) => void>();

function notify(u: SessionUser) {
  cacheUser = u;
  listeners.forEach((fn) => fn(u));
}

export function useSession() {
  const [user, setUserState] = useState<SessionUser>(cacheUser);
  const [loading, setLoading] = useState(true);

  const setUser = (u: SessionUser) => {
    setUserState(u);
    notify(u);
  };

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/session');
      const data = await res.json();
      setUser(data.user || null);
    } catch (e) {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    listeners.add(setUserState);
    if (cacheUser === null) {
      load();
    } else {
      setUserState(cacheUser);
      setLoading(false);
    }
    return () => {
      listeners.delete(setUserState);
    };
  }, [load]);

  const logout = async () => {
    await fetch('/api/logout', { method: 'POST' });
    setUser(null);
  };

  const bump = () => load();

  return { user, loading, reload: load, logout, setUser, bump } as const;
}
