import { NextResponse } from 'next/server';
import { adminDb, adminAuth } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

async function checkAdmin() {
  const session = await readSession();
  if (!session) return null;
  const doc = await adminDb.collection('users').doc(session.uid).get();
  if (doc.data()?.role !== 'admin') return null;
  return session;
}

export async function GET() {
  const session = await checkAdmin();
  if (!session) return NextResponse.json({ error: 'Forbidden' }, { status: 403 });

  // Депутат-администратор не управляет пользователями — возвращаем пустой список
  const deputyDoc = await adminDb.collection('deputies').doc(session.uid).get();
  if (deputyDoc.exists) {
    return NextResponse.json({ users: [] });
  }

  // Берём список из Firebase Auth (там есть email и displayName)
  const listResult = await adminAuth.listUsers(1000);

  // Роли берём из Firestore
  const firestoreSnap = await adminDb.collection('users').get();
  const roles: Record<string, string> = {};
  firestoreSnap.docs.forEach(doc => {
    roles[doc.id] = (doc.data()?.role as string) || 'user';
  });

  const users = listResult.users.map(u => ({
    uid: u.uid,
    email: u.email || '',
    name: u.displayName || u.email || u.uid,
    role: roles[u.uid] || 'user',
    createdAt: u.metadata.creationTime || null,
  }));

  return NextResponse.json({ users });
}

export async function PUT(req: Request) {
  const session = await checkAdmin();
  if (!session) return NextResponse.json({ error: 'Forbidden' }, { status: 403 });

  const { uid, role } = await req.json();
  if (!uid || !['admin', 'user'].includes(role)) {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 });
  }

  await adminDb.collection('users').doc(uid).set({ role }, { merge: true });
  return NextResponse.json({ ok: true });
}
