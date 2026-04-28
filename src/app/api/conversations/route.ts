import { NextResponse } from 'next/server';
import { FieldValue } from 'firebase-admin/firestore';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export type ConvMessage = {
  role: 'user' | 'assistant';
  text?: string;
  imageUrl?: string;
};

export async function GET(req: Request) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });

  const { searchParams } = new URL(req.url);
  const query = searchParams.get('query')?.toLowerCase().trim() ?? '';

  const snapshot = await adminDb
    .collection('users')
    .doc(session.uid)
    .collection('conversations')
    .orderBy('updatedAt', 'desc')
    .limit(50)
    .get();

  let items = snapshot.docs.map((doc) => {
    const data = doc.data();
    return {
      id: doc.id,
      messages: (data.messages ?? []) as ConvMessage[],
      title: (data.title as string) || '',
      startedAt: data.startedAt?.toDate?.()?.toISOString() ?? new Date().toISOString(),
      updatedAt: data.updatedAt?.toDate?.()?.toISOString() ?? new Date().toISOString(),
    };
  });

  if (query) {
    items = items.filter((conv) => {
      if (conv.title.toLowerCase().includes(query)) return true;
      return conv.messages.some((m) => m.text?.toLowerCase().includes(query));
    });
  }

  return NextResponse.json({ items });
}

export async function POST(req: Request) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });

  const { id, messages } = await req.json();
  if (!messages || !Array.isArray(messages) || messages.length === 0) {
    return NextResponse.json({ error: 'Пустые данные' }, { status: 400 });
  }

  const title =
    (messages as ConvMessage[]).find((m) => m.role === 'user')?.text?.slice(0, 100) ?? 'Разговор';

  const collRef = adminDb
    .collection('users')
    .doc(session.uid)
    .collection('conversations');

  if (id) {
    await collRef.doc(id).set(
      { messages, title, updatedAt: FieldValue.serverTimestamp() },
      { merge: true }
    );
    return NextResponse.json({ id, title, updatedAt: new Date().toISOString() });
  }

  const docRef = await collRef.add({
    messages,
    title,
    startedAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  });

  return NextResponse.json({
    id: docRef.id,
    title,
    startedAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
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
    .collection('conversations')
    .doc(id)
    .delete();

  return NextResponse.json({ ok: true });
}
