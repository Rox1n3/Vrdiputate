import { NextResponse } from 'next/server';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function GET() {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });

  const snapshot = await adminDb
    .collection('users')
    .doc(session.uid)
    .collection('history')
    .orderBy('createdAt', 'desc')
    .limit(50)
    .get();

  const items = snapshot.docs.map((doc) => ({
    id: doc.id,
    question: (doc.data().question as string) || '',
    answer: (doc.data().answer as string) || '',
    lang: (doc.data().lang as string) || 'ru',
    createdAt: doc.data().createdAt?.toDate?.()?.toISOString() ?? new Date().toISOString(),
  }));

  return NextResponse.json({ items });
}
