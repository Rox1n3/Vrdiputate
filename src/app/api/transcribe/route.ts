import { NextRequest, NextResponse } from 'next/server';
import { getOpenAIClient } from '@/lib/openai';

// Map our interface language codes → Whisper BCP-47 language hints.
// Providing a hint dramatically improves accuracy for minority languages like Kazakh.
const whisperLang: Record<string, string> = {
  kk: 'kk', // Kazakh  — Whisper supports it natively
  ru: 'ru', // Russian
  en: 'en', // English
};

export async function POST(req: NextRequest) {
  try {
    const body = await req.formData();
    const audioFile = body.get('audio') as File | null;
    const lang = (body.get('lang') as string | null) ?? 'ru';

    if (!audioFile || audioFile.size < 100) {
      return NextResponse.json({ error: 'No audio provided' }, { status: 400 });
    }

    const openai = getOpenAIClient();

    // Convert Web File to Buffer for the OpenAI SDK (Node.js environment)
    const arrayBuffer = await audioFile.arrayBuffer();
    const buffer = Buffer.from(arrayBuffer);

    // Determine file extension from MIME type
    const ext = audioFile.type.includes('mp4') ? 'mp4'
      : audioFile.type.includes('ogg') ? 'ogg'
      : 'webm';

    // Create a proper File object for the OpenAI SDK
    const file = new File([buffer], `recording.${ext}`, { type: audioFile.type || 'audio/webm' });

    const result = await openai.audio.transcriptions.create({
      file,
      model: 'whisper-1',
      // Providing the language hint improves accuracy and reduces latency
      ...(whisperLang[lang] ? { language: whisperLang[lang] } : {}),
    });

    return NextResponse.json({ text: result.text ?? '' });
  } catch (err) {
    console.error('[api/transcribe] error:', err);
    return NextResponse.json({ error: 'Transcription failed' }, { status: 500 });
  }
}
