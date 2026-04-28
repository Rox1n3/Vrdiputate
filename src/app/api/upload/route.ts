import { NextRequest, NextResponse } from 'next/server';
import { readSession } from '@/lib/auth';
import { adminStorage } from '@/lib/firebase-admin';

/**
 * POST /api/upload
 * Принимает мультипарт-запрос с файлом изображения, загружает его в Firebase Storage
 * через Admin SDK (минуя security rules), возвращает публичный download URL.
 *
 * Используется Android-приложением вместо прямой загрузки в Firebase Storage,
 * чтобы обойти возможные ограничения Storage security rules.
 *
 * Тело запроса: multipart/form-data с полем "file" (binary) и "uid" (string)
 * Ответ: { url: "https://..." }
 */
export async function POST(req: NextRequest) {
  // Проверяем сессию (Firebase ID-token через заголовок Authorization)
  let uid: string | null = null;
  const authHeader = req.headers.get('Authorization');
  if (authHeader?.startsWith('Bearer ')) {
    try {
      const token = authHeader.slice(7);
      const decoded = await adminStorage.app.auth().verifyIdToken(token);
      uid = decoded.uid;
    } catch {
      // Неверный токен — пробуем через сессию
    }
  }

  if (!uid) {
    const session = await readSession();
    if (!session) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
    uid = session.uid;
  }

  let fileBuffer: Buffer;
  let mimeType = 'image/jpeg';
  let fileExt = 'jpg';

  const contentType = req.headers.get('content-type') || '';

  if (contentType.includes('multipart/form-data')) {
    // Мультипарт-форма
    const formData = await req.formData();
    const file = formData.get('file') as File | null;
    if (!file) return NextResponse.json({ error: 'No file' }, { status: 400 });
    fileBuffer = Buffer.from(await file.arrayBuffer());
    mimeType = file.type || 'image/jpeg';
    fileExt = mimeType === 'image/png' ? 'png' : mimeType === 'image/gif' ? 'gif' : 'jpg';
  } else {
    // Сырые байты в теле запроса (application/octet-stream или image/*)
    const arrayBuf = await req.arrayBuffer();
    fileBuffer = Buffer.from(arrayBuf);
    if (contentType.startsWith('image/')) {
      mimeType = contentType.split(';')[0].trim();
      fileExt = mimeType === 'image/png' ? 'png' : mimeType === 'image/gif' ? 'gif' : 'jpg';
    }
  }

  const fileName = `photos/${uid}/${Date.now()}.${fileExt}`;
  const bucket = adminStorage.bucket();
  const fileRef = bucket.file(fileName);

  // Загружаем файл с публичным доступом
  await fileRef.save(fileBuffer, {
    metadata: { contentType: mimeType },
    public: true,
  });

  // Возвращаем публичный URL
  const publicUrl = `https://storage.googleapis.com/${bucket.name}/${fileName}`;

  return NextResponse.json({ url: publicUrl });
}
