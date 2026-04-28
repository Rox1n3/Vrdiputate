'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLang } from '@/lib/useLang';
import { useSession } from '@/lib/useSession';
import Link from 'next/link';

type Status = 'processing' | 'in_work' | 'done' | 'rejected';

type ComplaintMessage = {
  role: string;        // "user" | "bot"
  text?: string;       // текстовое сообщение
  imageUrl?: string;   // Firebase Storage URL фото (если пользователь прикрепил фото)
};

type Complaint = {
  id: string; uid: string; fio: string; phone: string;
  problem: string; address: string; status: Status;
  complaintNumber: number; lang: string;
  lat: number | null; lng: number | null;
  messages: ComplaintMessage[];
  createdAt: string | null;
};

type LocationPin = {
  id: string; lat: number; lng: number;
  address: string; description: string; complaintId: string;
};

type AdminUser = {
  uid: string; email: string; name: string; role: string; createdAt: string | null;
};

type StatsRow = {
  name: string; okrug: number; total: number; done: number;
};


const STATUS_LABELS: Record<string, Record<Status, string>> = {
  ru: { processing: 'в обработке', in_work: 'в работе', done: 'выполнено', rejected: 'отклонено' },
  kk: { processing: 'қарастырылуда', in_work: 'орындалуда', done: 'орындалды', rejected: 'қабылданбады' },
  en: { processing: 'processing', in_work: 'in progress', done: 'done', rejected: 'rejected' },
};
const STATUS_COLORS: Record<Status, string> = {
  processing: '#9E9E9E', in_work: '#14B8A6', done: '#22C55E', rejected: '#EF4444',
};

const T = {
  ru: {
    title: 'Админ панель', map: 'Карта заявок', complaints: 'Заявки', users: 'Пользователи',
    loading: 'Загрузка…', noComplaints: 'Заявок нет', noUsers: 'Нет пользователей',
    num: '№', fio: 'ФИО', problem: 'Проблема', status: 'Статус', actions: 'Действия',
    view: 'Открыть', del: 'Удалить', makeAdmin: 'Сделать админом', removeAdmin: 'Убрать права',
    close: 'Закрыть', conversation: 'Переписка', noMessages: 'Переписка не сохранена',
    you: 'Заявитель', ai: 'ИИ', back: '← Назад', accessDenied: 'Доступ запрещён',
    noAccess: 'Только для администраторов.',
    search: 'Поиск…', email: 'Email', name: 'Имя', role: 'Роль', error: 'Ошибка загрузки.',
    deleteConfirm: 'Удалить заявку? Нельзя отменить.',
    noCoords: 'Нет координат', pavlodar: 'Павлодарская область',
    clickHint: 'Нажми строку — перейти к точке на карте',
    stats: 'Статистика', statsExec: 'Исполнитель', statsTasks: 'Задачи',
    statsDays: 'Срок (дней)', statsOverdue: 'Просроченные', statsDone: 'Выполненные',
    statsPercent: 'Процент выполненных задач', statsPercentOverdue: 'Процент просроченных задач',
    statsOkrug: 'Округ', statsNoData: 'Нет данных по депутатам',
    points: (n: number) => {
      const m10 = n % 10, m100 = n % 100;
      if (m10 === 1 && m100 !== 11) return `${n} точка`;
      if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return `${n} точки`;
      return `${n} точек`;
    },
  },
  kk: {
    title: 'Әкімші панелі', map: 'Өтініштер картасы', complaints: 'Өтініштер', users: 'Пайдаланушылар',
    loading: 'Жүктелуде…', noComplaints: 'Өтініш жоқ', noUsers: 'Пайдаланушылар жоқ',
    num: '№', fio: 'ТАӘ', problem: 'Мәселе', status: 'Мәртебе', actions: 'Әрекет',
    view: 'Ашу', del: 'Жою', makeAdmin: 'Әкімші ету', removeAdmin: 'Құқықтарды алу',
    close: 'Жабу', conversation: 'Хат алмасу', noMessages: 'Хат алмасу сақталмаған',
    you: 'Өтініш беруші', ai: 'ЖИ', back: '← Кері', accessDenied: 'Рұқсат жоқ',
    noAccess: 'Тек әкімшілерге арналған.',
    search: 'Іздеу…', email: 'Email', name: 'Аты', role: 'Рөл', error: 'Жүктеу қатесі.',
    deleteConfirm: 'Өтінішті жою керек пе?',
    noCoords: 'Координаттар жоқ', pavlodar: 'Павлодар облысы',
    clickHint: 'Жолды басу — картадағы нүктеге өту',
    stats: 'Статистика', statsExec: 'Орындаушы', statsTasks: 'Тапсырмалар',
    statsDays: 'Мерзім (күн)', statsOverdue: 'Мерзімі өткен', statsDone: 'Орындалған',
    statsPercent: 'Орындалған тапсырмалар пайызы', statsPercentOverdue: 'Мерзімі өткендер пайызы',
    statsOkrug: 'Округ', statsNoData: 'Депутаттар туралы деректер жоқ',
    points: (n: number) => `${n} нүкте`,
  },
  en: {
    title: 'Admin Panel', map: 'Complaints Map', complaints: 'Complaints', users: 'Users',
    loading: 'Loading…', noComplaints: 'No complaints', noUsers: 'No users',
    num: '#', fio: 'Full Name', problem: 'Problem', status: 'Status', actions: 'Actions',
    view: 'Open', del: 'Delete', makeAdmin: 'Make Admin', removeAdmin: 'Remove Admin',
    close: 'Close', conversation: 'Conversation', noMessages: 'No conversation',
    you: 'Applicant', ai: 'AI', back: '← Back', accessDenied: 'Access Denied',
    noAccess: 'Admins only.',
    search: 'Search…', email: 'Email', name: 'Name', role: 'Role', error: 'Load failed.',
    deleteConfirm: 'Delete complaint? Cannot be undone.',
    noCoords: 'No coordinates', pavlodar: 'Pavlodar Region',
    clickHint: 'Click a row to fly to its point on the map',
    stats: 'Statistics', statsExec: 'Executor', statsTasks: 'Tasks',
    statsDays: 'Days (deadline)', statsOverdue: 'Overdue', statsDone: 'Completed',
    statsPercent: 'Completion rate',
    statsOkrug: 'District', statsNoData: 'No deputy data',
    points: (n: number) => `${n} ${n === 1 ? 'point' : 'points'}`,
  },
};

const PAVLODAR_BOUNDS: [[number,number],[number,number]] = [[50.0,72.5],[55.0,83.5]];
const PAVLODAR_CENTER: [number,number] = [52.3, 77.0];

