import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = (import.meta.env.VITE_SUPABASE_URL || '').trim().replace(/\/+$/, '')
const SUPABASE_ANON_KEY = (import.meta.env.VITE_SUPABASE_ANON_KEY || '').trim()
const ONLINE_WINDOW_MS = 45 * 1000 // treat a device as online if it synced in the last 45s
const REFRESH_MS = 15 * 1000 // re-check device freshness so ONLINE/OFFLINE stays accurate

const els = {
  loginPanel: document.querySelector('#loginPanel'),
  loginForm: document.querySelector('#loginForm'),
  loginEmail: document.querySelector('#loginEmail'),
  loginPassword: document.querySelector('#loginPassword'),
  loginStatus: document.querySelector('#loginStatus'),
  appTopbar: document.querySelector('#appTopbar'),
  appContent: document.querySelector('#appContent'),
  logoutButton: document.querySelector('#logoutButton'),
  device: document.querySelector('#device'),
  deviceMeta: document.querySelector('#deviceMeta'),
  deviceStatus: document.querySelector('#deviceStatus'),
  status: document.querySelector('#status'),
  badge: document.querySelector('.badge'),
  accessToggle: document.querySelector('#accessToggle'),
  accessPanel: document.querySelector('#accessPanel'),
  accessSummary: document.querySelector('#accessSummary'),
  accessCount: document.querySelector('#accessCount'),
  permission: {
    location: document.querySelector('#location'),
    camera: document.querySelector('#camera'),
    microphone: document.querySelector('#microphone'),
    notifications: document.querySelector('#notifications'),
  },
  dashboard: document.querySelector('#dashboardView'),
  detailViews: [...document.querySelectorAll('.detail-view')],
  screenButtons: [...document.querySelectorAll('[data-screen]')],
  backButtons: [...document.querySelectorAll('[data-back]')],
  mapLabel: document.querySelector('#locationView .map-label'),
}

let devices = []
let supabase = null
let activeScreen = 'dashboardView'
let realtimeChannel = null
let refreshTimer = null
let currentUserId = null

function setStatus(message, kind = '') {
  els.status.textContent = message
  els.status.dataset.kind = kind
}

function isFresh(lastSeenAt) {
  if (!lastSeenAt) return false
  return Date.now() - new Date(lastSeenAt).getTime() < ONLINE_WINDOW_MS
}

function setOnline(online) {
  const text = online ? 'ONLINE' : 'OFFLINE'
  els.badge.textContent = text
  els.badge.classList.toggle('online', online)
  els.badge.classList.toggle('offline', !online)
  els.deviceStatus.textContent = text
  els.deviceStatus.classList.toggle('online', online)
}

function permissionText(value) {
  return value ? '✓ Diizinkan' : '⚠ Belum'
}

function renderLocation(device) {
  if (!els.mapLabel) return
  if (device?.last_lat != null && device?.last_lng != null) {
    const updated = device.location_updated_at ? new Date(device.location_updated_at).toLocaleString('id-ID') : '-'
    els.mapLabel.innerHTML = `LAST KNOWN POSITION<br><small>${device.last_lat.toFixed(5)}, ${device.last_lng.toFixed(5)} · ${updated}</small>`
  } else {
    els.mapLabel.innerHTML = 'LAST KNOWN POSITION<br><small>Belum ada data lokasi</small>'
  }
}

function renderDevice(device) {
  const keys = ['location_permission', 'camera_permission', 'microphone_permission', 'notification_permission']
  const granted = keys.filter((key) => Boolean(device?.[key])).length
  els.accessCount.textContent = `${granted}/4`
  els.accessSummary.textContent = `${granted}/4 AKSES`
  const online = isFresh(device?.last_seen_at)
  els.deviceMeta.textContent = device ? (online ? 'Terhubung dan siap dikontrol' : 'Perangkat sedang offline') : 'Belum ada perangkat'
  Object.entries(els.permission).forEach(([name, element]) => {
    const key = `${name}_permission`
    element.textContent = permissionText(device?.[key])
    element.style.color = device?.[key] ? '#69e8a8' : '#e6aa67'
  })
  setOnline(online)
  renderLocation(device)
}

