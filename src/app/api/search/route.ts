import { NextResponse } from 'next/server';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function GET(req: Request) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Нужен вход' }, { status: 401 });

  const { searchParams } = new URL(req.url);
  const query = searchParams.get('query')?.toLowerCase().trim() || '';

  // Forward to conversations with the query param
  const snapshot = await adminDb
    .collection('users')
    .doc(session.uid)
    .collection('conversations')
    .orderBy('updatedAt', 'desc')
    .limit(50)
    .get();

  const items = snapshot.docs
    .map((doc) => {
      const data = doc.data();
      return {
        id: doc.id,
        messages: (data.messages ?? []) as Array<{ role: string; text?: string; imageUrl?: string }>,
        title: (data.title as string) || '',
        startedAt: data.startedAt?.toDate?.()?.toISOString() ?? new Date().toISOString(),
        updatedAt: data.updatedAt?.toDate?.()?.toISOString() ?? new Date().toISOString(),
      };
    })
    .filter((conv) => {
      if (!query) return true;
      if (conv.title.toLowerCase().includes(query)) return true;
      return conv.messages.some((m) => m.text?.toLowerCase().includes(query));
    });

  return NextResponse.json({ items });
}
