'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { useSession } from '@/lib/useSession';
import { createUserWithEmailAndPassword, updateProfile } from 'firebase/auth';
import { clientAuth } from '@/lib/firebase-client';

export default function RegisterPage() {
  const router = useRouter();
  const { bump, setUser } = useSession();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const credential = await createUserWithEmailAndPassword(clientAuth, email, password);
      await updateProfile(credential.user, { displayName: name });
      const idToken = await credential.user.getIdToken();
      const res = await fetch('/api/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken })
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Не удалось создать аккаунт');
      setUser(data.user || null);
      bump();
      router.push('/');
    } catch (err: any) {
      const code = err.code;
      if (code === 'auth/email-already-in-use') {
        setError('Такой email уже зарегистрирован');
      } else if (code === 'auth/weak-password') {
        setError('Пароль слишком короткий (мин. 6 символов)');
      } else {
        setError(err.message || 'Не удалось создать аккаунт');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="flex-1 flex items-center justify-center px-6 py-12">
      <div className="card w-full max-w-md p-8 space-y-6">
        <div className="space-y-2">
          <p className="text-xs text-slate-500 dark:text-slate-300 uppercase tracking-[0.3em]">Регистрация</p>
          <h1 className="text-2xl font-bold">Создайте аккаунт депутата/гражданина</h1>
          <p className="text-sm text-slate-600 dark:text-slate-300">История вопросов сохранится, ответы можно искать.</p>
        </div>
        <form className="space-y-4" onSubmit={handleSubmit}>
          <input className="input" placeholder="Имя" value={name} onChange={(e) => setName(e.target.value)} required />
          <input className="input" placeholder="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          <input
            className="input"
            placeholder="Пароль (мин. 6 символов)"
            type="password"
            value={password}
            minLength={6}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          {error && <p className="text-sm text-red-600">{error}</p>}
          <button type="submit" className="btn btn-primary w-full" disabled={loading}>
            {loading ? 'Создаём…' : 'Создать и войти'}
          </button>
        </form>
        <div className="flex items-center justify-between text-sm text-slate-600 dark:text-slate-300">
          <Link href="/login" className="text-steppe hover:underline">
            Уже есть аккаунт? Войти
          </Link>
          <Link href="/" className="text-slate-500 hover:text-steppe">
            Назад
          </Link>
        </div>
      </div>
    </main>
  );
}