function renderDevices() {
  els.device.replaceChildren()
  if (!devices.length) {
    els.device.add(new Option('Belum ada perangkat', ''))
    els.device.disabled = true
    renderDevice(null)
    return
  }
  const previous = els.device.value
  devices.forEach((device) => els.device.add(new Option(device.device_name, device.id)))
  els.device.disabled = false
  els.device.value = devices.some((d) => d.id === previous) ? previous : devices[0].id
  renderDevice(selectedDevice())
}

function selectedDevice() {
  return devices.find((device) => device.id === els.device.value) ?? null
}

async function loadDevices({ silent = false } = {}) {
  if (!supabase) return
  if (!silent) setStatus('Memuat perangkat…')
  const { data, error } = await supabase
    .from('devices')
    .select('id, device_name, last_seen_at, location_permission, camera_permission, microphone_permission, notification_permission, last_lat, last_lng, location_updated_at')
    .order('created_at', { ascending: false })

  if (error) {
    if (silent) return // transient error during background refresh: keep what is on screen
    devices = []
    renderDevices()
    setStatus(`Gagal memuat perangkat: ${error.message}`, 'error')
    return
  }

  const next = data ?? []
  if (silent && JSON.stringify(next) === JSON.stringify(devices)) {
    renderDevice(selectedDevice()) // same data, only re-evaluate ONLINE/OFFLINE
    return
  }
  devices = next
  renderDevices()
  if (!silent) {
    setStatus(devices.length ? 'Perangkat siap dikontrol.' : 'Belum ada perangkat terdaftar. Daftarkan lewat aplikasi target.', 'success')
  }
}

async function sendCommand(command) {
  const device = selectedDevice()
  if (!device || !supabase) return

  setStatus(`Mengirim perintah ${command}…`)
  const { error } = await supabase.from('device_commands').insert({
    device_id: device.id,
    command,
  })

  if (error) {
    setStatus(`Perintah gagal: ${error.message}`, 'error')
    return
  }
  setStatus(`Perintah ${command} dikirim. Menunggu perangkat merespons…`, 'success')
}

function showScreen(screenId) {
  activeScreen = screenId
  els.dashboard.hidden = screenId !== 'dashboardView'
  els.detailViews.forEach((view) => { view.hidden = view.id !== screenId })
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

els.device.addEventListener('change', () => renderDevice(selectedDevice()))

els.accessToggle.addEventListener('click', () => {
  const expanded = els.accessToggle.getAttribute('aria-expanded') === 'true'
  els.accessToggle.setAttribute('aria-expanded', String(!expanded))
  els.accessPanel.hidden = expanded
})

els.screenButtons.forEach((button) => {
  button.addEventListener('click', () => showScreen(button.dataset.screen))
})

els.backButtons.forEach((button) => {
  button.addEventListener('click', () => showScreen('dashboardView'))
})

document.querySelector('[data-command="location"]').addEventListener('click', () => sendCommand('location'))

// Camera/microphone controls intentionally stay as visual placeholders only.
// See project README: remote camera/mic activation and notification mirroring
// are out of scope for this build.
document.querySelector('#captureButton').addEventListener('click', () => {
  setStatus('Fitur pengambilan foto jarak jauh belum diaktifkan pada build ini.', 'info')
})
document.querySelector('#recordButton').addEventListener('click', () => {
  setStatus('Fitur rekam kamera jarak jauh belum diaktifkan pada build ini.', 'info')
})
document.querySelector('#audioRecordButton').addEventListener('click', () => {
  setStatus('Fitur rekam audio jarak jauh belum diaktifkan pada build ini.', 'info')
})

// ---- Auth gate --------------------------------------------------------------------------

function showLoggedOut(message) {
  els.loginPanel.hidden = false
  els.appTopbar.hidden = true
  els.appContent.hidden = true
  if (message) els.loginStatus.textContent = message
}

function showLoggedIn() {
  els.loginPanel.hidden = true
  els.appTopbar.hidden = false
  els.appContent.hidden = false
}

async function stopRealtime() {
  if (!realtimeChannel || !supabase) return
  const channel = realtimeChannel
  realtimeChannel = null
  try { await supabase.removeChannel(channel) } catch { /* ignore */ }
}

async function startRealtime() {
  await stopRealtime()
  realtimeChannel = supabase
    .channel('devices-status')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'devices' }, () => loadDevices())
    .subscribe()
}

function startRefreshTimer() {
  stopRefreshTimer()
  refreshTimer = setInterval(() => loadDevices({ silent: true }), REFRESH_MS)
}

