import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL
const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY

const els = {
  device: document.querySelector('#device'),
  status: document.querySelector('#status'),
  statusMessage: document.querySelector('#statusMessage'),
  badge: document.querySelector('#onlineBadge'),
  location: document.querySelector('#location'),
  camera: document.querySelector('#camera'),
  microphone: document.querySelector('#microphone'),
  notifications: document.querySelector('#notifications'),
}

let devices = []
let supabase = null

const permissionLabels = {
  location_permission: els.location,
  camera_permission: els.camera,
  microphone_permission: els.microphone,
  notification_permission: els.notifications,
}

function setStatus(message, kind = '') {
  els.status.textContent = message
  els.statusMessage.textContent = message
  els.statusMessage.dataset.kind = kind
}

function setBadge(online) {
  els.badge.textContent = online ? 'ONLINE' : 'OFFLINE'
  els.badge.classList.toggle('online', online)
  els.badge.classList.toggle('offline', !online)
}

function formatPermission(value) {
  return value ? 'Diizinkan' : 'Belum diizinkan'
}

function renderDevice(device) {
  Object.entries(permissionLabels).forEach(([key, element]) => {
    element.textContent = device ? formatPermission(device[key]) : '—'
    element.classList.toggle('allowed', Boolean(device?.[key]))
  })
  setBadge(Boolean(device?.is_online))
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
  els.device.value = devices[0].id
  renderDevice(devices[0])
}

function selectedDevice() {
  return devices.find((device) => device.id === els.device.value) ?? null
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

async function loadDevices() {
  if (!supabase) {
    devices = demoDevices()
    renderDevices()
    setStatus('Mode demo aktif — UI siap diuji.', 'info')
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
  setStatus(devices.length ? 'Perangkat siap dikontrol.' : 'Belum ada perangkat terdaftar.', devices.length ? 'success' : '')
}

async function sendCommand(command) {
  const device = selectedDevice()
  if (!device) return

  if (!supabase) {
    setStatus(`Demo: perintah ${command} dicatat. Backend live belum diaktifkan.`, 'info')
    return
  }

  setStatus(`Mengirim perintah ${command}…`)
  const { error } = await supabase.from('device_commands').insert({
    device_id: device.id,
    command,
  })

  if (error) {
    setStatus(`Perintah gagal dikirim: ${error.message}`, 'error')
    return
  }
  setStatus(`Perintah ${command} berhasil dikirim.`, 'success')
}

function showView(name) {
  document.querySelectorAll('.view').forEach((view) => view.classList.remove('active'))
  document.querySelector(`#${name}View`).classList.add('active')
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

document.querySelectorAll('[data-screen]').forEach((button) => {
  button.addEventListener('click', () => showView(button.dataset.screen))
})

document.querySelectorAll('[data-back]').forEach((button) => {
  button.addEventListener('click', () => showView('dashboard'))
})

document.querySelectorAll('[data-command]').forEach((button) => {
  button.addEventListener('click', () => sendCommand(button.dataset.command))
})

els.device.addEventListener('change', () => renderDevice(selectedDevice()))

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
