import { NextResponse } from 'next/server';
import { readSession } from '@/lib/auth';
import { adminDb } from '@/lib/firebase-admin';

export async function GET() {
  const session = await readSession();
  if (!session) {
    return NextResponse.json({ user: null });
  }

  const userDoc = await adminDb.collection('users').doc(session.uid).get();
  const role = userDoc.data()?.role || 'user';

  return NextResponse.json({ user: { ...session, role } });
}
