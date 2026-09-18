import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL
const SUPABASE_ANON_KEY = import.meta.env.VITE_SUPABASE_ANON_KEY

const els = {
  device: document.querySelector('#device'),
  status: document.querySelector('#status'),
  badge: document.querySelector('.badge'),
  location: document.querySelector('#location'),
  camera: document.querySelector('#camera'),
  microphone: document.querySelector('#microphone'),
  notifications: document.querySelector('#notifications'),
  buttons: [...document.querySelectorAll('[data-command]')],
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
  els.status.dataset.kind = kind
}

function setBadge(online) {
  els.badge.textContent = online ? 'ONLINE' : 'OFFLINE'
  els.badge.classList.toggle('online', online)
  els.badge.classList.toggle('offline', !online)
}

function formatPermission(value) {
  return value ? 'Diizinkan' : 'Belum diizinkan'
}

function renderDevices() {
  els.device.replaceChildren()

  if (!devices.length) {
    els.device.add(new Option('Belum ada perangkat', ''))
    els.device.disabled = true
    els.buttons.forEach((button) => { button.disabled = true })
    renderDevice(null)
    return
  }

  devices.forEach((device) => {
    els.device.add(new Option(device.device_name, device.id))
  })
  els.device.disabled = false
  els.buttons.forEach((button) => { button.disabled = false })
  renderDevice(devices[0])
}

function renderDevice(device) {
  Object.entries(permissionLabels).forEach(([key, element]) => {
    element.textContent = device ? formatPermission(device[key]) : '—'
  })
  setBadge(Boolean(device?.is_online))
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
    camera_permission: false,
    microphone_permission: false,
    notification_permission: true,
  }]
}

async function loadDevices() {
  if (!supabase) {
    devices = demoDevices()
    renderDevices()
    setStatus('Mode demo aktif. Isi environment Supabase untuk memakai perangkat nyata.', 'info')
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
    setStatus(`Demo: perintah ${command} dicatat, tetapi belum dikirim ke Android.`, 'info')
    return
  }

  els.buttons.forEach((button) => { button.disabled = true })
  setStatus(`Mengirim perintah ${command}…`)
  const { error } = await supabase.from('device_commands').insert({
    device_id: device.id,
    command,
  })
  els.buttons.forEach((button) => { button.disabled = false })

  if (error) {
    setStatus(`Perintah gagal dikirim: ${error.message}`, 'error')
    return
  }
  setStatus(`Perintah ${command} berhasil dikirim.`, 'success')
}

els.device.addEventListener('change', () => renderDevice(selectedDevice()))
els.buttons.forEach((button) => {
  button.addEventListener('click', () => sendCommand(button.dataset.command))
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
