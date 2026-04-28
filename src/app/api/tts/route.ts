import { getOpenAIClient } from '@/lib/openai';

type Lang = 'ru' | 'kk' | 'en';

const voiceByLang: Record<Lang, string> = {
  kk: 'verse',
  ru: 'onyx',
  en: 'alloy'
};

const normalizeLang = (value: unknown): Lang => {
  if (value === 'kk' || value === 'en') return value;
  return 'ru';
};

export async function POST(req: Request) {
  try {
    const body = await req.json().catch(() => ({}));
    const text = typeof body?.text === 'string' ? body.text.trim() : '';
    const lang = normalizeLang(body?.lang);

    if (!text) {
      return Response.json({ error: 'Empty text' }, { status: 400 });
    }

    const limitedText = text.slice(0, 4000);

    const openai = getOpenAIClient();
    const response = await openai.audio.speech.create({
      model: 'gpt-4o-mini-tts',
      voice: voiceByLang[lang] ?? 'verse',
      input: limitedText,
      response_format: 'mp3'
    });

    const headers = {
      'Content-Type': 'audio/mpeg',
      'Cache-Control': 'no-store',
    };

    // Stream bytes directly to client — first audio chunk plays as soon as
    // OpenAI sends it, no need to wait for the full file to be generated.
    if (response.body) {
      return new Response(response.body as ReadableStream, { status: 200, headers });
    }

    // Fallback for runtimes where body streaming is unavailable.
    const buffer = Buffer.from(await response.arrayBuffer());
    return new Response(buffer, { status: 200, headers });
  } catch (err) {
    console.error('[api/tts] failed:', err);
    return Response.json({ error: 'TTS unavailable' }, { status: 502 });
  }
}
