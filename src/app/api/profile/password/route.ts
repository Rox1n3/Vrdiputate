import { NextResponse } from 'next/server';
import { adminAuth } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function POST(req: Request) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });
  const { next } = await req.json();
  if (!next || next.length < 6) {
    return NextResponse.json({ error: 'Пароль слишком короткий' }, { status: 400 });
  }
  try {
    await adminAuth.updateUser(session.uid, { password: next });
    return NextResponse.json({ ok: true });
  } catch {
    return NextResponse.json({ error: 'Не удалось обновить пароль' }, { status: 500 });
  }
}
