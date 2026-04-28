import { NextResponse } from 'next/server';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function DELETE(
  request: Request,
  { params }: { params: { id: string } }
) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

  const userDoc = await adminDb.collection('users').doc(session.uid).get();
  if (userDoc.data()?.role !== 'admin') {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  const { searchParams } = new URL(request.url);
  const uid = searchParams.get('uid');
  if (!uid) return NextResponse.json({ error: 'uid required' }, { status: 400 });

  const complaintId = params.id;

  // Удаляем заявку из Firestore
  await adminDb
    .collection('users').doc(uid)
    .collection('complaints').doc(complaintId)
    .delete();

  // Удаляем связанные точки из коллекции locations
  const locSnap = await adminDb
    .collection('locations')
    .where('complaintId', '==', complaintId)
    .get();

  if (locSnap.docs.length > 0) {
    const batch = adminDb.batch();
    locSnap.docs.forEach(doc => batch.delete(doc.ref));
    await batch.commit();
  }

  return NextResponse.json({ ok: true });
}