export default function AdminPage() {
  const { lang } = useLang();
  const { user, loading: sessionLoading } = useSession();
  const router = useRouter();
  const t  = T[lang as keyof typeof T] ?? T.ru;
  const sl = STATUS_LABELS[lang as keyof typeof STATUS_LABELS] ?? STATUS_LABELS.ru;

  const [isAdmin,      setIsAdmin]      = useState<boolean|null>(null);
  const [activeTab,    setActiveTab]    = useState<'complaints'|'users'|'stats'>('complaints');
  const [complaints,   setComplaints]   = useState<Complaint[]>([]);
  const [locations,    setLocations]    = useState<LocationPin[]>([]);
  const [users,        setUsers]        = useState<AdminUser[]>([]);
  const [isMainAdmin,  setIsMainAdmin]  = useState(false);
  const [statsRows,    setStatsRows]    = useState<StatsRow[]>([]);
const [dataLoading,  setDataLoading]  = useState(false);
  const [dataError,    setDataError]    = useState('');
  const [selected,     setSelected]     = useState<Complaint|null>(null);
  const [statusBusy,   setStatusBusy]   = useState<string|null>(null);
  const [deleteBusy,   setDeleteBusy]   = useState<string|null>(null);
  const [search,       setSearch]       = useState('');
  const [markerCount,  setMarkerCount]  = useState(0);
  /** ID заявки, подсвеченной и в списке, и на карте */
  const [highlightedId, setHighlightedId] = useState<string | null>(null);
  /** URL фото для полноэкранного просмотра */
  const [lightboxSrc,  setLightboxSrc]  = useState<string | null>(null);
  /** Трансформация лайтбокса: масштаб + смещение для зума в точку */
  const [lbT, setLbT] = useState({ scale: 1, tx: 0, ty: 0 });

  const mapDivRef         = useRef<HTMLDivElement|null>(null);
  const mapObjRef         = useRef<any>(null);
  const markersRef        = useRef<Record<string,any>>({});
  const mapInitedRef      = useRef(false);   // ref, not state — avoids re-render race
  const highlightedIdRef  = useRef<string | null>(null);
  const prevHighlightedRef = useRef<string | null>(null);
  const onMarkerClickRef  = useRef<(cid: string) => void>(() => {});
  const listContainerRef  = useRef<HTMLDivElement | null>(null);
  const lightboxRef       = useRef<HTMLDivElement | null>(null);
  const lbImgRef          = useRef<HTMLImageElement | null>(null);
  // Всегда актуальное значение трансформации для замыканий колесика
  const lbTRef            = useRef({ scale: 1, tx: 0, ty: 0 });
  lbTRef.current = lbT;

  // ── Marker icon builders ────────────────────────────────────
  const makeNormalIcon = (L: any) => L.divIcon({
    className: '',
    html: `<div style="width:14px;height:14px;background:#EF4444;border:3px solid #fff;border-radius:50%;box-shadow:0 2px 8px rgba(0,0,0,0.4)"></div>`,
    iconSize: [14,14], iconAnchor: [7,7], popupAnchor: [0,-10],
  });

  const makeHighlightIcon = (L: any) => L.divIcon({
    className: '',
    html: `<div style="width:22px;height:22px;background:#EF4444;border:3px solid #fff;border-radius:50%;box-shadow:0 0 0 4px rgba(239,68,68,.35),0 2px 12px rgba(239,68,68,.6)"></div>`,
    iconSize: [22,22], iconAnchor: [11,11], popupAnchor: [0,-12],
  });

  // Keep ref in sync with state (used inside Leaflet closures)
  useEffect(() => { highlightedIdRef.current = highlightedId; }, [highlightedId]);

  // Update marker icons when highlighted complaint changes
  useEffect(() => {
    const L = (window as any).L;
    if (!L) return;
    if (prevHighlightedRef.current) {
      const m = markersRef.current[prevHighlightedRef.current];
      if (m) m.setIcon(makeNormalIcon(L));
    }
    if (highlightedId) {
      const m = markersRef.current[highlightedId];
      if (m) m.setIcon(makeHighlightIcon(L));
    }
    prevHighlightedRef.current = highlightedId;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [highlightedId]);

  // Keep onMarkerClickRef up-to-date so Leaflet handlers always call current logic
  useEffect(() => {
    onMarkerClickRef.current = (cid: string) => {
      setHighlightedId(cid);
      // Scroll the matching row into view
      const row = listContainerRef.current?.querySelector<HTMLElement>(`[data-cid="${cid}"]`);
      if (row) row.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    };
  }, []);

  // ── Lightbox: сброс трансформации + колесо мыши (non-passive) ──
  useEffect(() => {
    setLbT({ scale: 1, tx: 0, ty: 0 });
    const div = lightboxRef.current;
    if (!div) return;

    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const { scale, tx, ty } = lbTRef.current;
      const factor  = e.deltaY < 0 ? 1.15 : 1 / 1.15;
      const newScale = Math.min(10, Math.max(1, scale * factor));

      // Если zoom вернулся в 1 — сбросить смещение
      if (newScale <= 1) { setLbT({ scale: 1, tx: 0, ty: 0 }); return; }

      const img = lbImgRef.current;
      if (!img) { setLbT(prev => ({ ...prev, scale: newScale })); return; }

      // «Натуральная» (до transform) позиция img на экране — центрирована flex-ом
      const elemLeft = (window.innerWidth  - img.offsetWidth)  / 2;
      const elemTop  = (window.innerHeight - img.offsetHeight) / 2;

      // Позиция курсора в локальных координатах элемента (до transform)
      const px = (e.clientX - elemLeft - tx) / scale;
      const py = (e.clientY - elemTop  - ty) / scale;

      // Новое смещение, удерживающее точку под курсором неподвижной
      const newTx = e.clientX - elemLeft - px * newScale;
      const newTy = e.clientY - elemTop  - py * newScale;

      setLbT({ scale: newScale, tx: newTx, ty: newTy });
    };

    div.addEventListener('wheel', onWheel, { passive: false });
    return () => div.removeEventListener('wheel', onWheel);
  }, [lightboxSrc]);

  // ── Auth ────────────────────────────────────────────────────
  useEffect(() => {
    if (sessionLoading) return;
    if (!user) { router.push('/login'); return; }
    fetch('/api/session')
      .then(r => r.json())
      .then(d => setIsAdmin(d.user?.role === 'admin'))
      .catch(() => setIsAdmin(false));
  }, [user, sessionLoading, router]);

  // ── Load data ───────────────────────────────────────────────
  useEffect(() => {
    if (!isAdmin) return;
    setDataLoading(true);
    Promise.all([
      fetch('/api/admin/complaints').then(r => r.json()),
      fetch('/api/admin/locations').then(r => r.json()),
      fetch('/api/admin/users').then(r => r.json()),
      fetch('/api/admin/statistics').then(r => r.json()),
    ])
      .then(([c, l, u, s]) => {
        if (c.error) throw new Error(c.error);
        setComplaints(c.complaints ?? []);
        setLocations(l.locations ?? []);
        setUsers(u.users ?? []);
        setIsMainAdmin(s.isMainAdmin === true);
        setStatsRows(s.rows ?? []);
      })
      .catch(e => setDataError(String(e.message || t.error)))
      .finally(() => setDataLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isAdmin]);

  // ── Load Leaflet CSS + JS ────────────────────────────────────
  useEffect(() => {
    if (typeof window === 'undefined') return;

    const loadCSS = () => {
      if (document.querySelector('link[href*="leaflet"]')) return;
      const link = document.createElement('link');
      link.rel  = 'stylesheet';
      link.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
      document.head.appendChild(link);
    };

    const loadJS = () => {
      if ((window as any).L) { scheduleMapInit(); return; }
      const s   = document.createElement('script');
      s.src     = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
      s.onload  = () => scheduleMapInit();
      s.onerror = () => console.error('Leaflet failed to load');
      document.head.appendChild(s);
    };

    loadCSS();
    loadJS();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Init map — retries until div is in DOM with real size ───
  const scheduleMapInit = () => {
    // Small delay to let React flush the DOM
    setTimeout(tryInitMap, 300);
  };

  const tryInitMap = () => {
    if (mapInitedRef.current) return;
    const L   = (window as any).L;
    const div = mapDivRef.current;
    if (!L || !div) { setTimeout(tryInitMap, 150); return; }

    // Make sure the div has actual pixel dimensions
    // (it will if parent uses visibility:hidden, not display:none)
    if (div.offsetWidth === 0 || div.offsetHeight === 0) {
      setTimeout(tryInitMap, 150);
      return;
    }

    mapInitedRef.current = true;

    const map = L.map(div, {
      center: PAVLODAR_CENTER,
      zoom: 7,
      maxBounds: PAVLODAR_BOUNDS,
      maxBoundsViscosity: 0.85,
      minZoom: 6,
    });

    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      attribution: '© <a href="https://openstreetmap.org">OpenStreetMap</a>',
      maxZoom: 19,
    }).addTo(map);

    map.fitBounds(PAVLODAR_BOUNDS, { padding: [30, 30] });
    mapObjRef.current = map;

    // Add markers after map is ready
    addAllMarkers();
  };

  // ── Add / refresh markers ───────────────────────────────────
  const addAllMarkers = () => {
    const L   = (window as any).L;
    const map = mapObjRef.current;
    if (!L || !map) return;

    Object.values(markersRef.current).forEach((m: any) => m.remove());
    markersRef.current = {};

    const normalIcon = makeNormalIcon(L);

    let cnt = 0;

    // Track how many markers have been placed at each rounded coordinate
    // so we can spiral duplicates outward
    const coordCount: Record<string, number> = {};

    const jitter = (lat: number, lng: number): [number, number] => {
      const key = `${lat.toFixed(4)}_${lng.toFixed(4)}`;
      const n   = coordCount[key] ?? 0;
      coordCount[key] = n + 1;
      if (n === 0) return [lat, lng];
      // Spiral outward: ~50m per step at this latitude
      const angle  = (n - 1) * 137.5 * (Math.PI / 180); // golden-angle spiral
      const radius = 0.00012 * Math.ceil(n / 8);         // ~13m, grows every 8 items
      return [lat + radius * Math.cos(angle), lng + radius * Math.sin(angle)];
    };

    const add = (lat: number, lng: number, title: string, addr: string, num: string, cid: string) => {
      const [jLat, jLng] = jitter(lat, lng);
      const m = L.marker([jLat, jLng], { icon: normalIcon })
        .bindPopup(
          `<div style="font-family:sans-serif;max-width:220px">` +
          `<b style="color:#EF4444">${num ? '№'+num+' — ' : ''}${title}</b>` +
          `<br/><span style="font-size:11px;color:#666">${addr}</span></div>`
        )
        .addTo(map);
      // Marker click → highlight the matching row in the list
      m.on('click', () => onMarkerClickRef.current(cid));
      markersRef.current[cid] = m;
      cnt++;
    };

    // Use current state values via closure — called after data loads too
    const locs  = locationsRef.current;
    const comps = complaintsRef.current;

    locs.forEach(loc => {
      if (!loc.lat || !loc.lng) return;
      const comp = comps.find(c => c.id === loc.complaintId);
      if (!comp) return; // skip orphaned location pins (complaint was deleted)
      const num  = comp.complaintNumber ? String(comp.complaintNumber).padStart(4,'0') : '';
      add(loc.lat, loc.lng, loc.description || 'Проблема', loc.address, num, loc.complaintId);
    });

    comps.forEach(c => {
      if (!c.lat || !c.lng || markersRef.current[c.id]) return;
      const num = c.complaintNumber ? String(c.complaintNumber).padStart(4,'0') : '';
      add(c.lat, c.lng, c.problem, c.address, num, c.id);
    });

    setMarkerCount(cnt);

    // Restore highlight icon after full rebuild
    const hId = highlightedIdRef.current;
    if (hId && markersRef.current[hId]) {
      markersRef.current[hId].setIcon(makeHighlightIcon(L));
    }
  };

  // Keep refs in sync so addAllMarkers closure always has fresh data.
  // Both refs are updated together before a single addAllMarkers() call —
  // this prevents the "deleted marker reappears" bug that happened when two
  // separate effects ran sequentially with stale ref data on the first call.
  const locationsRef  = useRef<LocationPin[]>([]);
  const complaintsRef = useRef<Complaint[]>([]);
  useEffect(() => {
    locationsRef.current  = locations;
    complaintsRef.current = complaints;
    if (mapObjRef.current) addAllMarkers();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locations, complaints]);

  // ── invalidateSize when switching back to complaints tab ────
  useEffect(() => {
    if (activeTab === 'complaints' && mapObjRef.current) {
      setTimeout(() => mapObjRef.current?.invalidateSize(), 80);
    }
  }, [activeTab]);

  // ── Fly to point ────────────────────────────────────────────
  const flyTo = (c: Complaint) => {
    if (!mapObjRef.current || !c.lat || !c.lng) return;
    setHighlightedId(c.id);
    mapObjRef.current.flyTo([c.lat, c.lng], 14, { duration: 1.2 });
    const m = markersRef.current[c.id];
    if (m) setTimeout(() => m.openPopup(), 1300);
  };

  // ── Status update ───────────────────────────────────────────
  const updateStatus = async (complaint: Complaint, newStatus: Status) => {
    setStatusBusy(complaint.id);
    try {
      await fetch(`/api/admin/complaints/${complaint.id}/status`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ uid: complaint.uid, status: newStatus }),
      });
      const updated = { ...complaint, status: newStatus };
      setComplaints(prev => prev.map(c => c.id===complaint.id ? updated : c));
      if (selected?.id === complaint.id) setSelected(updated);
    } finally { setStatusBusy(null); }
  };

  // ── Delete complaint ────────────────────────────────────────
  const deleteComplaint = async (c: Complaint) => {
    if (!window.confirm(t.deleteConfirm)) return;
    setDeleteBusy(c.id);
    try {
      await fetch(`/api/admin/complaints/${c.id}?uid=${encodeURIComponent(c.uid)}`, { method: 'DELETE' });
      setComplaints(prev => prev.filter(x => x.id !== c.id));
      setLocations(prev => prev.filter(x => x.complaintId !== c.id));
      // Marker cleanup is handled by the combined useEffect above (addAllMarkers re-runs)
      if (selected?.id === c.id) setSelected(null);
    } finally { setDeleteBusy(null); }
  };

  // ── Toggle role ─────────────────────────────────────────────
  const toggleRole = async (u: AdminUser) => {
    const newRole = u.role === 'admin' ? 'user' : 'admin';
    await fetch('/api/admin/users', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ uid: u.uid, role: newRole }),
    });
    setUsers(prev => prev.map(x => x.uid===u.uid ? { ...x, role: newRole } : x));
  };

  const filtered = complaints.filter(c => {
    if (!search) return true;
    const q = search.toLowerCase().trim();
    // Поиск по номеру заявки: «23», «0023», «№23» и т.п.
    const num = c.complaintNumber > 0 ? String(c.complaintNumber) : '';
    const numPadded = c.complaintNumber > 0 ? String(c.complaintNumber).padStart(4, '0') : '';
    const numQuery = q.replace(/^№/, '').replace(/^0+/, '') || '0';
    const numMatch = num !== '' && (
      num === numQuery ||
      numPadded.includes(q.replace(/^№/, ''))
    );
    return (
      numMatch ||
      c.fio.toLowerCase().includes(q) ||
      c.problem.toLowerCase().includes(q) ||
      c.address.toLowerCase().includes(q) ||
      c.phone.includes(q)
    );
  });

  const fmtDate = (iso: string|null) =>
    iso ? new Date(iso).toLocaleString('ru-RU', {
      day:'2-digit', month:'2-digit', year:'numeric', hour:'2-digit', minute:'2-digit',
    }) : '—';

  // ── Loading / access guards ─────────────────────────────────
  if (sessionLoading || isAdmin === null) {
    return (
      <div style={{ display:'flex', alignItems:'center', justifyContent:'center', minHeight:'100vh' }}>
        <span style={{ opacity:0.4, fontSize:'14px' }}>{t.loading}</span>
      </div>
    );
  }
  if (!isAdmin) {
    return (
      <div style={{ display:'flex', flexDirection:'column', alignItems:'center', justifyContent:'center',
                    minHeight:'100vh', gap:'16px', padding:'24px', textAlign:'center',
                    background:'var(--bg)', color:'var(--text)' }}>
        <div style={{ fontSize:'52px' }}>🔒</div>
        <h1 style={{ color:'#EF4444', fontSize:'22px', fontWeight:700, margin:0 }}>{t.accessDenied}</h1>
        <p style={{ opacity:0.6, fontSize:'13px', maxWidth:'320px', margin:0 }}>{t.noAccess}</p>
        <Link href="/" style={{ padding:'8px 20px', borderRadius:'10px', background:'#14B8A6',
                                 color:'#fff', fontSize:'13px', fontWeight:600 }}>{t.back}</Link>
      </div>
    );
  }

  // ═══════════════════════════════════════════════════════════
  const showComplaints = !dataLoading && activeTab === 'complaints';
  const showUsers      = !dataLoading && activeTab === 'users';
  const showStats      = !dataLoading && activeTab === 'stats';

  return (
    <div style={{ minHeight:'100vh', display:'flex', flexDirection:'column',
                  background:'var(--bg)', color:'var(--text)' }}>

      {/* Header */}
      <header style={{ flexShrink:0, display:'flex', alignItems:'center', justifyContent:'space-between',
                       padding:'10px 24px', background:'var(--card)', borderBottom:'1px solid var(--border)' }}>
        <h1 style={{ margin:0, fontWeight:700, fontSize:'17px', color:'#14B8A6' }}>⚙ {t.title}</h1>
        <Link href="/" style={{ fontSize:'13px', opacity:0.6 }}>{t.back}</Link>
      </header>

      {/* Tabs */}
      <div style={{ flexShrink:0, display:'flex', gap:'8px', padding:'10px 24px 6px' }}>
        {(['complaints','users'] as const).map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)} style={{
            padding:'6px 20px', borderRadius:'999px', fontSize:'13px', fontWeight:600,
            border:'1px solid', cursor:'pointer', transition:'all .2s',
            background:  activeTab===tab ? '#14B8A6' : 'var(--card)',
            color:       activeTab===tab ? '#fff'    : 'var(--text)',
            borderColor: activeTab===tab ? '#14B8A6' : 'var(--border)',
          }}>
            {tab==='complaints' ? `📋 ${t.complaints}` : `👥 ${t.users}`}
          </button>
        ))}
        {isMainAdmin && (
          <button onClick={() => setActiveTab('stats')} style={{
            padding:'6px 20px', borderRadius:'999px', fontSize:'13px', fontWeight:600,
            border:'1px solid', cursor:'pointer', transition:'all .2s',
            background:  activeTab==='stats' ? '#14B8A6' : 'var(--card)',
            color:       activeTab==='stats' ? '#fff'    : 'var(--text)',
            borderColor: activeTab==='stats' ? '#14B8A6' : 'var(--border)',
          }}>
            📊 {t.stats}
          </button>
        )}
      </div>

      {/* Error */}
      {dataError && (
        <div style={{ flexShrink:0, margin:'0 24px 6px', padding:'10px 16px', borderRadius:'12px',
                      fontSize:'13px', background:'rgba(239,68,68,.1)',
                      border:'1px solid rgba(239,68,68,.3)', color:'#EF4444' }}>
          ⚠ {dataError}
        </div>
      )}

      {/* Loading spinner */}
      {dataLoading && (
        <div style={{ flex:1, display:'flex', alignItems:'center', justifyContent:'center' }}>
          <span style={{ opacity:0.4, fontSize:'14px' }}>{t.loading}</span>
        </div>
      )}

      {/*
        ╔══════════════════════════════════════════════════════════╗
        ║  BOTH PANELS ALWAYS RENDERED — toggle via visibility    ║
        ║  visibility:hidden keeps layout dimensions intact       ║
        ║  so Leaflet always sees a real pixel size               ║
        ╚══════════════════════════════════════════════════════════╝
      */}

      {/* ── COMPLAINTS PANEL ────────────────────────────────── */}
      <div style={{
        display: 'flex', gap:'14px', padding:'0 14px 14px', flex: showComplaints ? 1 : 'none',
        // visibility keeps dimensions; opacity hides visually
        visibility: showComplaints ? 'visible' : 'hidden',
        pointerEvents: showComplaints ? 'auto' : 'none',
        height: showComplaints ? 'auto' : 0,
        overflow: showComplaints ? 'visible' : 'hidden',
      }}>

        {/* MAP card — 62% */}
        <div style={{
          flex:'0 0 62%', display:'flex', flexDirection:'column',
          borderRadius:'16px', overflow:'hidden',
          border:'1px solid var(--border)', background:'var(--card)',
          position:'relative', zIndex:0,
        }}>
          <div style={{ flexShrink:0, padding:'8px 14px', borderBottom:'1px solid var(--border)',
                        display:'flex', alignItems:'center', gap:'8px',
                        fontSize:'13px', fontWeight:600 }}>
            📍 {t.map}
            <span style={{ opacity:.4, fontWeight:400, fontSize:'11px' }}>— {t.pavlodar}</span>
            <span style={{ marginLeft:'auto', fontWeight:600, fontSize:'13px', color:'#14B8A6' }}>
              {t.points(markerCount)}
            </span>
          </div>
          {/* ★ Fixed height — Leaflet ALWAYS has real pixels */}
          <div ref={mapDivRef} style={{ height:'580px', width:'100%' }} />

          {/* Stats strip — complaint counts by status */}
          <div style={{ flexShrink:0, padding:'8px 14px', borderTop:'1px solid var(--border)',
                        display:'flex', gap:'8px' }}>
            {(['processing','in_work','done','rejected'] as Status[]).map(s => {
              const count = complaints.filter(c => c.status === s).length;
              return (
                <div key={s} style={{ flex:1, textAlign:'center', padding:'7px 4px',
                                      borderRadius:'10px',
                                      border:`1px solid ${STATUS_COLORS[s]}44`,
                                      background:`${STATUS_COLORS[s]}14` }}>
                  <div style={{ fontSize:'22px', fontWeight:700,
                                color:STATUS_COLORS[s], lineHeight:1.1 }}>{count}</div>
                  <div style={{ fontSize:'10px', opacity:.65, marginTop:'3px',
                                whiteSpace:'nowrap', overflow:'hidden',
                                textOverflow:'ellipsis' }}>{sl[s]}</div>
                </div>
              );
            })}
          </div>
        </div>

        {/* TABLE card — 38% */}
        <div style={{
          flex:'0 0 calc(38% - 14px)', display:'flex', flexDirection:'column',
          borderRadius:'16px', overflow:'hidden',
          border:'1px solid var(--border)', background:'var(--card)',
        }}>
          {/* Search header */}
          <div style={{ flexShrink:0, padding:'8px 12px', borderBottom:'1px solid var(--border)',
                        display:'flex', alignItems:'center', gap:'8px', flexWrap:'wrap' }}>
            <span style={{ fontWeight:600, fontSize:'13px' }}>
              📋 {t.complaints} ({filtered.length})
            </span>
            <input type="text" value={search} onChange={e => setSearch(e.target.value)}
                   placeholder={t.search}
                   style={{ marginLeft:'auto', padding:'4px 10px', borderRadius:'8px', fontSize:'12px',
                            border:'1px solid var(--border)', background:'transparent',
                            color:'var(--text)', outline:'none', minWidth:'110px' }} />
          </div>
          <div style={{ flexShrink:0, padding:'3px 12px', fontSize:'10px', opacity:.35,
                        borderBottom:'1px solid var(--border)' }}>
            {t.clickHint}
          </div>

          {/* Scrollable table */}
          <div ref={listContainerRef} style={{ flex:1, overflowY:'auto' }}>
            {filtered.length === 0 ? (
              <div style={{ display:'flex', alignItems:'center', justifyContent:'center',
                            height:'120px', opacity:.4, fontSize:'13px' }}>{t.noComplaints}</div>
            ) : (
              <table style={{ width:'100%', borderCollapse:'collapse', fontSize:'12px' }}>
                <thead>
                  <tr style={{ background:'rgba(20,184,166,.08)', position:'sticky', top:0, zIndex:1 }}>
                    {[t.num, t.fio, t.problem, t.status, t.actions].map(col => (
                      <th key={col} style={{ padding:'7px 8px', textAlign:'left', fontWeight:600,
                                             fontSize:'11px', whiteSpace:'nowrap',
                                             borderBottom:'1px solid var(--border)' }}>{col}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {filtered.map(c => (
                    <tr key={c.id}
                        data-cid={c.id}
                        style={{
                          borderBottom: '1px solid var(--border)',
                          cursor: c.lat && c.lng ? 'pointer' : 'default',
                          background: c.id === highlightedId ? 'rgba(20,184,166,.15)' : 'transparent',
                          borderLeft: c.id === highlightedId ? '3px solid #14B8A6' : '3px solid transparent',
                          transition: 'background .15s',
                        }}
                        onClick={() => { if (c.lat && c.lng) flyTo(c); }}
                        onMouseEnter={e => {
                          e.currentTarget.style.background =
                            c.id === highlightedId ? 'rgba(20,184,166,.22)' : 'rgba(20,184,166,.07)';
                        }}
                        onMouseLeave={e => {
                          e.currentTarget.style.background =
                            c.id === highlightedId ? 'rgba(20,184,166,.15)' : 'transparent';
                        }}>

                      <td style={{ padding:'7px 8px', fontFamily:'monospace', opacity:.5, whiteSpace:'nowrap' }}>
                        {c.complaintNumber > 0 ? String(c.complaintNumber).padStart(4,'0') : '—'}
                      </td>
                      <td style={{ padding:'7px 8px', maxWidth:'80px', overflow:'hidden',
                                   textOverflow:'ellipsis', whiteSpace:'nowrap' }}>
                        {c.fio||'—'}
                      </td>
                      <td style={{ padding:'7px 8px', maxWidth:'140px' }}>
                        <div style={{ overflow:'hidden', textOverflow:'ellipsis', whiteSpace:'nowrap' }}
                             title={c.problem}>{c.problem}</div>
                        {c.lat && c.lng
                          ? <span style={{ color:'#14B8A6', fontSize:'10px' }}>📍 {c.address.slice(0,30)}</span>
                          : <span style={{ opacity:.3, fontSize:'10px' }}>нет координат</span>}
                      </td>
                      <td style={{ padding:'7px 8px' }} onClick={e => e.stopPropagation()}>
                        <select value={c.status} disabled={statusBusy===c.id}
                                onChange={e => updateStatus(c, e.target.value as Status)}
                                style={{ background:STATUS_COLORS[c.status], color:'#fff', border:'none',
                                         borderRadius:'999px', padding:'3px 6px', fontSize:'10px',
                                         fontWeight:700, cursor:'pointer', outline:'none', minWidth:'82px' }}>
                          {(['processing','in_work','done','rejected'] as Status[]).map(s => (
                            <option key={s} value={s} style={{ background:'#fff', color:'#333' }}>{sl[s]}</option>
                          ))}
                        </select>
                      </td>
                      <td style={{ padding:'7px 8px' }} onClick={e => e.stopPropagation()}>
                        <div style={{ display:'flex', gap:'4px' }}>
                          <button onClick={() => setSelected(c)}
                                  style={{ background:'#14B8A6', color:'#fff', border:'none',
                                           borderRadius:'7px', padding:'4px 8px', fontSize:'11px',
                                           fontWeight:600, cursor:'pointer' }}>{t.view}</button>
                          <button disabled={deleteBusy===c.id} onClick={() => deleteComplaint(c)}
                                  style={{ background: deleteBusy===c.id ? '#ccc' : '#EF4444',
                                           color:'#fff', border:'none', borderRadius:'7px',
                                           padding:'4px 8px', fontSize:'11px',
                                           fontWeight:600, cursor:'pointer' }}>
                            {deleteBusy===c.id ? '…' : t.del}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      </div>

      {/* ── USERS PANEL ─────────────────────────────────────── */}
      <div style={{
        padding:'8px 24px 24px', flex: showUsers ? 1 : 'none',
        visibility: showUsers ? 'visible' : 'hidden',
        pointerEvents: showUsers ? 'auto' : 'none',
        height: showUsers ? 'auto' : 0,
        overflow: showUsers ? 'visible' : 'hidden',
      }}>

        <div style={{ borderRadius:'16px', overflow:'hidden',
                      border:'1px solid var(--border)', background:'var(--card)' }}>
          {users.length === 0 ? (
            <div style={{ display:'flex', alignItems:'center', justifyContent:'center',
                          height:'160px', opacity:.4, fontSize:'13px' }}>{t.noUsers}</div>
          ) : (
            <table style={{ width:'100%', borderCollapse:'collapse', fontSize:'13px' }}>
              <thead>
                <tr style={{ background:'rgba(20,184,166,.08)' }}>
                  {[t.email, t.name, t.role, t.actions].map(col => (
                    <th key={col} style={{ padding:'12px 16px', textAlign:'left', fontWeight:600,
                                           fontSize:'12px', borderBottom:'1px solid var(--border)' }}>{col}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {users.map(u => (
                  <tr key={u.uid} style={{ borderBottom:'1px solid var(--border)' }}>
                    <td style={{ padding:'12px 16px' }}>{u.email||'—'}</td>
                    <td style={{ padding:'12px 16px' }}>{u.name||'—'}</td>
                    <td style={{ padding:'12px 16px' }}>
                      <span style={{ padding:'3px 12px', borderRadius:'999px', fontSize:'11px',
                                     fontWeight:700, color:'#fff',
                                     background: u.role==='admin' ? '#14B8A6' : '#9E9E9E' }}>
                        {u.role}
                      </span>
                    </td>
                    <td style={{ padding:'12px 16px' }}>
                      <button onClick={() => toggleRole(u)}
                              style={{ padding:'5px 14px', borderRadius:'8px', fontSize:'12px',
                                       fontWeight:600, color:'#fff', border:'none', cursor:'pointer',
                                       background: u.role==='admin' ? '#EF4444' : '#14B8A6' }}>
                        {u.role==='admin' ? t.removeAdmin : t.makeAdmin}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {/* ── STATS PANEL ─────────────────────────────────────── */}
      <div style={{
        padding:'20px 24px 32px', flex: showStats ? 1 : 'none',
        visibility: showStats ? 'visible' : 'hidden',
        pointerEvents: showStats ? 'auto' : 'none',
        height: showStats ? 'auto' : 0,
        overflow: showStats ? 'visible' : 'hidden',
      }}>
        <div style={{
          maxWidth:'1200px', margin:'0 auto',
          borderRadius:'20px', overflow:'hidden',
          border:'1px solid var(--border)', background:'var(--card)',
          boxShadow:'0 4px 24px rgba(0,0,0,.08)',
        }}>
          {/* Header */}
          <div style={{
            padding:'16px 24px', borderBottom:'1px solid var(--border)',
            display:'flex', alignItems:'center', gap:'10px',
            background:'rgba(20,184,166,.06)',
          }}>
            <span style={{ fontSize:'20px' }}>📊</span>
            <div>
              <div style={{ fontWeight:700, fontSize:'16px' }}>{t.stats}</div>
              <div style={{ fontSize:'11px', opacity:.45, marginTop:'1px' }}>
                {statsRows.length} {lang==='kk' ? 'депутат' : lang==='en' ? 'deputies' : 'депутата'}
              </div>
            </div>
          </div>

          {statsRows.length === 0 ? (
            <div style={{ display:'flex', alignItems:'center', justifyContent:'center',
                          height:'140px', opacity:.35, fontSize:'13px' }}>{t.statsNoData}</div>
          ) : (
            <table style={{ width:'100%', borderCollapse:'collapse', fontSize:'13px' }}>
              <thead>
                <tr style={{ background:'rgba(20,184,166,.07)' }}>
                  {[
                    { label: t.statsOkrug, align: 'center' as const, w: '60px' },
                    { label: t.statsExec,  align: 'left'   as const, w: 'auto' },
                    { label: t.statsTasks, align: 'center' as const, w: '80px' },
                    { label: t.statsDays,    align: 'center' as const, w: '100px' },
                    { label: t.statsOverdue,        align: 'center' as const, w: '110px' },
                    { label: t.statsPercentOverdue, align: 'center' as const, w: '160px' },
                    { label: t.statsDone,           align: 'center' as const, w: '110px' },
                    { label: t.statsPercent,        align: 'center' as const, w: '160px' },
                  ].map(col => (
                    <th key={col.label} style={{
                      padding:'12px 16px', textAlign: col.align, width: col.w,
                      fontWeight:600, fontSize:'11px', textTransform:'uppercase',
                      letterSpacing:'.04em', opacity:.55,
                      borderBottom:'1px solid var(--border)', whiteSpace:'nowrap',
                    }}>{col.label}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {statsRows.map((row, i) => (
                  <tr key={i} style={{
                    borderBottom: i < statsRows.length - 1 ? '1px solid var(--border)' : 'none',
                    transition:'background .15s',
                  }}
                  onMouseEnter={e => (e.currentTarget.style.background = 'rgba(20,184,166,.05)')}
                  onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>

                    {/* Округ */}
                    <td style={{ padding:'14px 16px', textAlign:'center' }}>
                      <span style={{
                        display:'inline-flex', alignItems:'center', justifyContent:'center',
                        width:'30px', height:'30px', borderRadius:'50%',
                        background:'rgba(20,184,166,.12)', color:'#14B8A6',
                        fontSize:'12px', fontWeight:700,
                      }}>{row.okrug}</span>
                    </td>

                    {/* ФИО */}
                    <td style={{ padding:'14px 16px', fontWeight:500 }}>{row.name}</td>

                    {/* Задачи */}
                    <td style={{ padding:'14px 16px', textAlign:'center' }}>
                      <span style={{
                        display:'inline-block', padding:'3px 12px',
                        borderRadius:'999px', fontSize:'13px', fontWeight:700,
                        background: row.total > 0 ? 'rgba(20,184,166,.12)' : 'rgba(128,128,128,.1)',
                        color: row.total > 0 ? '#14B8A6' : 'var(--text)',
                      }}>{row.total}</span>
                    </td>

                    {/* Срок — визуал: 10 дней */}
                    <td style={{ padding:'14px 16px', textAlign:'center' }}>
                      <span style={{
                        display:'inline-block', padding:'3px 12px',
                        borderRadius:'999px', fontSize:'12px', fontWeight:600,
                        background:'rgba(234,179,8,.12)', color:'#CA8A04',
                      }}>10</span>
                    </td>

                    {/* Просроченные — визуал: 0 */}
                    <td style={{ padding:'14px 16px', textAlign:'center' }}>
                      <span style={{
                        display:'inline-block', padding:'3px 12px',
                        borderRadius:'999px', fontSize:'12px', fontWeight:600,
                        background:'rgba(239,68,68,.08)', color:'#EF4444',
                      }}>0</span>
                    </td>

                    {/* Процент просроченных — визуал: 0% */}
                    <td style={{ padding:'14px 16px', textAlign:'center' }}>
                      <div style={{ display:'flex', flexDirection:'column', alignItems:'center', gap:'4px' }}>
                        <span style={{
                          display:'inline-block', padding:'3px 14px',
                          borderRadius:'999px', fontSize:'13px', fontWeight:700,
                          background: row.total > 0 ? 'rgba(239,68,68,.08)' : 'rgba(128,128,128,.1)',
                          color: row.total > 0 ? '#EF4444' : '#9E9E9E',
                        }}>{row.total > 0 ? '0%' : '—'}</span>
                        {row.total > 0 && (
                          <div style={{
                            width:'80px', height:'4px', borderRadius:'999px',
                            background:'rgba(128,128,128,.15)', overflow:'hidden',
                          }}>
                            <div style={{ width:'0%', height:'100%', borderRadius:'999px', background:'#EF4444' }} />
                          </div>
                        )}
                      </div>
                    </td>

                    {/* Выполненные — реальные данные */}
                    <td style={{ padding:'14px 16px', textAlign:'center' }}>
                      <span style={{
                        display:'inline-block', padding:'3px 12px',
                        borderRadius:'999px', fontSize:'13px', fontWeight:700,
                        background: row.done > 0 ? 'rgba(34,197,94,.12)' : 'rgba(128,128,128,.1)',
                        color: row.done > 0 ? '#16A34A' : 'var(--text)',
                      }}>{row.done}</span>
                    </td>

                    {/* Процент выполненных */}
                    {(() => {
                      const pct = row.total > 0
                        ? Math.round((row.done / row.total) * 100)
                        : null;
                      const color = pct === null ? '#9E9E9E'
                        : pct >= 75 ? '#16A34A'
                        : pct >= 40 ? '#CA8A04'
                        : '#EF4444';
                      const bg = pct === null ? 'rgba(128,128,128,.1)'
                        : pct >= 75 ? 'rgba(34,197,94,.12)'
                        : pct >= 40 ? 'rgba(234,179,8,.12)'
                        : 'rgba(239,68,68,.08)';
                      return (
                        <td style={{ padding:'14px 16px', textAlign:'center' }}>
                          <div style={{ display:'flex', flexDirection:'column', alignItems:'center', gap:'4px' }}>
                            <span style={{
                              display:'inline-block', padding:'3px 14px',
                              borderRadius:'999px', fontSize:'13px', fontWeight:700,
                              background: bg, color,
                            }}>{pct !== null ? `${pct}%` : '—'}</span>
                            {pct !== null && (
                              <div style={{
                                width:'80px', height:'4px', borderRadius:'999px',
                                background:'rgba(128,128,128,.15)', overflow:'hidden',
                              }}>
                                <div style={{
                                  width:`${pct}%`, height:'100%',
                                  borderRadius:'999px', background: color,
                                  transition:'width .4s ease',
                                }} />
                              </div>
                            )}
                          </div>
                        </td>
                      );
                    })()}
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {/* Footer hint */}
          <div style={{
            padding:'10px 24px', borderTop:'1px solid var(--border)',
            fontSize:'11px', opacity:.35, textAlign:'right',
          }}>
            {lang==='kk' ? 'Мәліметтер нақты уақытта жүктеледі'
             : lang==='en' ? 'Data loaded in real time'
             : 'Данные загружаются в реальном времени'}
          </div>
        </div>
      </div>

      {/* ── DIALOG ──────────────────────────────────────────── */}
      {selected && (
        <div style={{ position:'fixed', inset:0, zIndex:9999, display:'flex',
                      alignItems:'center', justifyContent:'center', padding:'16px',
                      background:'rgba(0,0,0,.55)', backdropFilter:'blur(4px)' }}
             onClick={() => setSelected(null)}>
          <div style={{ borderRadius:'20px', width:'100%', maxWidth:'680px', maxHeight:'88vh',
                        display:'flex', flexDirection:'column', overflow:'hidden',
                        background:'var(--card)', border:'1px solid var(--border)',
                        boxShadow:'0 25px 60px rgba(0,0,0,.3)' }}
               onClick={e => e.stopPropagation()}>

            <div style={{ padding:'16px 20px', borderBottom:'1px solid var(--border)',
                          display:'flex', alignItems:'flex-start', gap:'12px' }}>
              <div style={{ flex:1, minWidth:0 }}>
                {selected.complaintNumber > 0 && (
                  <span style={{ fontWeight:700, fontSize:'14px', color:'#14B8A6', marginRight:'8px' }}>
                    №{String(selected.complaintNumber).padStart(4,'0')}
                  </span>
                )}
                <span style={{ fontWeight:700, fontSize:'14px' }}>{selected.fio}</span>
                <div style={{ fontSize:'12px', opacity:.6, marginTop:'4px', overflow:'hidden',
                              textOverflow:'ellipsis', whiteSpace:'nowrap' }}>
                  {selected.phone} · {selected.address}
                </div>
                <div style={{ fontSize:'12px', marginTop:'3px' }}>{selected.problem}</div>
                {selected.lat && selected.lng
                  ? <div style={{ fontSize:'11px', color:'#14B8A6', marginTop:'3px' }}>
                      📍 {selected.lat.toFixed(5)}, {selected.lng.toFixed(5)}
                    </div>
                  : <div style={{ fontSize:'11px', opacity:.35, marginTop:'3px' }}>📍 {t.noCoords}</div>}
              </div>
              <div style={{ display:'flex', alignItems:'center', gap:'8px', flexShrink:0 }}>
                <select value={selected.status}
                        onChange={e => updateStatus(selected, e.target.value as Status)}
                        style={{ background:STATUS_COLORS[selected.status], color:'#fff', border:'none',
                                 borderRadius:'999px', padding:'5px 12px', fontSize:'12px',
                                 fontWeight:700, cursor:'pointer', outline:'none' }}>
                  {(['processing','in_work','done','rejected'] as Status[]).map(s => (
                    <option key={s} value={s} style={{ background:'#fff', color:'#333' }}>{sl[s]}</option>
                  ))}
                </select>
                <button onClick={() => setSelected(null)}
                        style={{ width:'32px', height:'32px', borderRadius:'50%', border:'none',
                                 background:'transparent', fontSize:'18px', cursor:'pointer',
                                 color:'var(--text)', display:'flex', alignItems:'center', justifyContent:'center' }}>
                  ✕
                </button>
              </div>
            </div>

            <div style={{ flex:1, overflowY:'auto', padding:'16px 20px' }}>
              <div style={{ fontSize:'11px', fontWeight:600, opacity:.4, textTransform:'uppercase',
                            letterSpacing:'.05em', marginBottom:'12px' }}>{t.conversation}</div>
              {selected.messages.length === 0
                ? <p style={{ fontSize:'13px', opacity:.4 }}>{t.noMessages}</p>
                : <div style={{ display:'flex', flexDirection:'column', gap:'10px' }}>
                    {selected.messages.map((msg, i) => {
                      const isUser = msg.role === 'user';
                      return (
                        <div key={i} style={{ display:'flex', flexDirection:'column',
                                              alignItems: isUser ? 'flex-end' : 'flex-start' }}>
                          {/* Подпись */}
                          <div style={{ fontSize:'10px', fontWeight:600, opacity:.45,
                                        marginBottom:'3px', paddingLeft:'4px', paddingRight:'4px' }}>
                            {isUser ? `👤 ${t.you}` : `🤖 ${t.ai}`}
                          </div>
                          {/* Пузырь */}
                          <div style={{
                            borderRadius: '18px',
                            padding: msg.imageUrl ? '4px' : '10px 14px',
                            maxWidth:'78%',
                            fontSize:'13px',
                            lineHeight:'1.5',
                            background: isUser ? '#14B8A6' : 'rgba(128,128,128,.1)',
                            color:      isUser ? '#fff'    : 'var(--text)',
                            wordBreak:'break-word',
                          }}>
                            {msg.imageUrl ? (
                              /* Фото — клик открывает полноэкранный лайтбокс */
                              <img
                                src={msg.imageUrl}
                                alt="фото"
                                onClick={() => setLightboxSrc(msg.imageUrl!)}
                                style={{ display:'block', maxWidth:'260px', maxHeight:'300px',
                                         borderRadius:'14px', objectFit:'contain',
                                         cursor:'zoom-in', background:'rgba(0,0,0,.04)' }}
                              />
                            ) : (
                              /* Текст — поддержка ссылок */
                              <span style={{ whiteSpace:'pre-wrap' }}>
                                {(msg.text ?? '').split(/(\bhttps?:\/\/\S+)/g).map((part, pi) =>
                                  /^https?:\/\//.test(part)
                                    ? <a key={pi} href={part} target="_blank" rel="noopener noreferrer"
                                         style={{ color: isUser ? '#cffafe' : '#14B8A6',
                                                  textDecoration:'underline', wordBreak:'break-all' }}>
                                        {part}
                                      </a>
                                    : part
                                )}
                              </span>
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
              }
            </div>

            <div style={{ padding:'12px 20px', borderTop:'1px solid var(--border)',
                          display:'flex', justifyContent:'flex-end', alignItems:'center' }}>
              <button disabled={deleteBusy===selected.id} onClick={() => deleteComplaint(selected)}
                      style={{ padding:'8px 24px', borderRadius:'10px', fontSize:'13px', fontWeight:600,
                               color:'#fff', border:'none', cursor:'pointer',
                               background: deleteBusy===selected.id ? '#ccc' : '#EF4444' }}>
                {deleteBusy===selected.id ? '…' : `🗑 ${t.del}`}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── LIGHTBOX — полноэкранный просмотр фото ─────────── */}
      {lightboxSrc && (
        <div
          ref={lightboxRef}
          onClick={() => { setLightboxSrc(null); }}
          style={{
            position:'fixed', inset:0, zIndex:99999,
            background:'rgba(0,0,0,.92)',
            display:'flex', alignItems:'center', justifyContent:'center',
            cursor:'zoom-out',
            overflow:'hidden',
          }}
        >
          {/* Кнопка закрыть */}
          <button
            onClick={e => { e.stopPropagation(); setLightboxSrc(null); }}
            style={{
              position:'absolute', top:'16px', right:'20px',
              width:'40px', height:'40px', borderRadius:'50%',
              background:'rgba(255,255,255,.15)', border:'none',
              color:'#fff', fontSize:'22px', cursor:'pointer',
              display:'flex', alignItems:'center', justifyContent:'center',
              lineHeight:1, zIndex:1,
            }}
          >✕</button>

          {/* Подсказка по зуму */}
          {lbT.scale > 1 && (
            <div style={{
              position:'absolute', bottom:'18px', left:'50%',
              transform:'translateX(-50%)',
              background:'rgba(0,0,0,.55)', color:'rgba(255,255,255,.75)',
              fontSize:'11px', padding:'4px 12px', borderRadius:'999px',
              pointerEvents:'none', zIndex:1, whiteSpace:'nowrap',
            }}>
              ×{lbT.scale.toFixed(1)} · клик — сбросить · колесо — зум
            </div>
          )}

          <img
            ref={lbImgRef}
            src={lightboxSrc}
            alt="фото"
            onClick={e => {
              e.stopPropagation();
              if (lbT.scale >= 3.9) {
                // Второй клик — вернуть в исходное
                setLbT({ scale: 1, tx: 0, ty: 0 });
              } else {
                // Первый клик — приблизить до ×4 в точку клика
                const newScale = 4;
                const img = e.currentTarget;
                const elemLeft = (window.innerWidth  - img.offsetWidth)  / 2;
                const elemTop  = (window.innerHeight - img.offsetHeight) / 2;
                const px = (e.clientX - elemLeft - lbT.tx) / lbT.scale;
                const py = (e.clientY - elemTop  - lbT.ty) / lbT.scale;
                const newTx = e.clientX - elemLeft - px * newScale;
                const newTy = e.clientY - elemTop  - py * newScale;
                setLbT({ scale: newScale, tx: newTx, ty: newTy });
              }
            }}
            style={{
              maxWidth:'92vw', maxHeight:'92vh',
              objectFit:'contain',
              borderRadius:'12px',
              boxShadow:'0 8px 48px rgba(0,0,0,.6)',
              cursor: lbT.scale >= 3.9 ? 'zoom-out' : 'zoom-in',
              transform: `translate(${lbT.tx}px, ${lbT.ty}px) scale(${lbT.scale})`,
              transformOrigin: '0 0',
              transition: 'transform 0.15s ease',
              userSelect: 'none',
            }}
          />
        </div>
      )}
    </div>
  );
}
