'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useLang } from '@/lib/useLang';
import { useSession } from '@/lib/useSession';
import { useTts } from '@/lib/useTts';

type SpeechRecognition = any;
type SpeechRecognitionEvent = any;
type SpeechRecognitionErrorEvent = any;

type Message = { role: 'user' | 'assistant'; content: string };
type Session = { uid: string; email: string; name: string } | null;
type ConvMessage = { role: 'user' | 'assistant'; text?: string; imageUrl?: string };
type SavedConversation = { id: string; messages: ConvMessage[]; title: string; startedAt: string; updatedAt: string };

/** Рендерит текст, превращая [текст](url) и голые https://... ссылки в кликабельные <a> */
function MessageContent({ text }: { text: string }) {
  const linkPattern = /\[([^\]]+)\]\((https?:\/\/[^\)]+)\)|https?:\/\/[^\s<>"]+/g;
  const parts: React.ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  let key = 0;

  while ((m = linkPattern.exec(text)) !== null) {
    if (m.index > last) parts.push(<span key={key++}>{text.slice(last, m.index)}</span>);
    const isMarkdown = m[0].startsWith('[');
    const href = isMarkdown ? m[2] : m[0];
    const label = isMarkdown ? m[1] : m[0];
    parts.push(
      <a key={key++} href={href} target="_blank" rel="noopener noreferrer"
         style={{ color: '#14B8A6', textDecoration: 'underline', wordBreak: 'break-all' }}>
        {label}
      </a>
    );
    last = m.index + m[0].length;
  }
  if (last < text.length) parts.push(<span key={key++}>{text.slice(last)}</span>);

  return <span style={{ whiteSpace: 'pre-wrap' }}>{parts}</span>;
}

