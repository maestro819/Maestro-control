import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL
const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY
const ONLINE_WINDOW_MS = 45 * 1000 // treat a device as online if it synced in the last 45s

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

async function loadDevices() {
  if (!supabase) return
  setStatus('Memuat perangkat…')
  const { data, error } = await supabase
    .from('devices')
    .select('id, device_name, last_seen_at, location_permission, camera_permission, microphone_permission, notification_permission, last_lat, last_lng, location_updated_at')
    .order('created_at', { ascending: false })

  if (error) {
    devices = []
    renderDevices()
    setStatus(`Gagal memuat perangkat: ${error.message}`, 'error')
    return
  }

  devices = data ?? []
  renderDevices()
  setStatus(devices.length ? 'Perangkat siap dikontrol.' : 'Belum ada perangkat terdaftar. Daftarkan lewat aplikasi target.', 'success')
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

async function startRealtime() {
  if (realtimeChannel) await supabase.removeChannel(realtimeChannel)
  realtimeChannel = supabase
    .channel('devices-status')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'devices' }, loadDevices)
    .subscribe()
}

els.loginForm?.addEventListener('submit', async (event) => {
  event.preventDefault()
  if (!supabase) {
    els.loginStatus.textContent = 'Supabase belum dikonfigurasi (isi .env.local).'
    return
  }
  els.loginStatus.textContent = 'Memproses…'
  const { error } = await supabase.auth.signInWithPassword({
    email: els.loginEmail.value.trim(),
    password: els.loginPassword.value,
  })
  if (error) {
    els.loginStatus.textContent = `Gagal masuk: ${error.message}`
  }
})

els.logoutButton?.addEventListener('click', async () => {
  if (!supabase) return
  await supabase.auth.signOut()
})

async function init() {
  if (!SUPABASE_URL || !SUPABASE_ANON_KEY) {
    showLoggedOut('Supabase belum dikonfigurasi. Isi VITE_SUPABASE_URL dan VITE_SUPABASE_ANON_KEY di web-controller/.env.local.')
    return
  }

  supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)

  supabase.auth.onAuthStateChange((_event, session) => {
    if (session) {
      showLoggedIn()
      loadDevices()
      startRealtime()
    } else {
      showLoggedOut('Masuk dengan akun Supabase kamu untuk mengontrol perangkat milikmu.')
      devices = []
    }
  })

  const { data: { session } } = await supabase.auth.getSession()
  if (session) {
    showLoggedIn()
    loadDevices()
    startRealtime()
  } else {
    showLoggedOut()
  }
}

init()
