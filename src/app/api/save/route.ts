import { NextResponse } from 'next/server';
import { FieldValue } from 'firebase-admin/firestore';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function POST(req: Request) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });
  const { question, answer, lang = 'ru' } = await req.json();
  if (!question || !answer) return NextResponse.json({ error: 'Пустые данные' }, { status: 400 });

  const docRef = await adminDb
    .collection('users')
    .doc(session.uid)
    .collection('history')
    .add({ question, answer, lang, createdAt: FieldValue.serverTimestamp() });

  return NextResponse.json({
    record: { id: docRef.id, question, answer, lang, createdAt: new Date().toISOString() }
  });
}

export async function DELETE(req: Request) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });
  const { searchParams } = new URL(req.url);
  const id = searchParams.get('id');
  if (!id) return NextResponse.json({ error: 'Нет id' }, { status: 400 });

  await adminDb
    .collection('users')
    .doc(session.uid)
    .collection('history')
    .doc(id)
    .delete();

  return NextResponse.json({ ok: true });
}
