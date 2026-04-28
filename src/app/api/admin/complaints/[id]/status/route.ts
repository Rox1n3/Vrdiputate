import { NextResponse } from 'next/server';
import { adminDb, adminMessaging } from '@/lib/firebase-admin';
import { readSession } from '@/lib/auth';

export async function PUT(req: Request, { params }: { params: { id: string } }) {
  const session = await readSession();
  if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

  const userDoc = await adminDb.collection('users').doc(session.uid).get();
  if (userDoc.data()?.role !== 'admin') {
    return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
  }

  const { uid, status } = await req.json();
  const validStatuses = ['processing', 'in_work', 'done', 'rejected'];
  if (!validStatuses.includes(status) || !uid) {
    return NextResponse.json({ error: 'Bad request' }, { status: 400 });
  }

  // Update status in Firestore
  await adminDb
    .collection('users')
    .doc(uid)
    .collection('complaints')
    .doc(params.id)
    .update({ status });

  // Send FCM push notification to the complaint owner
  try {
    const targetUserDoc = await adminDb.collection('users').doc(uid).get();
    const fcmToken: string | undefined = targetUserDoc.data()?.fcmToken;

    if (fcmToken) {
      await adminMessaging.send({
        token: fcmToken,
        // notification payload — FCM SDK (Google Play Services) показывает
        // уведомление на системном уровне даже если приложение убито
        notification: {
          title: 'Статус заявки изменён',
          body: 'Нажмите, чтобы открыть заявку',
        },
        // data payload — доступен в intent-extras при тапе на уведомление
        data: {
          complaint_id: params.id,
          status,
        },
        android: {
          priority: 'high',
          notification: {
            // канал, созданный AppFirebaseMessagingService
            channelId: 'status_updates',
            // при тапе откроется AnalyticsActivity (intent-filter в манифесте)
            clickAction: 'OPEN_ANALYTICS_ACTIVITY',
            sound: 'default',
          },
        },
      });
    }
  } catch (fcmError) {
    // FCM failure should not break the main status update
    console.error('FCM send error:', fcmError);
  }

  return NextResponse.json({ ok: true });
}
