import { NextResponse } from 'next/server';
import { adminDb } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';
import { addressMatchesDistricts } from '@/lib/deputy-filter';
import type { DeputyDistrict } from '@/lib/deputy-filter';

export async function GET() {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

  const userDoc = await adminDb.collection('users').doc(session.uid).get();
  if (userDoc.data()?.role !== 'admin') {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  // Проверяем, является ли пользователь депутатом с привязкой к районам
  const deputyDoc = await adminDb.collection('deputies').doc(session.uid).get();

  // Точный список улиц + домов (новый формат)
  const deputyDistricts: DeputyDistrict[] = deputyDoc.exists
    ? ((deputyDoc.data()?.districts as DeputyDistrict[]) || [])
    : [];

  // Устаревший формат (только названия улиц) — используется как запасной вариант
  const deputyStreets: string[] = deputyDoc.exists
    ? ((deputyDoc.data()?.streets as string[]) || [])
    : [];

  const snap = await adminDb.collectionGroup('complaints').limit(500).get();

  const complaints = snap.docs
    .map(doc => {
      const data = doc.data();
      // путь: users/{uid}/complaints/{docId}
      const uid = doc.ref.path.split('/')[1];
      return {
        id: doc.id,
        uid,
        fio: data.fio || '',
        phone: data.phone || '',
        problem: data.problem || '',
        address: data.address || '',
        status: data.status || 'processing',
        complaintNumber: data.complaintNumber || 0,
        lang: data.lang || 'ru',
        lat: data.lat ?? null,
        lng: data.lng ?? null,
        messages: data.messages || [],
        createdAt: data.createdAt?.toDate?.()?.toISOString() ?? null,
      };
    })
    .filter(c => {
      // Главный администратор (без документа депутата) видит все заявки
      if (!deputyDoc.exists) return true;

      const addr = c.address;

      // Новый точный фильтр: улица + конкретный номер дома
      if (deputyDistricts.length > 0) {
        return addressMatchesDistricts(addr, deputyDistricts);
      }

      // Устаревший фильтр (только по названию улицы) — запасной вариант
      if (deputyStreets.length > 0) {
        const addrLower = addr.toLowerCase();
        return deputyStreets.some(s => addrLower.includes(s.toLowerCase()));
      }

      // Депутат без привязки улиц — ничего не показываем
      return false;
    })
    .sort((a, b) => {
      if (!a.createdAt && !b.createdAt) return 0;
      if (!a.createdAt) return 1;
      if (!b.createdAt) return -1;
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime();
    });

  return NextResponse.json({ complaints });
}
