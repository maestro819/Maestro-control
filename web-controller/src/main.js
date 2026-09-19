import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL
const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY

const els = {
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
}

let devices = []
let supabase = null
let activeScreen = 'dashboardView'

function setStatus(message, kind = '') {
  els.status.textContent = message
  els.status.dataset.kind = kind
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

function renderDevice(device) {
  const keys = ['location_permission', 'camera_permission', 'microphone_permission', 'notification_permission']
  const granted = keys.filter((key) => Boolean(device?.[key])).length
  els.accessCount.textContent = `${granted}/4`
  els.accessSummary.textContent = `${granted}/4 AKSES`
  els.deviceMeta.textContent = device ? (device.is_online ? 'Terhubung dan siap dikontrol' : 'Perangkat sedang offline') : 'Belum ada perangkat'
  Object.entries(els.permission).forEach(([name, element]) => {
    const key = `${name}_permission`
    element.textContent = permissionText(device?.[key])
    element.style.color = device?.[key] ? '#69e8a8' : '#e6aa67'
  })
  setOnline(Boolean(device?.is_online))
}

function demoDevices() {
  return [{
    id: 'demo-device',
    device_name: 'Demo Android',
    is_online: true,
    location_permission: true,
    camera_permission: true,
    microphone_permission: true,
    notification_permission: true,
  }]
}

function renderDevices() {
  els.device.replaceChildren()
  if (!devices.length) {
    els.device.add(new Option('Belum ada perangkat', ''))
    els.device.disabled = true
    renderDevice(null)
    return
  }
  devices.forEach((device) => els.device.add(new Option(device.device_name, device.id)))
  els.device.disabled = false
  renderDevice(devices[0])
}

function selectedDevice() {
  return devices.find((device) => device.id === els.device.value) ?? null
}

async function loadDevices() {
  if (!supabase) {
    devices = demoDevices()
    renderDevices()
    setStatus('Mode demo aktif. Hubungkan Supabase untuk perangkat nyata.', 'info')
    return
  }

  setStatus('Memuat perangkat…')
  const { data, error } = await supabase
    .from('devices')
    .select('id, device_name, is_online, location_permission, camera_permission, microphone_permission, notification_permission')
    .order('created_at', { ascending: false })

  if (error) {
    devices = []
    renderDevices()
    setStatus(`Gagal memuat perangkat: ${error.message}`, 'error')
    return
  }

  devices = data ?? []
  renderDevices()
  setStatus(devices.length ? 'Perangkat siap dikontrol.' : 'Belum ada perangkat terdaftar.', 'success')
}

async function sendCommand(command) {
  const device = selectedDevice()
  if (!device) return

  if (!supabase) {
    setStatus(`Demo: ${command} dipanggil. Android belum menerima perintah.`, 'info')
    return
  }

  setStatus(`Mengirim ${command}…`)
  const { error } = await supabase.from('device_commands').insert({
    device_id: device.id,
    command,
  })

  if (error) {
    setStatus(`Perintah gagal: ${error.message}`, 'error')
    return
  }
  setStatus(`Perintah ${command} berhasil dikirim.`, 'success')
}

function showScreen(screenId) {
  activeScreen = screenId
  els.dashboard.hidden = screenId !== 'dashboardView'
  els.detailViews.forEach((view) => { view.hidden = view.id !== screenId })
  if (screenId === 'cameraView') {
    document.querySelector('#cameraState').textContent = 'Menghubungkan otomatis…'
    setTimeout(() => {
      if (activeScreen === 'cameraView') document.querySelector('#cameraState').textContent = '● LIVE — koneksi siap'
    }, 700)
  }
  if (screenId === 'microphoneView') {
    document.querySelector('#audioState').textContent = 'Menghubungkan otomatis…'
    setTimeout(() => {
      if (activeScreen === 'microphoneView') document.querySelector('#audioState').textContent = '● LIVE — audio siap'
    }, 700)
  }
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

document.querySelector('#captureButton').addEventListener('click', () => {
  setStatus('Permintaan foto diproses dari halaman Kamera.', 'info')
})

let recording = false
document.querySelector('#recordButton').addEventListener('click', (event) => {
  recording = !recording
  const button = event.currentTarget
  button.classList.toggle('recording', recording)
  button.innerHTML = recording ? '<span></span> STOP RECORDING' : '<span></span> RECORD'
  setStatus(recording ? 'Rekaman kamera dimulai.' : 'Rekaman kamera dihentikan.', 'info')
})

let audioRecording = false
let audioSeconds = 0
let audioTimer = null
document.querySelector('#audioRecordButton').addEventListener('click', (event) => {
  audioRecording = !audioRecording
  const button = event.currentTarget
  button.classList.toggle('recording', audioRecording)
  if (audioRecording) {
    audioSeconds = 0
    button.innerHTML = '<span></span> STOP RECORDING'
    audioTimer = setInterval(() => {
      audioSeconds += 1
      const m = String(Math.floor(audioSeconds / 60)).padStart(2, '0')
      const s = String(audioSeconds % 60).padStart(2, '0')
      document.querySelector('#timer').textContent = `${m}:${s}`
    }, 1000)
  } else {
    clearInterval(audioTimer)
    button.innerHTML = '<span></span> RECORD'
    setStatus('Rekaman suara dihentikan.', 'info')
  }
})

if (SUPABASE_URL && SUPABASE_ANON_KEY) {
  supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)
}

loadDevices()
if (supabase) {
  supabase
    .channel('devices-status')
    .on('postgres_changes', { event: '*', schema: 'public', table: 'devices' }, loadDevices)
    .subscribe()
}
