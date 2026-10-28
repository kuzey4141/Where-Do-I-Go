import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDown, ArrowLeft, ArrowRight, ArrowUpRight, Check, CheckCircle2,
  ChevronDown, Compass, Globe2, Info, Loader2, Minus, MousePointer2,
  Move, Plus, RotateCcw, Search, ShieldCheck, X,
} from 'lucide-react'
import Globe from './components/Globe.jsx'

const PASSPORTS = [
  { code: 'ORDINARY', label: 'Bordo', shortName: 'Umuma mahsus', number: '01' },
  { code: 'SPECIAL', label: 'Yeşil', shortName: 'Hususi', number: '02' },
  { code: 'SERVICE', label: 'Gri', shortName: 'Hizmet', number: '03' },
  { code: 'DIPLOMATIC', label: 'Siyah', shortName: 'Diplomatik', number: '04' },
]

const STATUSES = {
  VISA_FREE: { label: 'Vizesiz', detail: 'Bu pasaport için vize muafiyeti bilgisi bulunuyor.', Icon: CheckCircle2 },
  VISA_REQUIRED: { label: 'Vize gerekli', detail: 'Seyahatin için uygun vize koşullarını incele.', Icon: ShieldCheck },
  E_VISA: { label: 'E-vize', detail: 'Elektronik vize başvurusu ve onay koşullarını incele.', Icon: Globe2 },
  VISA_ON_ARRIVAL: { label: 'Kapıda vize', detail: 'Varışta vize için geçerli koşulları incele.', Icon: ArrowDown },
  CONDITIONAL: { label: 'Koşullu giriş', detail: 'Giriş hakkı belirli koşulların sağlanmasına bağlı.', Icon: Info },
  UNKNOWN: { label: 'Bilgi netleşmedi', detail: 'Bu pasaport için kesin bir giriş koşulu sunamıyoruz.', Icon: Info },
}

function normalize(value) {
  return value.toLocaleLowerCase('tr-TR').normalize('NFD').replace(/\p{Diacritic}/gu, '').replaceAll('ı', 'i')
}

const NUMBER_WORDS = {
  sifir: 0, bir: 1, iki: 2, uc: 3, dort: 4, bes: 5, alti: 6, yedi: 7, sekiz: 8, dokuz: 9,
  on: 10, yirmi: 20, otuz: 30, kirk: 40, elli: 50, altmis: 60, yetmis: 70, seksen: 80, doksan: 90,
  yuz: 100, bin: 1000,
}
const NUMBER_WORD = '(?:s[ıi]f[ıi]r|bir|iki|[üu][çc]|d[öo]rt|be[şs]|alt[ıi]|yedi|sekiz|dokuz|on|yirmi|otuz|k[ıi]rk|elli|altm[ıi][şs]|yetmi[şs]|seksen|doksan|y[üu]z|bin)'
const WRITTEN_DURATION = new RegExp(
  `(?<!\\p{L})(${NUMBER_WORD}(?:\\s+${NUMBER_WORD})*)(?:\\s*\\((\\d+)\\))?\\s+`
  + '(g[üu]n(?:l[üu]k|de|e|[üu]|d[üu]r)?|hafta(?:l[ıi]k|ya|y[ıi]|da|d[ıi]r)?|ay(?:l[ıi]k|da|[ıi]|a|d[ıi]r)?|y[ıi]l(?:l[ıi]k|da|[ıi]|a|d[ıi]r)?)(?!\\p{L})',
  'giu',
)
const PLAIN_DURATION_UNITS = {
  gunu: 'gün', gundur: 'gün', ayi: 'ay', aydir: 'ay', yili: 'yıl', yildir: 'yıl',
  haftayi: 'hafta', haftadir: 'hafta', gunluk: 'gün', aylik: 'ay', yillik: 'yıl', haftalik: 'hafta',
}

function formatStayLimit(value) {
  if (!value) return null
  return value.replace(WRITTEN_DURATION, (original, words, parenthesized, unit) => {
    let total = 0
    let group = 0
    for (const word of normalize(words).split(' ')) {
      const number = NUMBER_WORDS[word]
      if (number === 1000) { total += (group || 1) * 1000; group = 0 }
      else if (number === 100) group = (group || 1) * 100
      else group += number
    }
    const number = total + group
    // Keep conflicting source spellings visible instead of choosing a different duration.
    if (parenthesized && Number(parenthesized) !== number) return original
    return `${number} ${unit}`
  })
    .replace(/(\d+)\s+(günü|gündür|ayı|aydır|yılı|yıldır|haftayı|haftadır)(?!\p{L})/giu,
      (_, number, unit) => `${number} ${PLAIN_DURATION_UNITS[normalize(unit)]}`)
    .replace(/(\d+)\s+(günlük|aylık|yıllık|haftalık)\s*$/giu,
      (_, number, unit) => `${number} ${PLAIN_DURATION_UNITS[normalize(unit)]}`)
}

