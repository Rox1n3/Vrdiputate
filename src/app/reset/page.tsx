'use client';

import { useState } from 'react';
import { sendPasswordResetEmail } from 'firebase/auth';
import { clientAuth } from '@/lib/firebase-client';

export default function ResetPage() {
  const [email, setEmail] = useState('');
  const [message, setMessage] = useState('');
  const [loading, setLoading] = useState(false);

  const requestReset = async () => {
    setLoading(true);
    setMessage('');
    try {
      await sendPasswordResetEmail(clientAuth, email);
      setMessage('Ссылка для сброса пароля отправлена на ваш email.');
    } catch (e: any) {
      if (e.code === 'auth/user-not-found') {
        // Не раскрываем наличие пользователя
        setMessage('Если email существует, ссылка отправлена.');
      } else {
        setMessage(e.message || 'Не удалось отправить письмо.');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="flex-1 flex items-center justify-center px-4 py-12">
      <div className="card w-full max-w-md p-6 space-y-4">
        <h1 className="text-xl font-bold">Восстановление пароля</h1>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          Введите email — Firebase отправит письмо со ссылкой для сброса пароля.
        </p>
        <input
          className="input"
          placeholder="Email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
        <button
          className="btn btn-primary w-full"
          onClick={requestReset}
          disabled={!email || loading}
        >
          {loading ? '...' : 'Отправить ссылку'}
        </button>
        {message && <p className="text-sm text-slate-600 dark:text-slate-200">{message}</p>}
      </div>
    </main>
  );
}
