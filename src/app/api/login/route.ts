import { NextResponse } from 'next/server';
import { adminAuth } from '@/lib/firebase-admin';
import { setSessionCookie } from '@/lib/auth';

export async function POST(req: Request) {
  try {
    const { idToken } = await req.json();
    if (!idToken) return NextResponse.json({ error: 'Нет токена' }, { status: 400 });
    const decoded = await adminAuth.verifyIdToken(idToken);
    const uid = decoded.uid;
    const email = decoded.email ?? '';
    const name = (decoded.name ?? decoded.display_name ?? email) as string;
    await setSessionCookie(uid, email, name);
    return NextResponse.json({ user: { uid, email, name } });
  } catch {
    return NextResponse.json({ error: 'Ошибка авторизации' }, { status: 401 });
  }
}