async function getJson(path, signal) {
  const response = await fetch(path, { signal, headers: { Accept: 'application/json' } })
  if (!response.ok) throw new Error('Bilgiler şu anda alınamıyor.')
  const value = await response.json()
  if (!Array.isArray(value)) throw new Error('Bilgiler şu anda alınamıyor.')
  return value
}

function safeSource(value) {
  try {
    const url = new URL(value)
    return ['https:', 'http:'].includes(url.protocol) ? url.href : null
  } catch {
    return null
  }
}

function dateLabel(value) {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const date = new Date(value + 'T12:00:00Z')
  return Number.isNaN(date.getTime()) ? null : new Intl.DateTimeFormat('tr-TR', {
    day: 'numeric', month: 'long', year: 'numeric',
  }).format(date)
}

function PassportIcon() {
  return <svg viewBox="0 0 32 38" fill="none" aria-hidden="true">
    <rect x="3" y="2" width="25" height="33" rx="2" stroke="currentColor" strokeWidth="1.4" />
    <path d="M7 2v33M12 29h11" stroke="currentColor" strokeWidth="1.2" />
    <circle cx="17.5" cy="16" r="6" stroke="currentColor" strokeWidth="1.1" />
    <ellipse cx="17.5" cy="16" rx="2.6" ry="6" stroke="currentColor" strokeWidth="1" />
    <path d="M11.5 16h12" stroke="currentColor" strokeWidth="1" />
  </svg>
}

function HowItWorks({ open, onClose }) {
  const dialog = useRef(null)
  useEffect(() => {
    if (open && !dialog.current.open) dialog.current.showModal()
    else if (!open && dialog.current.open) dialog.current.close()
  }, [open])
  return <dialog ref={dialog} className="guide-dialog" aria-labelledby="guide-title" onClose={onClose}
    onClick={(event) => { if (event.target === dialog.current) onClose() }}>
    <div className="guide-body">
      <button className="icon-button guide-close" onClick={onClose} aria-label="Rehberi kapat"><X size={20} /></button>
      <span className="eyebrow">BİR SONRAKİ ROTA</span>
      <h2 id="guide-title">Merak ettiğin yerden başla.</h2>
      {[
        ['01', 'Pasaportunu seç.', 'Bordo, yeşil, gri veya siyah. Giriş koşulları seçtiğin pasaporta göre değişir.'],
        ['02', 'Dünyayı keşfet.', 'Küreyi sürükle, tekerlekle yaklaş. Bir ülkenin üzerinde bekle veya adını ara.'],
        ['03', 'Ülkene yakından bak.', 'Ülkeyi seç; tanıtımını, kalış süresini ve kaynaklı giriş koşullarını incele.'],
      ].map(([number, title, text]) => <div className="guide-step" key={number}>
        <span>{number}</span><div><h3>{title}</h3><p>{text}</p></div>
      </div>)}
      <p className="guide-note">Bilgiler değişebilir. Seyahatinden önce resmî kaynaktaki güncel koşulları kontrol et.</p>
      <button className="text-button guide-cta" onClick={onClose}>Keşfetmeye başla <ArrowRight size={16} /></button>
    </div>
  </dialog>
}

