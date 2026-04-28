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

  // Только главный админ получает статистику — депутаты не видят эту вкладку
  const deputyDoc = await adminDb.collection('deputies').doc(session.uid).get();
  if (deputyDoc.exists) {
    return NextResponse.json({ isMainAdmin: false, rows: [] });
  }

  // Получаем всех депутатов
  const deputiesSnap = await adminDb.collection('deputies').get();
  const deputies = deputiesSnap.docs.map(doc => ({
    uid: doc.id,
    name: (doc.data().name as string) || '',
    okrug: (doc.data().okrug as number) || 0,
    streets: (doc.data().streets as string[]) || [],
  }));

  // Получаем все заявки
  const complaintsSnap = await adminDb.collectionGroup('complaints').get();
  const complaints = complaintsSnap.docs.map(doc => ({
    address: (doc.data().address as string) || '',
    status:  (doc.data().status  as string) || 'processing',
  }));

  // Считаем статистику по каждому депутату
  const rows = deputies.map(dep => {
    const matching = complaints.filter(c => {
      const addrLower = c.address.toLowerCase();
      return dep.streets.some(s => addrLower.includes(s.toLowerCase()));
    });
    return {
      name:   dep.name,
      okrug:  dep.okrug,
      total:  matching.length,
      done:   matching.filter(c => c.status === 'done').length,
    };
  });

  rows.sort((a, b) => a.okrug - b.okrug);

  return NextResponse.json({ isMainAdmin: true, rows });
}
