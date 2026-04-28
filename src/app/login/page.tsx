'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { useSession } from '@/lib/useSession';
import { signInWithEmailAndPassword } from 'firebase/auth';
import { clientAuth } from '@/lib/firebase-client';

export default function LoginPage() {
  const router = useRouter();
  const { bump, setUser } = useSession();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const credential = await signInWithEmailAndPassword(clientAuth, email, password);
      const idToken = await credential.user.getIdToken();
      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Не удалось войти');
      setUser(data.user || null);
      bump();
      router.push('/');
    } catch (err: any) {
      const code = err.code;
      if (code === 'auth/user-not-found' || code === 'auth/wrong-password' || code === 'auth/invalid-credential') {
        setError('Неверный email или пароль');
      } else if (code === 'auth/too-many-requests') {
        setError('Слишком много попыток. Попробуйте позже.');
      } else {
        setError(err.message || 'Ошибка входа');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="flex-1 flex items-center justify-center px-6 py-12">
      <div className="card w-full max-w-md p-8 space-y-6">
        <div className="space-y-2">
          <p className="text-xs text-slate-500 dark:text-slate-300 uppercase tracking-[0.3em]">Вход</p>
          <h1 className="text-2xl font-bold">Вернитесь к вашим диалогам</h1>
          <p className="text-sm text-slate-600 dark:text-slate-300">История и сохранённые ответы будут доступны.</p>
        </div>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <input className="input" placeholder="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          <input
            className="input"
            placeholder="Пароль"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {error && <p className="text-sm text-red-600">{error}</p>}
          <button type="submit" className="btn btn-primary w-full" disabled={loading}>
            {loading ? 'Проверяем…' : 'Войти'}
          </button>
        </form>
        <div className="flex items-center justify-between text-sm text-slate-600 dark:text-slate-300">
          <Link href="/register" className="text-steppe hover:underline">
            Создать аккаунт
          </Link>
          <Link href="/reset" className="text-slate-500 hover:text-steppe">
            Забыли пароль?
          </Link>
        </div>
      </div>
    </main>
  );
}