function CountryPanel({ country, visible, passport, data, onClose, onRetry }) {
  const [expanded, setExpanded] = useState(false)
  useEffect(() => setExpanded(false), [country?.isoCode, passport.code])
  if (!country) return <aside className="country-panel" aria-hidden="true" />
  const domestic = country.isoCode === 'TR'
  const current = data.countryCode === country.isoCode
  const loading = !current || data.loading
  const rule = current ? data.rules.find((item) => item.passportType === passport.code) : null
  const info = STATUSES[rule?.visaStatus] || STATUSES.UNKNOWN
  const source = safeSource(rule?.sourceUrl)
  const date = dateLabel(rule?.verifiedAt)
  const stay = formatStayLimit(rule?.stayLimit)
  return <aside className={'country-panel' + (visible ? ' is-visible' : '')}
    aria-label={country.name + ' ülke bilgileri'} aria-hidden={!visible} inert={!visible}>
    <div className="panel-topline">
      <span><span className="tiny-cross">+</span> ÜLKE DOSYASI <span className="panel-index">/ {country.isoCode}</span></span>
      <button className="icon-button panel-close" onClick={onClose} aria-label="Ülke bilgilerini kapat"><X size={18} /></button>
    </div>
    <div className="panel-scroll">
      <div className="country-heading">
        <span className="country-code">{country.isoCode}<span /></span>
        <div><span className="eyebrow subtle">YENİ BİR KOORDİNAT</span><h1>{country.name}</h1></div>
      </div>
      <section className="about-section" aria-label="Ülke hakkında">
        <h2>Ülke hakkında</h2>
        <p>{country.summary || 'Bu ülkenin kısa tanıtımı henüz eklenmemiş.'}</p>
      </section>
      <div className="section-divider"><span /> <Compass size={13} /> <span /></div>
      <section className="entry-section" aria-label="Pasaport ve giriş koşulları">
        <div className="entry-heading"><span className="eyebrow">GİRİŞ KOŞULLARI</span><span className="passport-tag">{passport.label} pasaport</span></div>
        {domestic ? <div className="domestic-note">
          <Globe2 size={24} /><h3>Başladığın yer.</h3>
          <p>Türkiye, bu rehberin çıkış noktası. Yurt dışı giriş koşulları için başka bir ülke seçebilirsin.</p>
        </div> : loading ? <div className="entry-loading" role="status"><Loader2 size={19} className="spin" /><span>Giriş koşulları yükleniyor</span><div className="skeleton-line" /><div className="skeleton-line short" /></div>
          : data.error ? <div className="entry-error" role="alert"><Info size={20} /><p>Giriş koşulları yüklenemedi.</p><button className="text-button" onClick={onRetry}>Tekrar dene <RotateCcw size={13} /></button></div>
          : <>
            <div className={'visa-status status-' + (rule?.visaStatus || 'UNKNOWN').toLowerCase()}>
              <span className="status-icon"><info.Icon size={23} strokeWidth={1.3} /></span>
              <div><h3>{info.label}</h3><p>{info.detail}</p></div>
            </div>
            <div className="stay-card">
              <div className="stay-label"><span className="eyebrow">KALIŞ SÜRESİ</span><span className="tiny-cross">+</span></div>
              <p className={'stay-value' + ((stay?.length || 0) > 60 ? ' is-long' : '')}>{stay || 'Belirtilmemiş'}</p>
              {!stay && <span className="stay-note">Süresiz kalış anlamına gelmez.</span>}
            </div>
            {rule?.conditions && <div className="conditions">
              <h2>Koşullar ve notlar</h2>
              <p className={expanded ? '' : 'is-clamped'}>{rule.conditions}</p>
              {rule.conditions.length > 190 && <button className="disclosure-button" onClick={() => setExpanded(!expanded)} aria-expanded={expanded}>
                {expanded ? 'Daha az göster' : 'Tüm koşulları oku'} <ChevronDown size={13} className={expanded ? 'is-rotated' : ''} />
              </button>}
            </div>}
            <div className="source-row">
              {source ? <a href={source} target="_blank" rel="noopener noreferrer">Resmî kaynağı aç <ArrowUpRight size={15} /></a> : <span>Kaynak bilgisi henüz eklenmemiş.</span>}
              {date && <span title="Kaynağın işlendiği tarih">Veri tarihi · {date}</span>}
            </div>
          </>}
      </section>
      <p className="panel-footnote"><Info size={13} /> Giriş koşulları değişebilir. Seyahatinden önce resmî kaynağı kontrol et.</p>
    </div>
  </aside>
}

