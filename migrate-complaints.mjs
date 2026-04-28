// Скрипт миграции: копирует все заявки из users/{uid}/complaints → allComplaints
// Запуск: node migrate-complaints.mjs

import { initializeApp, cert } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { readFileSync } from 'fs';

// Читаем переменные из .env
const env = readFileSync('.env', 'utf8');
const get = (key) => {
  const match = env.match(new RegExp(`^${key}="?([^"\n]+)"?`, 'm'));
  return match ? match[1].replace(/\\n/g, '\n') : '';
};

initializeApp({
  credential: cert({
    projectId:   get('FIREBASE_PROJECT_ID'),
    clientEmail: get('FIREBASE_CLIENT_EMAIL'),
    privateKey:  get('FIREBASE_PRIVATE_KEY'),
  }),
});

const db = getFirestore();

async function migrate() {
  console.log('Получаем всех пользователей...');
  const usersSnap = await db.collection('users').get();
  console.log(`Найдено пользователей: ${usersSnap.size}`);

  let total = 0;
  let copied = 0;

  for (const userDoc of usersSnap.docs) {
    const uid = userDoc.id;
    const complaintsSnap = await db
      .collection('users')
      .doc(uid)
      .collection('complaints')
      .get();

    if (complaintsSnap.empty) continue;

    console.log(`  uid=${uid}: ${complaintsSnap.size} заявок`);

    for (const doc of complaintsSnap.docs) {
      const data = doc.data();
      total++;

      // Проверяем — не скопирована ли уже (по complaintId)
      const existing = await db
        .collection('allComplaints')
        .where('complaintId', '==', doc.id)
        .limit(1)
        .get();

      if (!existing.empty) {
        console.log(`    [пропуск] ${doc.id} уже есть`);
        continue;
      }

      await db.collection('allComplaints').add({
        uid,
        complaintId:     doc.id,
        fio:             data.fio             ?? '',
        phone:           data.phone           ?? '',
        problem:         data.problem         ?? '',
        address:         data.address         ?? '',
        lang:            data.lang            ?? 'ru',
        status:          data.status          ?? 'processing',
        complaintNumber: data.complaintNumber ?? 0,
        lat:             data.lat             ?? 0,
        lng:             data.lng             ?? 0,
        messages:        data.messages        ?? [],  // включая imageUrl фото
        createdAt:       data.createdAt       ?? null,
      });

      copied++;
      console.log(`    ✓ скопировано: ${doc.id} (${data.fio ?? '—'})`);
    }
  }

  console.log(`\nГотово! Скопировано ${copied} из ${total} заявок → коллекция allComplaints`);
}

migrate().catch(console.error);
