'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useLang } from '@/lib/useLang';
import { useTheme } from '@/lib/useTheme';
import { useSession } from '@/lib/useSession';
import { useTts } from '@/lib/useTts';

const labels = {
  ru: { theme: 'Тема', lang: 'Язык', login: 'Войти', register: 'Регистрация' },
  kk: { theme: 'Тақырып', lang: 'Тіл', login: 'Кіру', register: 'Тіркелу' },
  en: { theme: 'Theme', lang: 'Language', login: 'Sign in', register: 'Sign up' }
};

const adminLabel = {
  ru: '⚙ Админ панель',
  kk: '⚙ Әкімші панелі',
  en: '⚙ Admin Panel'
};

const order: Array<'ru' | 'kk' | 'en'> = ['ru', 'kk', 'en'];
const languageNames = {
  ru: 'Русский',
  kk: 'Қазақша',
  en: 'English'
};

export default function TopBar() {
  const { lang, setLang } = useLang();
  const { theme, setTheme } = useTheme();
  const { user, logout, reload } = useSession();
  const [menuOpen, setMenuOpen] = useState(false);
  const [hoveredLang, setHoveredLang] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const { enabled: ttsEnabled, toggle: toggleTts, ready: ttsReady } = useTts();
  const [isAdmin, setIsAdmin] = useState(false);

  const thumbLeft = useMemo(() => `${order.indexOf(lang) * 33.3333}%`, [lang]);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (!menuRef.current) return;
      if (!menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  useEffect(() => {
    if (!user) { setIsAdmin(false); return; }
    fetch('/api/session')
      .then(r => r.json())
      .then(d => setIsAdmin(d.user?.role === 'admin'))
      .catch(() => setIsAdmin(false));
  }, [user]);

  return (
    <header className="w-full flex flex-wrap items-center justify-between gap-3 px-4 py-3 bg-white/80 dark:bg-slate-900/80 backdrop-blur border-b border-slate-200 dark:border-slate-700 z-50 relative transition-colors duration-300">
      <style jsx>{`
        /* Стили для кнопки переключения темы */
        .theme-toggle {
          position: relative;
          display: inline-flex;
          align-items: center;
          height: 40px;
        }

        .theme-toggle input {
          display: none;
        }

        .toggle-track {
          position: relative;
          width: 80px;
          height: 40px;
          background-color: white;
          border-radius: 9999px;
          transition: background-color 0.3s ease;
          box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }

        .theme-toggle input:checked + .toggle-track {
          background-color: #71717a;
        }

        .toggle-thumb {
          position: absolute;
          content: '';
          width: 32px;
          height: 32px;
          background: linear-gradient(to right, #f97316, #facc15);
          border-radius: 9999px;
          top: 4px;
          left: 4px;
          transition: all 0.3s ease;
          box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }

        .theme-toggle input:checked + .toggle-track .toggle-thumb {
          left: 76px;
          transform: translateX(-100%);
          background: #18181b;
        }

        .theme-toggle:active .toggle-thumb {
          width: 36px;
          height: 36px;
        }

        .sun-icon {
          position: absolute;
          width: 20px;
          height: 20px;
          left: 10px;
          fill: white;
          transition: opacity 0.3s ease;
          pointer-events: none;
          z-index: 10;
        }

        .moon-icon {
          position: absolute;
          width: 20px;
          height: 20px;
          right: 10px;
          fill: black;
          opacity: 0.6;
          transition: all 0.3s ease;
          pointer-events: none;
          z-index: 10;
        }

        .theme-toggle input:checked ~ .moon-icon {
          opacity: 0.7;
          fill: white;
        }

        .theme-toggle input:checked ~ .sun-icon {
          opacity: 0.6;
        }

        /* Стили для кнопки звука */
        .sound-toggle {
          width: 40px;
          height: 40px;
          position: relative;
          display: flex;
          align-items: center;
          justify-content: center;
          background-color: white;
          border-radius: 50%;
          cursor: pointer;
          transition: all 0.2s ease;
          box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
          overflow: hidden;
        }

        /* В темной теме фон всегда темный */
        :global(.dark) .sound-toggle {
          background-color: #1e293b;
        }

        .sound-toggle:hover {
          background-color: #f3f4f6;
        }

        :global(.dark) .sound-toggle:hover {
          background-color: #334155;
        }

        .sound-toggle:active {
          transform: scale(0.9);
        }

        #checkboxInput {
          display: none;
        }

        .speaker {
          width: 100%;
          height: 100%;
          display: flex;
          align-items: center;
          justify-content: center;
          z-index: 2;
          transition: opacity 0.2s ease;
        }

        .speaker svg {
          width: 20px;
          height: 20px;
        }

        /* В светлой теме - темные иконки */
        .speaker svg path {
          stroke: #4b5563;
          fill: #4b5563;
        }

        /* В темной теме - иконки ВСЕГДА БЕЛЫЕ */
        :global(.dark) .speaker svg path {
          stroke: #ffffff !important;
          fill: #ffffff !important;
        }

        .mute-speaker {
          position: absolute;
          width: 100%;
          height: 100%;
          display: flex;
          align-items: center;
          justify-content: center;
          opacity: 0;
          z-index: 3;
          transition: opacity 0.2s ease;
          background-color: transparent;
          border-radius: 50%;
        }

        .mute-speaker svg {
          width: 20px;
          height: 20px;
        }

        /* В светлой теме - темные иконки */
        .mute-speaker svg path {
          stroke: #4b5563;
          fill: #4b5563;
        }

        /* В темной теме - иконки ВСЕГДА БЕЛЫЕ */
        :global(.dark) .mute-speaker svg path {
          stroke: #ffffff !important;
          fill: #ffffff !important;
        }

        /* Когда звук ВКЛ (checked = true) - показываем обычный динамик */
        #checkboxInput:checked + .sound-toggle .speaker {
          opacity: 1;
        }

        #checkboxInput:checked + .sound-toggle .mute-speaker {
          opacity: 0;
        }

        /* Когда звук ВЫКЛ (checked = false) - показываем динамик с перечеркиванием */
        #checkboxInput:not(:checked) + .sound-toggle .speaker {
          opacity: 0;
        }

        #checkboxInput:not(:checked) + .sound-toggle .mute-speaker {
          opacity: 1;
        }

        /* Анимации для остальных элементов */
        .flag-button {
          transition: all 0.2s ease;
        }

        .flag-button:hover {
          transform: scale(1.1);
        }

        .flag-button:active {
          transform: scale(0.95);
        }

        .tooltip {
          position: absolute;
          top: -32px;
          left: 50%;
          transform: translateX(-50%);
          padding: 4px 8px;
          border-radius: 4px;
          background-color: #111827;
          color: white;
          font-size: 12px;
          white-space: nowrap;
          transition: all 0.2s ease;
          pointer-events: none;
          z-index: 50;
        }

        .tooltip::after {
          content: '';
          position: absolute;
          top: 100%;
          left: 50%;
          transform: translateX(-50%);
          border-width: 4px;
          border-style: solid;
          border-color: #111827 transparent transparent transparent;
        }

        .tooltip-hidden {
          opacity: 0;
          transform: translateX(-50%) translateY(4px);
          pointer-events: none;
        }

        .tooltip-visible {
          opacity: 1;
          transform: translateX(-50%) translateY(0);
        }

        .lang-indicator {
          position: absolute;
          top: 50%;
          transform: translateY(-50%);
          width: 40px;
          height: 40px;
          border-radius: 50%;
          background-color: #14B8A6;
          transition: left 0.5s cubic-bezier(0.34, 1.2, 0.64, 1);
          z-index: 5;
        }

        :global(.dark) .lang-indicator {
          background-color: #0d9488;
        }

        .btn {
          transition: all 0.2s ease;
        }

        .btn:hover {
          transform: scale(1.05);
        }

        .btn:active {
          transform: scale(0.95);
        }
      `}</style>

      <Link href="/" className="flex items-center gap-2 text-sm hover:opacity-80 transition-opacity">
        <div className="flex flex-col">
          <span className="font-semibold text-steppe dark:text-sky transition-colors duration-300">Виртуальный консультант маслихата</span>
          <span className="text-slate-400 text-xs transition-colors duration-300">Live conversation</span>
        </div>
        <div className="relative inline-flex items-center group">
          <div
            className="border-2 border-teal-400 cursor-pointer shadow-sm overflow-hidden"
            style={{
              width: 58,
              height: 40,
              minWidth: 58,
              borderRadius: 10,
              backgroundImage: 'url(/bitriks.png)',
              backgroundSize: 'cover',
              backgroundPosition: 'center',
              backgroundRepeat: 'no-repeat',
            }}
          />
          <div className="absolute top-full left-1/2 -translate-x-1/2 mt-2 px-3 py-1.5 rounded-lg bg-slate-800 text-white text-xs whitespace-nowrap opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity duration-200 shadow-lg z-50">
            Дата добавления: добавлено 24.04.26
            <div className="absolute bottom-full left-1/2 -translate-x-1/2 border-4 border-transparent border-b-slate-800" />
          </div>
        </div>
      </Link>

      <div className="flex items-center gap-3">
        {/* Кнопка Админ панели */}
        {user && isAdmin && (
          <Link
            href="/admin"
            className="btn btn-primary h-10 text-sm flex items-center gap-1"
            style={{ background: '#14B8A6', borderColor: '#14B8A6', fontSize: '13px', padding: '0 14px' }}
          >
            {adminLabel[lang]}
          </Link>
        )}

        {/* Переключатель языков */}
        <div className="relative h-10 w-52 rounded-full border border-slate-200 dark:border-slate-700 bg-white/70 dark:bg-slate-800 overflow-hidden shadow-sm transition-all duration-300">
          {/* Индикатор выбранного языка */}
          <div
            className="lang-indicator"
            style={{ 
              left: `calc(${thumbLeft} + (33.3333% / 2) - 1.25rem)`
            }}
          />
          
          <div className="grid grid-cols-3 h-full relative z-10">
            {order.map((code) => (
              <div key={code} className="relative flex items-center justify-center">
                <button
                  className="flag-button w-full h-full flex items-center justify-center"
                  onClick={() => setLang(code)}
                  onMouseEnter={() => setHoveredLang(code)}
                  onMouseLeave={() => setHoveredLang(null)}
                  aria-label={`${labels[lang].lang}: ${languageNames[code]}`}
                >
                  <span style={{
                    fontSize: '13px',
                    fontWeight: 600,
                    color: lang === code ? '#fff' : '#64748b',
                    letterSpacing: '0.01em',
                    position: 'relative',
                    zIndex: 10,
                  }}>
                    {code === 'ru' ? 'рус' : code === 'kk' ? 'қаз' : 'en'}
                  </span>
                </button>
                
                {/* Всплывающая подсказка */}
                <div
                  className={`tooltip ${hoveredLang === code ? 'tooltip-visible' : 'tooltip-hidden'}`}
                >
                  {languageNames[code]}
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Кнопка переключения темы */}
        <label className="theme-toggle">
          <input 
            type="checkbox" 
            checked={theme === 'dark'}
            onChange={() => setTheme(theme === 'light' ? 'dark' : 'light')}
          />
          <div className="toggle-track">
            <div className="toggle-thumb"></div>
          </div>
          
          {/* Иконка солнца */}
          <svg
            viewBox="0 0 24 24"
            className="sun-icon"
            xmlns="http://www.w3.org/2000/svg"
          >
            <path
              d="M12,17c-2.76,0-5-2.24-5-5s2.24-5,5-5,5,2.24,5,5-2.24,5-5,5ZM13,0h-2V5h2V0Zm0,19h-2v5h2v-5ZM5,11H0v2H5v-2Zm19,0h-5v2h5v-2Zm-2.81-6.78l-1.41-1.41-3.54,3.54,1.41,1.41,3.54-3.54ZM7.76,17.66l-1.41-1.41-3.54,3.54,1.41,1.41,3.54-3.54Zm0-11.31l-3.54-3.54-1.41,1.41,3.54,3.54,1.41-1.41Zm13.44,13.44l-3.54-3.54-1.41,1.41,3.54,3.54,1.41-1.41Z"
            ></path>
          </svg>
          
          {/* Иконка луны */}
          <svg
            viewBox="0 0 24 24"
            className="moon-icon"
            xmlns="http://www.w3.org/2000/svg"
          >
            <path
              d="M12.009,24A12.067,12.067,0,0,1,.075,10.725,12.121,12.121,0,0,1,10.1.152a13,13,0,0,1,5.03.206,2.5,2.5,0,0,1,1.8,1.8,2.47,2.47,0,0,1-.7,2.425c-4.559,4.168-4.165,10.645.807,14.412h0a2.5,2.5,0,0,1-.7,4.319A13.875,13.875,0,0,1,12.009,24Zm.074-22a10.776,10.776,0,0,0-1.675.127,10.1,10.1,0,0,0-8.344,8.8A9.928,9.928,0,0,0,4.581,18.7a10.473,10.473,0,0,0,11.093,2.734.5.5,0,0,0,.138-.856h0C9.883,16.1,9.417,8.087,14.865,3.124a.459.459,0,0,0,.127-.465.491.491,0,0,0-.356-.362A10.68,10.68,0,0,0,12.083,2ZM20.5,12a1,1,0,0,1-.97-.757l-.358-1.43L17.74,9.428a1,1,0,0,1,.035-1.94l1.4-.325.351-1.406a1,1,0,0,1,1.94,0l.355,1.418,1.418.355a1,1,0,0,1,0,1.94l-1.418.355-.355,1.418A1,1,0,0,1,20.5,12ZM16,14a1,1,0,0,0,2,0A1,1,0,0,0,16,14Zm6,4a1,1,0,0,0,2,0A1,1,0,0,0,22,18Z"
            ></path>
          </svg>
        </label>

        {/* Кнопка звука - теперь правильно: ВКЛ - обычный динамик, ВЫКЛ - с перечеркиванием */}
        <input 
          type="checkbox" 
          id="checkboxInput" 
          checked={ttsEnabled}
          onChange={toggleTts}
          disabled={!ttsReady}
        />
        <label htmlFor="checkboxInput" className="sound-toggle">
          {/* Обычный динамик - для ВКЛ */}
          <div className="speaker">
            <svg xmlns="http://www.w3.org/2000/svg" version="1.0" viewBox="0 0 75 75">
              <path
                d="M39.389,13.769 L22.235,28.606 L6,28.606 L6,47.699 L21.989,47.699 L39.389,62.75 L39.389,13.769z"
                style={{stroke: '#4b5563', strokeWidth: '5', strokeLinejoin: 'round', fill: '#4b5563'}}
                className="speaker-path-light"
              />
              <path
                d="M48,27.6a19.5,19.5 0 0 1 0,21.4M55.1,20.5a30,30 0 0 1 0,35.6M61.6,14a38.8,38.8 0 0 1 0,48.6"
                style={{fill: 'none', stroke: '#4b5563', strokeWidth: '5', strokeLinecap: 'round'}}
                className="speaker-path-light"
              />
            </svg>
          </div>

          {/* Динамик с перечеркиванием - для ВЫКЛ */}
          <div className="mute-speaker">
            <svg version="1.0" viewBox="0 0 75 75" strokeWidth="5">
              <path
                d="m39,14-17,15H6V48H22l17,15z"
                fill="#4b5563"
                stroke="#4b5563"
                strokeLinejoin="round"
                className="mute-path-light"
              />
              <path
                d="m49,26 20,24m0-24-20,24"
                fill="#4b5563"
                stroke="#4b5563"
                strokeLinecap="round"
                className="mute-path-light"
              />
            </svg>
          </div>
        </label>
        
        {/* Кнопки авторизации */}
        {!user ? (
          <>
            <Link href="/login" className="btn btn-ghost h-10 text-sm border border-slate-200 dark:border-slate-700 dark:text-slate-100">
              {labels[lang].login}
            </Link>
            <Link href="/register" className="btn btn-primary h-10 text-sm">
              {labels[lang].register}
            </Link>
          </>
        ) : (
          <div className="relative" ref={menuRef}>
            <button
              className="h-10 w-10 rounded-full text-white font-semibold flex items-center justify-center transition-all duration-200 hover:scale-105 active:scale-95"
              style={{ background: '#14B8A6' }}
              onClick={() => setMenuOpen((v) => !v)}
              aria-label="Профиль"
            >
              {user.name?.slice(0, 1).toUpperCase()}
            </button>
            {menuOpen && (
              <div className="absolute right-0 mt-2 w-40 card p-2 z-50 text-slate-800 dark:text-slate-100 transition-all duration-200">
                <Link
                  className="block px-3 py-2 rounded hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-800 dark:text-slate-100 transition-colors duration-200"
                  href="/profile"
                  onClick={() => setMenuOpen(false)}
                >
                  Профиль
                </Link>
                <button
                  className="block w-full text-left px-3 py-2 rounded hover:bg-slate-100 dark:hover:bg-slate-800 text-slate-800 dark:text-slate-100 transition-colors duration-200"
                  onClick={async () => {
                    setMenuOpen(false);
                    await logout();
                    reload();
                  }}
                >
                  Выйти
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {/* Дополнительные стили для темной темы */}
      <style jsx global>{`
        .dark .speaker-path-light {
          stroke: #ffffff !important;
          fill: #ffffff !important;
        }
        .dark .mute-path-light {
          stroke: #ffffff !important;
          fill: #ffffff !important;
        }
      `}</style>
    </header>
  );
}