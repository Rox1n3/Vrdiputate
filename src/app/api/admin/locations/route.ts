import { NextResponse } from 'next/server';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function GET() {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

  const userDoc = await adminDb.collection('users').doc(session.uid).get();
  if (userDoc.data()?.role !== 'admin') {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  // Проверяем, является ли пользователь депутатом с ограничением по улицам
  const deputyDoc = await adminDb.collection('deputies').doc(session.uid).get();
  const deputyStreets: string[] = deputyDoc.exists
    ? ((deputyDoc.data()?.streets as string[]) || [])
    : [];

  const snap = await adminDb.collection('locations').orderBy('createdAt', 'desc').limit(500).get();

  const locations = snap.docs
    .map(doc => {
      const d = doc.data();
      return {
        id: doc.id,
        lat: d.lat,
        lng: d.lng,
        address: d.address || '',
        description: d.description || '',
        complaintId: d.complaintId || '',
        uid: d.uid || '',
        createdAt: d.createdAt?.toDate?.()?.toISOString() || null,
      };
    })
    .filter(loc => {
      // Главный администратор (не депутат) видит все точки
      if (deputyStreets.length === 0) return true;
      // Депутат видит только точки, адрес которых содержит его улицы
      const addrLower = loc.address.toLowerCase();
      return deputyStreets.some(s => addrLower.includes(s.toLowerCase()));
    });

  return NextResponse.json({ locations });
}
