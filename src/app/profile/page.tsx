'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useSession } from '@/lib/useSession';

export default function ProfilePage() {
  const { user, loading, reload, logout } = useSession();
  const router = useRouter();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!loading && !user) router.replace('/login');
  }, [loading, user, router]);

  const changePassword = async () => {
    setMessage('');
    try {
      const res = await fetch('/api/profile/password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ current, next })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Не удалось обновить пароль');
      setMessage('Пароль обновлён');
      setCurrent('');
      setNext('');
    } catch (e: any) {
      setMessage(e.message);
    }
  };

  if (loading || !user) return null;

  return (
    <main className="flex-1 px-4 py-10 max-w-3xl mx-auto space-y-6">
      <div className="card p-6 space-y-3">
        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm text-slate-500 dark:text-slate-300">Профиль</div>
            <div className="text-xl font-semibold text-slate-900 dark:text-slate-100">{user.name}</div>
            <div className="text-sm text-slate-500 dark:text-slate-200">{user.email}</div>
          </div>
          <button className="btn btn-ghost text-slate-600 dark:text-slate-200 hover:text-slate-900 dark:hover:text-white" onClick={async () => { await logout(); router.replace('/'); }}>
            Выйти
          </button>
        </div>
      </div>

      <div className="card p-6 space-y-4">
        <div>
          <div className="text-sm font-semibold text-slate-900 dark:text-slate-100">Смена пароля</div>
          <p className="text-xs text-slate-500 dark:text-slate-300">Укажите текущий и новый пароль.</p>
        </div>
        <input className="input" type="password" placeholder="Текущий пароль" value={current} onChange={(e) => setCurrent(e.target.value)} />
        <input className="input" type="password" placeholder="Новый пароль" value={next} onChange={(e) => setNext(e.target.value)} />
        <button className="btn btn-primary w-full" onClick={changePassword} disabled={!current || !next}>
          Обновить пароль
        </button>
        {message && <p className="text-sm text-slate-600 dark:text-slate-200">{message}</p>}
      </div>

    </main>
  );
} 