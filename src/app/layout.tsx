import './globals.css';
import type { Metadata } from 'next';
import TopBar from '@/components/TopBar';

export const metadata: Metadata = {
  title: 'Виртуальный консультант маслихата',
  description: 'ИИ-ассистент для граждан и депутатов'
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="ru">
      <body className="text-slate-900" suppressHydrationWarning>
        <div className="min-h-screen flex flex-col">
          <TopBar />
          {children}
        </div>
      </body>
    </html>
  );
}