function stopRefreshTimer() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

function projectHost() {
  try { return new URL(SUPABASE_URL).host } catch { return SUPABASE_URL }
}

function loginErrorMessage(error) {
  const raw = error?.message || String(error)
  const msg = raw.toLowerCase()
  const code = error?.code || ''
  let hint = ''
  if (msg.includes('invalid login credentials')) {
    hint = 'Email/password salah, atau user belum dibuat di Supabase (Authentication → Users).'
  } else if (msg.includes('email not confirmed')) {
    hint = 'Email user belum dikonfirmasi. Konfirmasi user di Supabase (Authentication → Users) atau matikan "Confirm email".'
  } else if (code === 'email_provider_disabled' || msg.includes('email logins are disabled') || msg.includes('provider is not enabled')) {
    hint = 'Login email dimatikan. Aktifkan di Supabase → Authentication → Providers → Email.'
  } else if (msg.includes('secret api key')) {
    hint = 'Yang terpasang adalah key rahasia (service_role/secret). Pakai anon/publishable key di VITE_SUPABASE_ANON_KEY.'
  } else if (msg.includes('invalid api key') || msg.includes('apikey')) {
    hint = 'Anon key tidak valid. Cek VITE_SUPABASE_ANON_KEY di Vercel lalu redeploy.'
  } else if (msg.includes('failed to fetch') || msg.includes('network') || msg.includes('load failed')) {
    hint = 'Tidak bisa menjangkau Supabase. Cek VITE_SUPABASE_URL dan koneksi internet.'
  }
  return `${hint ? hint + ' ' : ''}(${raw}) [proyek: ${projectHost()}]`
}

els.loginForm?.addEventListener('submit', async (event) => {
  event.preventDefault()
  if (!supabase) {
    els.loginStatus.textContent = 'Supabase belum dikonfigurasi (isi .env.local).'
    return
  }
  const submitButton = els.loginForm.querySelector('button[type="submit"]')
  if (submitButton) submitButton.disabled = true
  els.loginStatus.textContent = 'Memproses…'
  try {
    const { error } = await supabase.auth.signInWithPassword({
      email: els.loginEmail.value.trim().toLowerCase(),
      password: els.loginPassword.value,
    })
    if (error) {
      els.loginStatus.textContent = `Gagal masuk: ${loginErrorMessage(error)}`
    } else {
      els.loginPassword.value = ''
    }
  } catch (error) {
    els.loginStatus.textContent = `Gagal masuk: ${loginErrorMessage(error)}`
  } finally {
    if (submitButton) submitButton.disabled = false
  }
})

els.logoutButton?.addEventListener('click', async () => {
  if (!supabase) return
  await supabase.auth.signOut()
})

// Called for every auth event (INITIAL_SESSION, SIGNED_IN, TOKEN_REFRESHED, SIGNED_OUT) and once from
// init(). Work only restarts when the signed-in user actually changes, so an hourly token refresh
// no longer reloads the device list or re-subscribes realtime.
function handleSession(session) {
  if (session) {
    const userId = session.user?.id ?? null
    if (userId && userId === currentUserId) return
    currentUserId = userId
    showLoggedIn()
    // Deferred so no Supabase call runs inside the auth-state callback itself.
    setTimeout(() => {
      loadDevices()
      startRealtime()
      startRefreshTimer()
    }, 0)
  } else {
    currentUserId = null
    stopRefreshTimer()
    stopRealtime()
    devices = []
    renderDevices()
    showLoggedOut('Masuk dengan akun Supabase kamu untuk mengontrol perangkat milikmu.')
  }
}

async function init() {
  if (!SUPABASE_URL || !SUPABASE_ANON_KEY) {
    showLoggedOut('Supabase belum dikonfigurasi. Isi VITE_SUPABASE_URL dan VITE_SUPABASE_ANON_KEY di web-controller/.env.local (lokal) atau di Vercel → Settings → Environment Variables, lalu redeploy.')
    return
  }

  try {
    supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)
  } catch (error) {
    showLoggedOut(`Konfigurasi Supabase tidak valid: ${error.message}`)
    return
  }

  supabase.auth.onAuthStateChange((_event, session) => handleSession(session))

  const { data: { session } } = await supabase.auth.getSession()
  handleSession(session)
}

init()