export default function App() {
  const [countries, setCountries] = useState([])
  const [passportTypes, setPassportTypes] = useState([])
  const [catalogLoading, setCatalogLoading] = useState(true)
  const [catalogError, setCatalogError] = useState(false)
  const [catalogAttempt, setCatalogAttempt] = useState(0)
  const [selectedCode, setSelectedCode] = useState(null)
  const [lastCountry, setLastCountry] = useState(null)
  const [hover, setHover] = useState(null)
  const [passportCode, setPassportCode] = useState(() => {
    try { return PASSPORTS.some((item) => item.code === localStorage.getItem('wdig-passport')) ? localStorage.getItem('wdig-passport') : 'ORDINARY' } catch { return 'ORDINARY' }
  })
  const [query, setQuery] = useState('')
  const [searchOpen, setSearchOpen] = useState(false)
  const [activeResult, setActiveResult] = useState(0)
  const [guideOpen, setGuideOpen] = useState(false)
  const [zoomSignal, setZoomSignal] = useState({ direction: 0, id: 0 })
  const [resetSignal, setResetSignal] = useState(0)
  const [ruleAttempt, setRuleAttempt] = useState(0)
  const [ruleData, setRuleData] = useState({ countryCode: null, rules: [], loading: false, error: false })
  const [reducedMotion, setReducedMotion] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches)
  const cache = useRef(new Map())
  const searchBox = useRef(null)
  const searchInput = useRef(null)

  useEffect(() => {
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)')
    const update = () => setReducedMotion(preference.matches)
    preference.addEventListener('change', update)
    return () => preference.removeEventListener('change', update)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    setCatalogLoading(true)
    setCatalogError(false)
    Promise.all([getJson('/api/countries', controller.signal), getJson('/api/passport-types', controller.signal)])
      .then(([items, passports]) => {
        setCountries(items.filter((item) => typeof item.isoCode === 'string' && typeof item.name === 'string'))
        setPassportTypes(passports)
      })
      .catch((error) => { if (error.name !== 'AbortError') setCatalogError(true) })
      .finally(() => { if (!controller.signal.aborted) setCatalogLoading(false) })
    return () => controller.abort()
  }, [catalogAttempt])

  const countryMap = useMemo(() => new Map(countries.map((country) => [country.isoCode, country])), [countries])
  const country = countryMap.get(selectedCode)
  const passport = PASSPORTS.find((item) => item.code === passportCode) || PASSPORTS[0]
  const passportName = passportTypes.find((item) => item.code === passportCode)?.name || passport.shortName + ' pasaport'
  const results = useMemo(() => {
    const search = normalize(query.trim())
    return countries.filter((item) => !search || normalize(item.name).includes(search) || normalize(item.isoCode).includes(search))
      .sort((first, second) => first.name.localeCompare(second.name, 'tr')).slice(0, 8)
  }, [countries, query])

  useEffect(() => {
    setActiveResult(0)
  }, [query])

  useEffect(() => {
    if (searchOpen) searchBox.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' })
  }, [activeResult, searchOpen])

  useEffect(() => {
    if (!selectedCode) return
    const saved = cache.current.get(selectedCode)
    if (saved) {
      setRuleData({ countryCode: selectedCode, rules: saved, loading: false, error: false })
      return
    }
    const controller = new AbortController()
    setRuleData({ countryCode: selectedCode, rules: [], loading: true, error: false })
    getJson('/api/countries/' + encodeURIComponent(selectedCode) + '/entry-rules', controller.signal)
      .then((rules) => {
        cache.current.set(selectedCode, rules)
        setRuleData({ countryCode: selectedCode, rules, loading: false, error: false })
      })
      .catch((error) => {
        if (error.name !== 'AbortError') setRuleData({ countryCode: selectedCode, rules: [], loading: false, error: true })
      })
    return () => controller.abort()
  }, [selectedCode, ruleAttempt])

  const selectCountry = useCallback((code) => {
    const next = countryMap.get(code)
    if (!next) return
    setSelectedCode(code)
    setLastCountry(next)
    setHover(null)
    setSearchOpen(false)
    setQuery('')
    searchInput.current?.blur()
  }, [countryMap])

  const clearSelection = useCallback(() => {
    setSelectedCode(null)
    setHover(null)
    setResetSignal((value) => value + 1)
  }, [])

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape' && !guideOpen) {
        if (searchOpen) setSearchOpen(false)
        else clearSelection()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [searchOpen, guideOpen, clearSelection])

  const handleHover = useCallback((next) => setHover(next), [])
  const hoveredCountry = hover ? countryMap.get(hover.code) : null
  const zoom = (direction) => setZoomSignal((value) => ({ direction, id: value.id + 1 }))
  const setPassport = (code) => {
    setPassportCode(code)
    try { localStorage.setItem('wdig-passport', code) } catch { /* Preferences are optional. */ }
  }

  return <div className={'app' + (country ? ' is-selected' : '') + (reducedMotion ? ' reduced-motion' : '')}>
    <div className="screen-texture" aria-hidden="true" />
    <header className="site-header">
      <nav className="header-nav" aria-label="Ana menü">
        <button className="nav-link is-active" onClick={clearSelection}>KEŞFET</button>
        <button className="nav-link how-link" onClick={() => setGuideOpen(true)}>NASIL ÇALIŞIR?</button>
      </nav>
      <button className="brand" onClick={clearSelection} aria-label="Where Do I Go, başlangıca dön">
        <Globe2 size={24} strokeWidth={1.25} /><span>WHERE DO I GO<span className="brand-dot">.</span></span>
      </button>
      <div className="header-caption"><span className="status-dot" /> TÜRK PASAPORTLARI İÇİN</div>
    </header>

    <main className="explorer" aria-label="Dünya üzerinden ülke keşfet">
      <div className="intro-copy" aria-hidden={!!country}>
        <span className="eyebrow"><span className="red-stroke" /> SINIRLARIN ÖTESİNDE</span>
        <h1>Sıradaki{' '}<br />durağın{' '}<br />neresi<span>?</span></h1>
        <p>Pasaportunu seç.<br />Dünyadaki ihtimallerini keşfet.</p>
        <div className="intro-coordinate"><span>40° 00′ N</span><span>35° 00′ E</span><span className="coordinate-line" /></div>
      </div>
      <div className="explore-note" aria-hidden={!!country}>
        <span className="note-index">01 — KEŞİF MODU</span>
        <p>Bir ülkeye dokun.<br />Yeni bir ihtimal aç.</p>
        <span className="note-cross">+</span>
      </div>

      <div ref={searchBox} className="search-wrapper" onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setSearchOpen(false)
      }}>
        <div className={'search-field' + (searchOpen ? ' is-focused' : '')}>
          <Search size={17} strokeWidth={1.5} />
          <input ref={searchInput} type="search" value={query} placeholder="Bir ülke ara..."
            aria-label="Ülke ara" role="combobox" aria-expanded={searchOpen}
            aria-controls="country-results" aria-autocomplete="list"
            aria-activedescendant={searchOpen && results[activeResult] ? 'result-' + results[activeResult].isoCode : undefined}
            disabled={catalogLoading || catalogError}
            onFocus={() => setSearchOpen(true)}
            onChange={(event) => { setQuery(event.target.value); setSearchOpen(true) }}
            onKeyDown={(event) => {
              if (event.key === 'ArrowDown') { event.preventDefault(); setSearchOpen(true); setActiveResult((value) => Math.min(value + 1, results.length - 1)) }
              if (event.key === 'ArrowUp') { event.preventDefault(); setActiveResult((value) => Math.max(value - 1, 0)) }
              if (event.key === 'Enter' && results[activeResult]) { event.preventDefault(); selectCountry(results[activeResult].isoCode) }
              if (event.key === 'Escape') { event.stopPropagation(); setSearchOpen(false); searchInput.current?.blur() }
            }} />
          <span className="search-shortcut"><Compass size={13} /></span>
        </div>
        {searchOpen && <div className="search-results">
          <div className="result-caption"><span>ÜLKELER</span><span>{results.length ? 'BİR ROTA SEÇ' : 'SONUÇ YOK'}</span></div>
          <ul role="listbox" id="country-results" aria-label="Ülke sonuçları">
            {results.map((item, index) => <li key={item.isoCode} role="option" id={'result-' + item.isoCode} aria-selected={index === activeResult}>
              <button tabIndex={-1} data-active={index === activeResult}
                onMouseDown={(event) => event.preventDefault()}
                onMouseEnter={() => setActiveResult(index)} onClick={() => selectCountry(item.isoCode)}>
                <span className="result-code">{item.isoCode}</span><span>{item.name}</span><ArrowUpRight size={14} />
              </button>
            </li>)}
          </ul>
          {!results.length && <p className="no-results">Bu isimde bir ülke bulunamadı. Başka bir isim veya ülke kodu dene.</p>}
        </div>}
      </div>

      <div className="globe-stage">
        <div className="globe-halo" aria-hidden="true" />
        <span className="globe-axis axis-top" aria-hidden="true">N</span>
        <span className="globe-axis axis-bottom" aria-hidden="true">S</span>
        <Globe countries={countries} selectedCode={selectedCode} onHover={handleHover}
          onSelect={selectCountry} zoomSignal={zoomSignal} resetSignal={resetSignal} reducedMotion={reducedMotion} />
      </div>

      {hoveredCountry && !searchOpen && hover.code !== selectedCode && <div className="country-preview"
        style={{ left: Math.min(Math.max(hover.x + 22, 12), window.innerWidth - 284), top: Math.min(Math.max(hover.y - 35, 80), window.innerHeight - 170) }}>
        <div className="preview-topline"><span>{hover.code}</span><span>YAKINDAN BAK</span></div>
        <h2>{hoveredCountry.name}</h2>
        <p>{hoveredCountry.summary?.split(/(?<=[.!?])\s/)[0] || 'Giriş koşullarını ve ülke bilgilerini keşfet.'}</p>
        <span className="preview-action">Keşfetmek için tıkla <ArrowUpRight size={13} /></span>
      </div>}

      <CountryPanel country={country || lastCountry} visible={!!country} passport={passport} data={ruleData}
        onClose={clearSelection} onRetry={() => setRuleAttempt((value) => value + 1)} />

      <div className="passport-dock" aria-label="Pasaport türü seçimi">
        <div className="dock-caption"><span className="eyebrow">PASAPORTUNU SEÇ</span><span className="dock-line" /><span>04 TÜR</span></div>
        <div className="passport-options">
          {PASSPORTS.map((item) => <button key={item.code}
            className={'passport-option' + (item.code === passportCode ? ' is-active' : '')}
            onClick={() => setPassport(item.code)} aria-pressed={item.code === passportCode}
            aria-label={item.label + ' pasaport'}>
            <PassportIcon /><span className="passport-option-copy"><strong>{item.label}</strong><span>{item.shortName}</span></span>
            <span className="passport-number">{item.code === passportCode ? <Check size={11} /> : item.number}</span>
          </button>)}
        </div>
        <span className="dock-hint">{country ? passportName + ' için giriş koşullarını görüntülüyorsun.' : 'Gidebileceğin yerleri kendi pasaportunla keşfet.'}</span>
      </div>

      <div className="globe-controls" aria-label="Küre kontrolleri">
        <button className="icon-button" onClick={() => zoom(1)} aria-label="Dünyaya yaklaş" title="Yaklaş"><Plus size={19} strokeWidth={1.4} /></button>
        <span className="control-separator" />
        <button className="icon-button" onClick={() => zoom(-1)} aria-label="Dünyadan uzaklaş" title="Uzaklaş"><Minus size={19} strokeWidth={1.4} /></button>
        <span className="control-separator" />
        <button className="icon-button" onClick={() => setResetSignal((value) => value + 1)} aria-label="Küre görünümünü sıfırla" title="Görünümü sıfırla"><RotateCcw size={15} strokeWidth={1.4} /></button>
      </div>
      {country && <button className="back-to-globe text-button" onClick={clearSelection}><ArrowLeft size={13} /> DÜNYAYA DÖN</button>}
      {catalogLoading && <div className="catalog-status" role="status"><Loader2 size={14} className="spin" /> Dünya rehberin hazırlanıyor</div>}
      {catalogError && <div className="connection-error" role="alert"><Info size={19} /><div><strong>Ülke bilgilerine ulaşılamadı.</strong><p>Bağlantıyı kontrol edip yeniden dene.</p></div><button className="text-button" onClick={() => setCatalogAttempt((value) => value + 1)}>Tekrar dene <RotateCcw size={13} /></button></div>}
    </main>
    <footer className="site-footer">
      <div className="footer-stats"><span className="status-dot" /><span>{catalogLoading || catalogError ? '—' : countries.filter((item) => item.isoCode !== 'TR').length} DESTİNASYON</span><span className="footer-divider">/</span><span>4 PASAPORT</span></div>
      <div className="interaction-hints"><span><Move size={12} /> SÜRÜKLE VE DÖNDÜR</span><span><MousePointer2 size={12} /> YAKLAŞ VE KEŞFET</span></div>
      <button className="footer-about" onClick={() => setGuideOpen(true)}>MERAKLA BAŞLAR <ArrowUpRight size={12} /></button>
    </footer>
    <div className="sr-only" aria-live="polite">{country ? country.name + ' seçildi. ' + passport.label + ' pasaport bilgileri gösteriliyor.' : ''}</div>
    <HowItWorks open={guideOpen} onClose={() => setGuideOpen(false)} />
  </div>
}