const labels = {
  ru: {
    guest: 'Гостевой режим',
    user: 'Пользователь',
    placeholderGuest: 'Задайте вопрос. История не сохраняется.',
    placeholderUser: 'Задайте вопрос, ответы сохраняются в вашем кабинете.',
    typing: 'AI assistant печатает…',
    send: 'Спросить',
    speech: 'Говорить',
    stop: 'Стоп микрофон',
    saved: 'Сохранённые чаты',
    unavailable: 'Недоступно в гостевом режиме',
    searchPlaceholder: 'Поиск по чатам',
    empty: 'Пока нет сохранённых чатов.',
    historyHint: 'История и поиск доступны после входа как пользователь.',
    samplePrompts:
      'Спросите, например: «Объясни процедуру принятия закона», «Собери аргументы для выступления о бюджете», «Какие есть инициативы по транспорту в регионе?»',
    listening: 'Слушаю...',
    processing: 'Обработка...',
    tapToSpeak: 'Нажмите, чтобы говорить',
    error: 'Ошибка микрофона',
    retry: 'Повторить'
  },
  kk: {
    guest: 'Қонақ режимі',
    user: 'Пайдаланушы',
    placeholderGuest: 'Сұрағыңызды қойыңыз. Тарих сақталмайды.',
    placeholderUser: 'Сұрағыңызды қойыңыз, жауаптар кабинетте сақталады.',
    typing: 'AI assistant жауап дайындауда…',
    send: 'Жіберу',
    speech: 'Дауыспен',
    stop: 'Тоқтату',
    saved: 'Сақталған чаттар',
    unavailable: 'Қонақ режимінде қолжетімсіз',
    searchPlaceholder: 'Чаттар бойынша іздеу',
    empty: 'Әзірге сақталған чаттар жоқ.',
    historyHint: 'Тарих пен іздеу тек пайдаланушы ретінде кіргенде ашылады.',
    samplePrompts:
      'Мысалы сұраңыз: «Заң қабылдау тәртібін түсіндір», «Бюджет бойынша сөзге аргументтер жина», «Аймақтағы көлік бастамалары қандай?»',
    listening: 'Тыңдау...',
    processing: 'Өңдеу...',
    tapToSpeak: 'Басу арқылы сөйлеңіз',
    error: 'Микрофон қатесі',
    retry: 'Қайталау'
  },
  en: {
    guest: 'Guest mode',
    user: 'User',
    placeholderGuest: 'Ask a question. History is not saved.',
    placeholderUser: 'Ask a question; answers stay in your cabinet.',
    typing: 'AI assistant is typing…',
    send: 'Send',
    speech: 'Speak',
    stop: 'Stop mic',
    saved: 'Saved chats',
    unavailable: 'Unavailable in guest mode',
    searchPlaceholder: 'Search chats',
    empty: 'No saved chats yet.',
    historyHint: 'History & search are available after signing in.',
    samplePrompts:
      'Ask: “Explain the law-making procedure”, “Collect arguments for a budget speech”, “What transport initiatives exist in the region?”',
    listening: 'Listening...',
    processing: 'Processing...',
    tapToSpeak: 'Tap to speak',
    error: 'Microphone error',
    retry: 'Retry'
  }
};
export default function ChatPage({ searchParams }: { searchParams: { [key: string]: string | string[] | undefined } }) {
  const { lang } = useLang();
  const t = labels[lang];
  const { user } = useSession();
  const { enabled: ttsEnabled } = useTts();

  const initialGuest = useMemo(() => searchParams?.mode !== 'user', [searchParams]);
  const [isGuest, setIsGuest] = useState(initialGuest);
  const [session, setSession] = useState<Session>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [saved, setSaved] = useState<SavedConversation[]>([]);
  const [search, setSearch] = useState('');
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<SavedConversation | null>(null);
  const [playingId, setPlayingId] = useState<string | null>(null);
  const [currentConvId, setCurrentConvId] = useState<string | null>(null);
  
  // Speech recognition states
  const [speechState, setSpeechState] = useState<'idle' | 'listening' | 'processing' | 'error'>('idle');
  const [speechSupported, setSpeechSupported] = useState(false);
  const [speechError, setSpeechError] = useState<string | null>(null);
  const [audioLevel, setAudioLevel] = useState(0);
  
  const recognitionRef = useRef<SpeechRecognition | null>(null);
  const ttsLangRef = useRef<'ru' | 'kk' | 'en'>('ru');
  const finalTranscriptRef = useRef<string>('');
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const microphoneRef = useRef<MediaStream | null>(null);
  const animationFrameRef = useRef<number | null>(null);
  const isListeningRef = useRef(false);
  const isPressingRef = useRef(false);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const revokeUrlRef = useRef<string | null>(null);

  // MediaRecorder for Whisper transcription (high-quality, all languages)
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const audioChunksRef = useRef<Blob[]>([]);

  // Professional audio processing nodes
  const compressorRef = useRef<DynamicsCompressorNode | null>(null);
  const gainNodeRef = useRef<GainNode | null>(null);
  const noiseGateRef = useRef<GainNode | null>(null);
  const eqFiltersRef = useRef<BiquadFilterNode[]>([]);

  // Silence auto-stop: track when audio dropped below threshold
  const silenceStartRef = useRef<number | null>(null);
  // Whether Web Speech actually detected any speech in this recording session
  const speechDetectedRef = useRef(false);
  // Stable ref so updateAudioLevel can call handleMicToggle without circular deps
  const handleMicToggleRef = useRef<() => Promise<void>>(async () => {});

  const detectLang = (text: string): 'ru' | 'kk' | 'en' => {
    const lower = text.toLowerCase();

    // Kazakh-specific Cyrillic letters.
    if (/[әғқңөұүһі]/u.test(lower)) return 'kk';

    // Generic Cyrillic -> Russian by default.
    if (/\p{Script=Cyrillic}/u.test(lower)) return 'ru';

    if (/[a-z]/.test(lower)) return 'en';
    return 'ru';
  };

  const selectVoice = useCallback((lang: 'ru' | 'kk' | 'en') => {
    if (typeof window === 'undefined' || !window.speechSynthesis) return null;
    const voices = window.speechSynthesis.getVoices();
    if (!voices?.length) return null;

    const targetPrefix = lang === 'kk' ? 'kk' : lang === 'en' ? 'en' : 'ru';
    const byLang = voices.find((v) => v.lang?.toLowerCase().startsWith(targetPrefix));
    if (byLang) return byLang;

    if (lang === 'kk') {
      const byName = voices.find((v) => /kaza?k/i.test(v.name));
      if (byName) return byName;
    }

    const russianFallback = voices.find((v) => v.lang?.toLowerCase().startsWith('ru'));
    return russianFallback ?? voices[0] ?? null;
  }, []);

  const stopAudioPlayback = useCallback(() => {
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current = null;
    }
    if (revokeUrlRef.current) {
      URL.revokeObjectURL(revokeUrlRef.current);
      revokeUrlRef.current = null;
    }
  }, []);

  const playWithSpeechSynthesis = useCallback(
    (text: string, targetLang: 'ru' | 'kk' | 'en') => {
      if (typeof window === 'undefined' || !window.speechSynthesis) return;
      window.speechSynthesis.cancel();
      const utter = new SpeechSynthesisUtterance(text);
      const langTag = targetLang === 'kk' ? 'kk-KZ' : targetLang === 'en' ? 'en-US' : 'ru-RU';
      utter.lang = langTag;
      utter.rate = 1;
      utter.pitch = 1;
      utter.volume = 1;

      const speakWithVoice = () => {
        const voice = selectVoice(targetLang);
        if (voice) utter.voice = voice;
        window.speechSynthesis.speak(utter);
      };

      const availableVoices = window.speechSynthesis.getVoices();
      if (!availableVoices.length) {
        const handleVoicesChanged = () => {
          speakWithVoice();
          window.speechSynthesis.removeEventListener('voiceschanged', handleVoicesChanged);
        };
        window.speechSynthesis.addEventListener('voiceschanged', handleVoicesChanged);
        window.speechSynthesis.getVoices();
      } else {
        speakWithVoice();
      }
      return utter;
    },
    [selectVoice]
  );

  const playStreamedMp3 = useCallback(
    async (response: Response) => {
      if (typeof window === 'undefined' || !('MediaSource' in window)) return false;
      if (!response.body) return false;

      return new Promise<boolean>((resolve) => {
        try {
          const mediaSource = new MediaSource();
          const objectUrl = URL.createObjectURL(mediaSource);
          revokeUrlRef.current = objectUrl;
          const audio = new Audio(objectUrl);
          audio.preload = 'auto';
          audioRef.current = audio;
          audio.onended = stopAudioPlayback;

          mediaSource.addEventListener('sourceopen', () => {
            const sourceBuffer = mediaSource.addSourceBuffer('audio/mpeg');
            const reader = response.body!.getReader();
            let started = false;

            const pump = () => {
              reader.read().then(({ done, value }) => {
                if (done) {
                  sourceBuffer.addEventListener(
                    'updateend',
                    () => {
                      mediaSource.endOfStream();
                      resolve(started);
                    },
                    { once: true }
                  );
                  return;
                }

                sourceBuffer.appendBuffer(value);
                if (!started) {
                  started = true;
                  audio.play().catch(() => resolve(false));
                }
                sourceBuffer.addEventListener('updateend', pump, { once: true });
              }).catch(() => resolve(false));
            };

            pump();
          });
        } catch {
          resolve(false);
        }
      });
    },
    [stopAudioPlayback]
  );

  const playTts = useCallback(
    async (text: string, targetLang: 'ru' | 'kk' | 'en') => {
      if (!ttsEnabled || !text.trim()) return;
      if (typeof window === 'undefined') return;

      if (window.speechSynthesis) {
        window.speechSynthesis.cancel();
      }
      stopAudioPlayback();

      // Kazakh: high-quality server TTS only (streamed for minimal latency).
      if (targetLang === 'kk') {
        try {
          const res = await fetch('/api/tts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ text, lang: targetLang })
          });
          if (res.ok) {
            if ('MediaSource' in window && res.body) {
              await playStreamedMp3(res);
            } else {
              const blob = new Blob([await res.arrayBuffer()], { type: 'audio/mpeg' });
              const url = URL.createObjectURL(blob);
              revokeUrlRef.current = url;
              const audio = new Audio(url);
              audioRef.current = audio;
              audio.play().catch(() => {});
              audio.onended = stopAudioPlayback;
            }
          }
        } catch { /* ignore */ }
        return;
      }

      // Non-Kazakh: browser TTS for immediacy.
      playWithSpeechSynthesis(text, targetLang);
    },
    [playStreamedMp3, playWithSpeechSynthesis, stopAudioPlayback, ttsEnabled]
  );

  useEffect(() => {
    return () => {
      stopAudioPlayback();
      if (typeof window !== 'undefined' && window.speechSynthesis) {
        window.speechSynthesis.cancel();
      }
    };
  }, [stopAudioPlayback]);

  useEffect(() => {
    if (!ttsEnabled) {
      stopAudioPlayback();
      if (typeof window !== 'undefined' && window.speechSynthesis) {
        window.speechSynthesis.cancel();
      }
    }
  }, [stopAudioPlayback, ttsEnabled]);

  // Studio-grade audio processing chain
  const setupProfessionalAudioProcessing = useCallback((source: MediaStreamAudioSourceNode) => {
    if (!audioContextRef.current) return;

    const ctx = audioContextRef.current;
    
    // 1. High-pass filter (remove rumble and low-frequency noise)
    const highpassFilter = ctx.createBiquadFilter();
    highpassFilter.type = 'highpass';
    highpassFilter.frequency.value = 80;
    highpassFilter.Q.value = 0.7;
    
    // 2. Low-pass filter (remove high-frequency noise)
    const lowpassFilter = ctx.createBiquadFilter();
    lowpassFilter.type = 'lowpass';
    lowpassFilter.frequency.value = 8000;
    lowpassFilter.Q.value = 0.7;
    
    // 3. Parametric EQ for voice enhancement
    const eqFilters = [
      { freq: 120, gain: 2, Q: 1.2 },  // Boost low-mids for warmth
      { freq: 1000, gain: 1.5, Q: 1.5 }, // Boost presence
      { freq: 3000, gain: 2, Q: 1.8 },   // Boost clarity
      { freq: 6000, gain: -2, Q: 2 }     // Cut sibilance
    ];
    
    eqFiltersRef.current = eqFilters.map(({ freq, gain, Q }) => {
      const filter = ctx.createBiquadFilter();
      filter.type = 'peaking';
      filter.frequency.value = freq;
      filter.gain.value = gain;
      filter.Q.value = Q;
      return filter;
    });
    
    // 4. Compressor for dynamic range control
    compressorRef.current = ctx.createDynamicsCompressor();
    compressorRef.current.threshold.value = -18;
    compressorRef.current.knee.value = 15;
    compressorRef.current.ratio.value = 8;
    compressorRef.current.attack.value = 0.003;
    compressorRef.current.release.value = 0.2;
    
    // 5. Make-up gain
    gainNodeRef.current = ctx.createGain();
    gainNodeRef.current.gain.value = 2.2;
    
    // 6. Adaptive noise gate
    noiseGateRef.current = ctx.createGain();
    noiseGateRef.current.gain.value = 1;
    
    // Build processing chain
    let currentNode: AudioNode = source;
    
    // Apply filters in sequence
    currentNode.connect(highpassFilter);
    currentNode = highpassFilter;
    
    currentNode.connect(lowpassFilter);
    currentNode = lowpassFilter;
    
    eqFiltersRef.current.forEach(filter => {
      currentNode.connect(filter);
      currentNode = filter;
    });
    
    if (compressorRef.current) {
      currentNode.connect(compressorRef.current);
      currentNode = compressorRef.current;
    }
    
    if (gainNodeRef.current) {
      currentNode.connect(gainNodeRef.current);
      currentNode = gainNodeRef.current;
    }
    
    if (noiseGateRef.current) {
      currentNode.connect(noiseGateRef.current);
      currentNode = noiseGateRef.current;
    }
    
    if (analyserRef.current) {
      currentNode.connect(analyserRef.current);
    }
    
    // Intelligent noise gate that adapts to ambient noise
    const adaptNoiseGate = () => {
      if (!analyserRef.current || !isListeningRef.current || !noiseGateRef.current) return;
      
      const dataArray = new Uint8Array(analyserRef.current.frequencyBinCount);
      analyserRef.current.getByteFrequencyData(dataArray);
      
      // Calculate ambient noise floor
      const sum = dataArray.reduce((a, b) => a + b, 0);
      const avg = sum / dataArray.length;
      
      // Dynamic threshold - opens when speech detected, closes when quiet
      const threshold = 12;
      const targetGain = avg > threshold ? 1.0 : 0.05;
      noiseGateRef.current.gain.setTargetAtTime(targetGain, ctx.currentTime, 0.02);
      
      requestAnimationFrame(adaptNoiseGate);
    };
    
    adaptNoiseGate();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // Audio level visualization + silence-based auto-stop.
  // Uses only refs (no state) to avoid stale-closure issues.
  const updateAudioLevel = useCallback(() => {
    if (!analyserRef.current || !isListeningRef.current) {
      if (animationFrameRef.current) {
        cancelAnimationFrame(animationFrameRef.current);
        animationFrameRef.current = null;
      }
      return;
    }

    const dataArray = new Uint8Array(analyserRef.current.frequencyBinCount);
    analyserRef.current.getByteFrequencyData(dataArray);

    // RMS for accurate volume
    let sum = 0;
    for (let i = 0; i < dataArray.length; i++) {
      sum += dataArray[i] * dataArray[i];
    }
    const rms = Math.sqrt(sum / dataArray.length);
    const scaledLevel = Math.min(10, Math.floor((rms / 128) * 12));
    setAudioLevel(scaledLevel);

    // Silence auto-stop: if audio stays below threshold for 2.5 s → stop recording.
    // Only triggers after speech was actually detected, so we don't cut before the user speaks.
    const SILENCE_THRESHOLD = 2;
    const SILENCE_TIMEOUT_MS = 2500;
    if (scaledLevel < SILENCE_THRESHOLD) {
      if (silenceStartRef.current === null) silenceStartRef.current = Date.now();
      else if (speechDetectedRef.current && Date.now() - silenceStartRef.current > SILENCE_TIMEOUT_MS) {
        silenceStartRef.current = null;
        handleMicToggleRef.current(); // auto-stop, same as pressing button again
        return;
      }
    } else {
      silenceStartRef.current = null;
    }

    animationFrameRef.current = requestAnimationFrame(updateAudioLevel);
  }, []); // uses only refs — no state deps needed

  const stopListening = useCallback(async () => {
    console.log('Stopping listening...');
    
    if (animationFrameRef.current) {
      cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }

    if (microphoneRef.current) {
      microphoneRef.current.getTracks().forEach(track => track.stop());
      microphoneRef.current = null;
    }

    if (audioContextRef.current) {
      await audioContextRef.current.close();
      audioContextRef.current = null;
    }

    if (recognitionRef.current && isListeningRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {
        console.error('Error stopping recognition:', e);
      }
    }

    // Clean up audio nodes
    compressorRef.current = null;
    gainNodeRef.current = null;
    noiseGateRef.current = null;
    eqFiltersRef.current = [];
    analyserRef.current = null;
    
    isListeningRef.current = false;
    
    setSpeechState('idle');
    setAudioLevel(0);
  }, []);

  // Stops MediaRecorder and returns a Blob with the recorded audio.
  // Must be called BEFORE stopListening() so mic tracks are still open.
  const getRecordedAudio = useCallback((): Promise<Blob | null> => {
    return new Promise((resolve) => {
      const recorder = mediaRecorderRef.current;
      if (!recorder) { resolve(null); return; }

      if (recorder.state === 'inactive') {
        mediaRecorderRef.current = null;
        resolve(
          audioChunksRef.current.length > 0
            ? new Blob(audioChunksRef.current, { type: 'audio/webm' })
            : null
        );
        return;
      }

      recorder.addEventListener('stop', () => {
        mediaRecorderRef.current = null;
        resolve(
          audioChunksRef.current.length > 0
            ? new Blob(audioChunksRef.current, { type: recorder.mimeType || 'audio/webm' })
            : null
        );
      }, { once: true });

      try { recorder.stop(); } catch { resolve(null); }
    });
  }, []);

  const initializeProfessionalMicrophone = useCallback(async () => {
    try {
      // Professional audio constraints for maximum quality
      const stream = await navigator.mediaDevices.getUserMedia({ 
        audio: {
          channelCount: 1,
          sampleRate: 48000,
          sampleSize: 24,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          latency: 0.01
        } 
      });
      
      microphoneRef.current = stream;
      
      // Create high-quality audio context
      audioContextRef.current = new (window.AudioContext || (window as any).webkitAudioContext)({
        latencyHint: 'interactive',
        sampleRate: 48000
      });
      await audioContextRef.current.resume();
      
      // Create analyser for visualization
      analyserRef.current = audioContextRef.current.createAnalyser();
      analyserRef.current.fftSize = 512;
      analyserRef.current.smoothingTimeConstant = 0.2;
      analyserRef.current.minDecibels = -90;
      analyserRef.current.maxDecibels = -10;
      
      const source = audioContextRef.current.createMediaStreamSource(stream);
      
      // Apply professional audio processing
      setupProfessionalAudioProcessing(source);
      
      return true;
    } catch (error) {
      console.error('Microphone initialization failed:', error);
      setSpeechError('permission-denied');
      setSpeechState('error');
      return false;
    }
  }, [setupProfessionalAudioProcessing]);

  const startListening = useCallback(async () => {
    if (!speechSupported || isGuest) return;
    
    console.log('Starting professional recording...');
    
    setSpeechError(null);
    setSpeechState('listening');
    finalTranscriptRef.current = '';
    setInput('');
    isListeningRef.current = true;
    speechDetectedRef.current = false;
    silenceStartRef.current = null;
    
    try {
      const micInitialized = await initializeProfessionalMicrophone();
      if (!micInitialized) return;
      
      updateAudioLevel();

      // Start MediaRecorder to capture raw audio → will be sent to Whisper on stop
      if (microphoneRef.current && typeof MediaRecorder !== 'undefined') {
        try {
          const mimeType =
            MediaRecorder.isTypeSupported('audio/webm;codecs=opus') ? 'audio/webm;codecs=opus' :
            MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' :
            MediaRecorder.isTypeSupported('audio/mp4') ? 'audio/mp4' : '';
          audioChunksRef.current = [];
          const recorder = new MediaRecorder(
            microphoneRef.current,
            mimeType ? { mimeType } : undefined
          );
          recorder.ondataavailable = (e) => {
            if (e.data && e.data.size > 0) audioChunksRef.current.push(e.data);
          };
          recorder.start(500); // collect chunks every 500ms
          mediaRecorderRef.current = recorder;
        } catch (err) {
          console.warn('MediaRecorder unavailable, Whisper fallback disabled:', err);
        }
      }

      // Start speech recognition for live interim display (visual feedback only).
      if (recognitionRef.current) {
        const langTag = lang === 'kk' ? 'kk-KZ' : lang === 'en' ? 'en-US' : 'ru-RU';
        recognitionRef.current.lang = langTag;
        try {
          recognitionRef.current.start();
        } catch (e) {
          console.error('Recognition start error:', e);
        }
      }
    } catch (e) {
      console.error('Failed to start listening:', e);
      setSpeechError('start-failed');
      setSpeechState('error');
      isListeningRef.current = false;
    }
  }, [speechSupported, isGuest, lang, initializeProfessionalMicrophone, updateAudioLevel]);

  // Voice input helper — just places the transcript into the input field.
  // The user sends the message manually by pressing the send button.
  const placeVoiceText = useCallback((text: string) => {
    if (!text.trim()) return;
    setInput(text.trim());
  }, []);

  const handleSendWithText = useCallback(async (text: string) => {
    if (!text.trim()) return;

    // Stop any currently playing TTS immediately when the user sends a new message
    if (typeof window !== 'undefined' && window.speechSynthesis) {
      window.speechSynthesis.cancel();
    }
    stopAudioPlayback();
    setPlayingId(null);

    const prompt = text.trim();
    const promptLang = detectLang(prompt);
    const previousUserQuestion = [...messages].reverse().find((m) => m.role === 'user')?.content ?? '';
    const previousAssistantAnswer = [...messages].reverse().find((m) => m.role === 'assistant')?.content ?? '';
    ttsLangRef.current = promptLang;
    const userMessage: Message = { role: 'user', content: prompt };
    setMessages((prev) => [...prev, userMessage]);
    setInput('');
    setLoading(true);
    
    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          prompt,
          mode: isGuest ? 'guest' : 'user',
          lang: promptLang,
          context: {
            lastUserQuestion: previousUserQuestion,
            lastAssistantAnswer: previousAssistantAnswer
          }
        })
      });
      const data = await res.json().catch(() => ({}));
      const fallbackError = 'Извините, не удалось получить ответ.';
      const content =
        typeof data?.answer === 'string' && data.answer.trim()
          ? data.answer
          : typeof data?.error === 'string' && data.error.trim()
            ? data.error
            : fallbackError;
      const answer: Message = { role: 'assistant', content };
      setMessages((prev) => [...prev, answer]);

      // Detect language from the answer text itself, not the user's prompt
      const answerLang = detectLang(answer.content);
      playTts(answer.content, answerLang);
    } catch (error) {
      setMessages((prev) => [...prev, { role: 'assistant', content: 'Сервис временно недоступен.' }]);
    } finally {
      setLoading(false);
    }
  }, [isGuest, playTts, stopAudioPlayback, ttsEnabled, messages]);

  // Toggle: press once → start recording; press again → stop, transcribe via Whisper, put text in input.
  // Message is NOT sent automatically — the user clicks the send button themselves.
  const handleMicToggle = useCallback(async () => {
    if (!speechSupported || isGuest || speechState === 'processing') return;

    if (speechState === 'listening') {
      isPressingRef.current = false;

      // Keep Web Speech transcript as fallback in case Whisper fails
      const webSpeechFallback = finalTranscriptRef.current.trim() || input.trim();

      // 1. Stop MediaRecorder and collect audio BEFORE stopping the mic stream
      const audioBlob = await getRecordedAudio();

      // 2. Stop everything (mic, AudioContext, Web Speech)
      await stopListening();

      // 3. Try Whisper only when speech was actually detected (avoids hallucinations on silence)
      if (audioBlob && audioBlob.size > 2000 && speechDetectedRef.current) {
        setSpeechState('processing');
        try {
          const formData = new FormData();
          const ext = audioBlob.type.includes('mp4') ? 'mp4'
            : audioBlob.type.includes('ogg') ? 'ogg'
            : 'webm';
          formData.append('audio', new File([audioBlob], `rec.${ext}`, { type: audioBlob.type }));
          formData.append('lang', lang);

          const res = await fetch('/api/transcribe', { method: 'POST', body: formData });
          if (res.ok) {
            const data = await res.json();
            if (data.text?.trim()) {
              placeVoiceText(data.text.trim());
              setSpeechState('idle');
              return;
            }
          }
        } catch {
          // Network/API error — fall through to Web Speech result
        }
        setSpeechState('idle');
      }

      // 4. Fallback to Web Speech interim result
      if (webSpeechFallback) placeVoiceText(webSpeechFallback);

    } else {
      isPressingRef.current = true;
      startListening();
    }
  }, [speechSupported, isGuest, speechState, input, lang, getRecordedAudio, startListening, stopListening, placeVoiceText]);

  // Keep handleMicToggleRef in sync so updateAudioLevel can call it without circular deps
  useEffect(() => {
    handleMicToggleRef.current = handleMicToggle;
  }, [handleMicToggle]);

  // Initialize professional speech recognition
  useEffect(() => {
    const SpeechRecognitionImpl =
      (typeof window !== 'undefined' &&
        ((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition)) ||
      null;
    
    if (SpeechRecognitionImpl) {
      const rec: SpeechRecognition = new SpeechRecognitionImpl();
      
      // Professional recognition settings
      rec.continuous = true;
      rec.interimResults = true;
      rec.maxAlternatives = 10; // Maximum alternatives for best accuracy
      
      rec.onstart = () => {
        console.log('Professional recognition started');
      };
      
      rec.onresult = (event: SpeechRecognitionEvent) => {
        // Mark that real speech was detected — used to gate Whisper and silence auto-stop
        speechDetectedRef.current = true;
        // Reset silence timer when speech resumes
        silenceStartRef.current = null;

        let finalTranscript = finalTranscriptRef.current;
        let interimTranscript = '';

        for (let i = event.resultIndex; i < event.results.length; i++) {
          // Get the best alternative with highest confidence
          let bestTranscript = '';
          let bestConfidence = 0;

          for (let j = 0; j < event.results[i].length; j++) {
            if (event.results[i][j].confidence > bestConfidence) {
              bestConfidence = event.results[i][j].confidence;
              bestTranscript = event.results[i][j].transcript;
            }
          }

          if (event.results[i].isFinal) {
            finalTranscript += bestTranscript.charAt(0).toUpperCase() + bestTranscript.slice(1) + ' ';
          } else {
            interimTranscript += bestTranscript;
          }
        }

        finalTranscriptRef.current = finalTranscript;
        setInput(finalTranscript + interimTranscript);
      };
        
      rec.onerror = (event: SpeechRecognitionErrorEvent) => {
        console.error('Recognition error:', event.error);

        if (event.error === 'no-speech') {
          // Restart silently while user is still recording.
          if (isPressingRef.current && isListeningRef.current) {
            try { recognitionRef.current?.start(); } catch { }
          } else if (isListeningRef.current) {
            stopListening();
          }
        } else if (event.error === 'language-not-supported' || event.error === 'service-not-allowed') {
          // kk-KZ not supported in this browser — fall back to Russian model
          // (Kazakh Cyrillic is largely recognisable by the Russian engine).
          if (isPressingRef.current && isListeningRef.current && recognitionRef.current) {
            recognitionRef.current.lang = 'ru-RU';
            try { recognitionRef.current.start(); } catch { }
          }
        } else if (event.error === 'not-allowed') {
          setSpeechError('permission-denied');
          setSpeechState('error');
          stopListening();
        } else {
          // For other errors, restart if still recording, otherwise stop.
          if (isPressingRef.current && isListeningRef.current) {
            try { recognitionRef.current?.start(); } catch { }
          } else {
            setSpeechError('unknown');
            setSpeechState('error');
            stopListening();
          }
        }
      };

      rec.onend = () => {
        console.log('Professional recognition ended');
        // If user is still holding, restart recognition automatically.
        if (isPressingRef.current && isListeningRef.current) {
          try { recognitionRef.current?.start(); } catch { }
        } else {
          isListeningRef.current = false;
        }
      };
      
      recognitionRef.current = rec;
      setSpeechSupported(true);
    }
    
    return () => {
      if (recognitionRef.current) {
        try { recognitionRef.current.abort(); } catch { /* ignore */ }
      }
      if (microphoneRef.current) {
        microphoneRef.current.getTracks().forEach(track => track.stop());
      }
      if (mediaRecorderRef.current && mediaRecorderRef.current.state !== 'inactive') {
        try { mediaRecorderRef.current.stop(); } catch { /* ignore */ }
      }
    };
  }, [stopListening]);

  useEffect(() => {
    if (user) {
      setSession(user);
      setIsGuest(false);
    } else {
      setSession(null);
      setIsGuest(true);
      setSaved([]);
    }
  }, [user]);

  useEffect(() => {
    if (!session) return;
    fetch('/api/conversations')
      .then((res) => res.json())
      .then((data) => setSaved(data.items ?? []))
      .catch(() => {});
  }, [session]);

  const saveConversation = async () => {
    if (isGuest || messages.length === 0) return;
    const convMsgs: ConvMessage[] = messages.map((m) => ({ role: m.role, text: m.content }));
    try {
      const body: Record<string, unknown> = { messages: convMsgs };
      if (currentConvId) body.id = currentConvId;
      const res = await fetch('/api/conversations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const data = await res.json().catch(() => ({}));
      if (data.id) {
        setCurrentConvId(data.id);
        const newConv: SavedConversation = {
          id: data.id,
          messages: convMsgs,
          title: convMsgs.find((m) => m.role === 'user')?.text ?? 'Разговор',
          startedAt: data.startedAt ?? new Date().toISOString(),
          updatedAt: data.updatedAt ?? new Date().toISOString(),
        };
        setSaved((prev) => {
          const idx = prev.findIndex((c) => c.id === data.id);
          if (idx !== -1) {
            const updated = [...prev];
            updated[idx] = newConv;
            return updated;
          }
          return [newConv, ...prev].slice(0, 50);
        });
      }
    } catch {
      // ignore
    }
  };

  const playSavedTts = useCallback(
    async (item: SavedConversation) => {
      if (!ttsEnabled) return;
      // Find the last assistant text message to play
      const lastAssistant = [...item.messages].reverse().find((m) => m.role === 'assistant' && m.text);
      const textToPlay = lastAssistant?.text ?? '';
      if (!textToPlay) return;

      if (playingId === item.id) {
        stopAudioPlayback();
        if (typeof window !== 'undefined' && window.speechSynthesis) window.speechSynthesis.cancel();
        setPlayingId(null);
        return;
      }
      setPlayingId(item.id);
      const answerLang = detectLang(textToPlay);
      if (typeof window !== 'undefined' && window.speechSynthesis) window.speechSynthesis.cancel();
      stopAudioPlayback();

      if (answerLang === 'kk') {
        try {
          const res = await fetch('/api/tts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ text: textToPlay, lang: answerLang })
          });
          if (res.ok) {
            if ('MediaSource' in window && res.body) {
              await playStreamedMp3(res);
              if (audioRef.current) {
                audioRef.current.onended = () => { stopAudioPlayback(); setPlayingId(null); };
              }
            } else {
              const blob = new Blob([await res.arrayBuffer()], { type: 'audio/mpeg' });
              const url = URL.createObjectURL(blob);
              revokeUrlRef.current = url;
              const audio = new Audio(url);
              audioRef.current = audio;
              audio.play().catch(() => {});
              audio.onended = () => { stopAudioPlayback(); setPlayingId(null); };
            }
            return;
          }
        } catch { /* ignore */ }
        setPlayingId(null);
        return;
      }

      const utter = playWithSpeechSynthesis(textToPlay, answerLang);
      if (utter) {
        utter.onend = () => setPlayingId(null);
      } else {
        setPlayingId(null);
      }
    },
    [playingId, stopAudioPlayback, playStreamedMp3, playWithSpeechSynthesis, ttsEnabled]
  );

  const handleSearch = async (q: string) => {
    if (isGuest) return;
    setSearching(true);
    try {
      const url = q.trim()
        ? `/api/conversations?query=${encodeURIComponent(q)}`
        : '/api/conversations';
      const res = await fetch(url);
      const data = await res.json().catch(() => ({}));
      setSaved(data.items ?? []);
    } finally {
      setSearching(false);
    }
  };

  return (
    <>
      <main className="flex-1 grid lg:grid-cols-[1fr_320px] min-h-screen">
        <section className="p-6 lg:p-10">
          <div className="flex items-center gap-3 mb-6" />
          <div className="card p-6 h-[62vh] lg:h-[58vh] overflow-y-auto space-y-4" role="log" aria-live="polite">
            {messages.length === 0 && (
              <div className="text-slate-500 dark:text-slate-300 text-sm">{t.samplePrompts}</div>
            )}
            {messages.map((msg, idx) => (
              <div key={idx} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div
                  className={`relative max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-relaxed shadow ${
                    msg.role === 'user'
                      ? 'bg-steppe text-white rounded-br-sm'
                      : 'bg-white dark:bg-slate-800 dark:text-slate-100 rounded-bl-sm border border-slate-100 dark:border-slate-700'
                  }`}
                >
                  {msg.role === 'assistant'
                    ? <MessageContent text={msg.content} />
                    : msg.content}
                  {msg.role === 'assistant' && !isGuest && (
                    <button
                      className="absolute -top-3 -right-3 h-9 w-9 rounded-full bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 shadow flex items-center justify-center text-amber-500 hover:shadow-lg"
                      title="Сохранить чат"
                      onClick={() => saveConversation()}
                    >
                      ★
                    </button>
                  )}
                </div>
              </div>
            ))}
            {loading && <div className="text-sm text-slate-500 dark:text-slate-300">{t.typing}</div>}
          </div>
          <div className="mt-4 card p-4 flex flex-col gap-3">
            <div className="relative">
              <textarea
                className="input h-28 resize-none pr-16"
                placeholder={isGuest ? t.placeholderGuest : t.placeholderUser}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleSendWithText(input);
                  }
                }}
              />
              
              {/* Audio level indicator - С‡РёСЃС‚С‹Р№ Рё РјРёРЅРёРјР°Р»РёСЃС‚РёС‡РЅС‹Р№ */}
              {speechState === 'listening' && (
                <div className="absolute bottom-2 right-2 flex items-center gap-1">
                  {[...Array(10)].map((_, i) => (
                    <div
                      key={i}
                      className={`w-1 rounded-full transition-all duration-100 ${
                        i < audioLevel 
                          ? 'bg-red-500 dark:bg-red-400' 
                          : 'bg-slate-300 dark:bg-slate-600'
                      }`}
                      style={{
                        height: `${Math.max(4, (i + 1) * 2)}px`
                      }}
                    />
                  ))}
                </div>
              )}
            </div>
            
            <div className="flex flex-wrap items-center gap-3 justify-between text-sm">
              <div className="flex items-center gap-3">
                <div className="relative group">
                  <button
                    type="button"
                    className={`flex items-center justify-center h-14 w-14 rounded-full transition-all duration-200 ${
                      !speechSupported || isGuest 
                        ? 'opacity-50 cursor-not-allowed bg-slate-200 dark:bg-slate-700' 
                        : speechState === 'listening'
                          ? 'bg-red-500 text-white scale-110 shadow-lg'
                          : speechState === 'error'
                            ? 'bg-amber-500 text-white hover:bg-amber-600'
                            : 'bg-white dark:bg-slate-800 hover:scale-105 hover:shadow-lg border-2 border-steppe dark:border-steppe/50'
                    }`}
                    onClick={handleMicToggle}
                    aria-label={speechState === 'listening' ? t.listening : t.tapToSpeak}
                    disabled={!speechSupported || isGuest}
                  >
                    {speechState === 'listening' ? (
                      <div className="relative">
                        <div className="absolute inset-0 rounded-full animate-ping bg-red-400 opacity-75"></div>
                        <svg className="w-6 h-6 relative z-10" fill="currentColor" viewBox="0 0 24 24">
                          <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
                          <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
                        </svg>
                      </div>
                    ) : speechState === 'processing' ? (
                      <svg className="animate-spin h-6 w-6" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                        <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                        <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                      </svg>
                    ) : speechState === 'error' ? (
                      <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
                        <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z"/>
                      </svg>
                    ) : (
                      <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
                        <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
                        <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
                      </svg>
                    )}
                  </button>
                  
                  {/* РџСЂРѕСЃС‚РѕР№ С‚СѓР»С‚РёРї */}
                  <div className="absolute bottom-full left-1/2 transform -translate-x-1/2 mb-2 px-2 py-1 bg-slate-900 text-white text-xs rounded opacity-0 group-hover:opacity-100 transition-opacity whitespace-nowrap pointer-events-none">
                    {speechState === 'listening' ? t.listening : speechState === 'processing' ? t.processing : speechState === 'error' ? `${t.error}. ${t.retry}` : t.tapToSpeak}
                  </div>
                </div>

                {!speechSupported && (
                  <span className="text-xs text-slate-500 dark:text-slate-400">
                    Микрофон доступен в Chrome / Edge / Safari
                  </span>
                )}
                
                {isGuest && (
                  <span className="text-xs text-slate-500 dark:text-slate-400">
                    Войдите, чтобы использовать голосовой ввод
                  </span>
                )}
                
                {speechError === 'permission-denied' && (
                  <span className="text-xs text-amber-600 dark:text-amber-400">
                    Разрешите доступ к микрофону
                  </span>
                )}
              </div>
              
              <button 
                onClick={() => handleSendWithText(input)} 
                className="btn btn-primary px-6 h-12" 
                disabled={loading || !input.trim()}
              >
                {loading ? (
                  <svg className="animate-spin h-5 w-5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                    <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                  </svg>
                ) : (
                  t.send
                )}
              </button>
            </div>
          </div>
        </section>

        <aside className="border-l border-slate-200 dark:border-slate-700 bg-white/70 dark:bg-slate-900/60 backdrop-blur p-6 space-y-4 hidden lg:block relative z-0">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">{t.saved}</h2>
            {isGuest && <span className="text-xs text-slate-500 dark:text-slate-300">{t.unavailable}</span>}
          </div>
          {!isGuest ? (
            <>
              <div className="flex gap-2">
                <input
                  className="input"
                  placeholder={t.searchPlaceholder}
                  value={search}
                  onChange={(e) => {
                    const q = e.target.value;
                    setSearch(q);
                    handleSearch(q);
                  }}
                />
              </div>
              <div className="space-y-3 max-h-[65vh] overflow-y-auto">
                {saved.length === 0 && <p className="text-sm text-slate-500 dark:text-slate-200">{t.empty}</p>}
                {saved.map((item) => {
                  const msgCount = item.messages.length;
                  const lastAssistant = [...item.messages].reverse().find((m) => m.role === 'assistant' && m.text);
                  return (
                    <div key={item.id} className="card p-4 bg-white dark:bg-slate-800 hover:shadow-md transition-shadow">
                      <div className="flex items-center justify-between mb-1">
                        <div className="flex items-center gap-1.5 text-xs text-slate-500 dark:text-slate-300">
                          <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 24 24">
                            <path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z"/>
                          </svg>
                          {msgCount} {msgCount === 1 ? 'сообщение' : msgCount < 5 ? 'сообщения' : 'сообщений'}
                          <span className="mx-1">·</span>
                          {new Date(item.updatedAt).toLocaleDateString('ru-RU')}
                        </div>
                        <button
                          className={`h-7 w-7 rounded-full flex items-center justify-center flex-shrink-0 transition-colors ${
                            playingId === item.id
                              ? 'bg-steppe text-white'
                              : 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-steppe/20'
                          }`}
                          title="Озвучить последний ответ"
                          onClick={(e) => { e.stopPropagation(); playSavedTts(item); }}
                        >
                          {playingId === item.id ? (
                            <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
                          ) : (
                            <svg className="w-3.5 h-3.5" fill="currentColor" viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>
                          )}
                        </button>
                      </div>
                      <button className="text-left w-full" onClick={() => setSelected(item)}>
                        <p className="font-semibold text-sm mb-1.5 text-slate-800 dark:text-slate-100 line-clamp-2">{item.title}</p>
                        {lastAssistant?.text && (
                          <p className="text-xs text-slate-500 dark:text-slate-400 line-clamp-2 whitespace-pre-wrap">{lastAssistant.text}</p>
                        )}
                      </button>
                    </div>
                  );
                })}
              </div>
            </>
          ) : (
            <div className="text-sm text-slate-500 dark:text-slate-300">{t.historyHint}</div>
          )}
        </aside>
      </main>

      {selected && (
        <div className="fixed inset-0 z-60 flex items-center justify-center bg-black/40 backdrop-blur-sm px-4">
          <div className="card max-w-2xl w-full p-6 relative bg-white dark:bg-slate-900 max-h-[85vh] flex flex-col">
            {/* Header */}
            <div className="flex items-center justify-between mb-4 flex-shrink-0">
              <div>
                <div className="font-semibold text-slate-800 dark:text-slate-100 text-sm line-clamp-1 pr-8">{selected.title}</div>
                <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                  {new Date(selected.updatedAt).toLocaleString('ru-RU')} · {selected.messages.length} сообщ.
                </div>
              </div>
              <button
                aria-label="Закрыть"
                className="absolute top-3 right-3 h-8 w-8 flex items-center justify-center rounded-full text-slate-400 hover:text-steppe hover:bg-slate-100 dark:hover:bg-slate-800"
                onClick={() => setSelected(null)}
              >
                ✕
              </button>
            </div>

            {/* Conversation thread */}
            <div className="overflow-y-auto flex-1 space-y-3 pr-1">
              {selected.messages.map((msg, idx) => (
                <div key={idx} className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                  <div
                    className={`max-w-[80%] rounded-2xl px-4 py-3 text-sm leading-relaxed shadow ${
                      msg.role === 'user'
                        ? 'bg-steppe text-white rounded-br-sm'
                        : 'bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100 border border-slate-100 dark:border-slate-700 rounded-bl-sm'
                    }`}
                  >
                    {msg.imageUrl ? (
                      <img
                        src={msg.imageUrl}
                        alt="Фото"
                        className="max-w-full rounded-lg"
                        style={{ maxHeight: 300, objectFit: 'contain' }}
                      />
                    ) : msg.text ? (
                      msg.role === 'assistant'
                        ? <MessageContent text={msg.text} />
                        : msg.text
                    ) : null}
                  </div>
                </div>
              ))}
            </div>

            {/* Footer actions */}
            <div className="flex justify-between items-center gap-3 mt-4 pt-4 border-t border-slate-100 dark:border-slate-700 flex-shrink-0">
              <button
                className={`flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-medium transition-colors ${
                  playingId === selected.id
                    ? 'bg-steppe text-white'
                    : 'bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 hover:bg-steppe/20'
                }`}
                onClick={() => playSavedTts(selected)}
              >
                {playingId === selected.id ? (
                  <>
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg>
                    Стоп
                  </>
                ) : (
                  <>
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z"/></svg>
                    Озвучить
                  </>
                )}
              </button>
              <button
                className="btn btn-primary"
                onClick={async () => {
                  if (!selected) return;
                  await fetch(`/api/conversations?id=${selected.id}`, { method: 'DELETE' });
                  setSaved((prev) => prev.filter((s) => s.id !== selected.id));
                  if (currentConvId === selected.id) setCurrentConvId(null);
                  setSelected(null);
                }}
              >
                Удалить
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}




