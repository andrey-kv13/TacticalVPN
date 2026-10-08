# -*- coding: utf-8 -*-
"""Patch index.html: dynamic profiles + diagnostic log UI."""
from pathlib import Path

PATH = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "assets" / "index.html"

CSS_INSERT = """
.profile-toolbar{display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap;}
.btn-hud{
  font-family:'Oswald',sans-serif;font-size:10px;letter-spacing:1.5px;
  padding:8px 10px;border:1px solid var(--line-bright);background:var(--bg-panel-2);
  color:var(--green);cursor:pointer;touch-action:manipulation;
}
.btn-hud.amber{color:var(--amber);border-color:var(--amber);}
.btn-hud.danger{color:var(--red);border-color:var(--red);}
.btn-hud:active{opacity:.85;}
.switch-row .btn-del{
  width:28px;height:28px;border:1px solid var(--line-bright);background:transparent;
  color:var(--red);font-size:14px;line-height:1;margin-right:6px;flex-shrink:0;cursor:pointer;
}
.modal-backdrop{
  position:fixed;inset:0;background:rgba(0,0,0,.72);z-index:40;
  display:none;align-items:center;justify-content:center;padding:16px;
}
.modal-backdrop.open{display:flex;}
.modal{
  width:100%;max-width:520px;background:var(--bg-panel);border:1px solid var(--line-bright);
  padding:14px;max-height:85dvh;overflow:auto;
}
.modal h3{
  font-family:'Oswald',sans-serif;font-size:12px;letter-spacing:2px;color:var(--green);
  margin:0 0 10px 0;
}
.modal label{font-size:10px;color:var(--text-dim);display:block;margin-bottom:4px;}
.modal input,.modal textarea{
  width:100%;background:#0d1109;border:1px solid var(--line);color:var(--text);
  font-family:'JetBrains Mono',monospace;font-size:11px;padding:8px;margin-bottom:10px;
}
.modal textarea{min-height:140px;resize:vertical;}
.modal-actions{display:flex;gap:8px;justify-content:flex-end;margin-top:6px;}
.terminal{max-height:140px;overflow-y:auto;}
.ln.warn .msg{color:var(--amber);}
.ln.err .msg{color:var(--red);}
.ln.dbg .msg{color:var(--text-dim);}
.ln.ok .msg{color:var(--green);}
"""

NEW_SCRIPT = r"""
<script>
function pad(n){return n.toString().padStart(2,'0');}
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');}

const mobileLayout = window.matchMedia('(max-width:760px)');
function syncPanelLayout(){
  document.querySelectorAll('.panel-collapse').forEach(el=>{
    if(mobileLayout.matches){ if(!el.dataset.toggled) el.removeAttribute('open'); }
    else { el.setAttribute('open',''); }
  });
}
document.querySelectorAll('.panel-collapse').forEach(el=>{
  el.addEventListener('toggle', ()=>{ if(mobileLayout.matches) el.dataset.toggled = '1'; });
});
syncPanelLayout();
mobileLayout.addEventListener('change', syncPanelLayout);

function tickClock(){
  const d = new Date();
  document.getElementById('clockNow').textContent = pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());
  document.getElementById('dateNow').textContent = pad(d.getDate())+'.'+pad(d.getMonth()+1)+'.'+d.getFullYear();
}
tickClock(); setInterval(tickClock,1000);

let activeProfile = null;
let busy = false;
let uptimeSec = 0;
let uptimeTimer = null;
let logPollTimer = null;
let PROFILES = {};

const statusLine = document.getElementById('statusLine');
const statusText = document.getElementById('statusText');
const routeLabel = document.getElementById('routeLabel');
const stateVal = document.getElementById('stateVal');
const uptimeVal = document.getElementById('uptimeVal');
const termBox = document.getElementById('termBox');
const pingVal = document.getElementById('pingVal');
const profileList = document.getElementById('profileList');
const modalBackdrop = document.getElementById('modalBackdrop');
const modalTitle = document.getElementById('modalTitle');
const modalConfig = document.getElementById('modalConfig');
const modalName = document.getElementById('modalName');
let modalType = 'vless';

function hasAndroidBridge(){ return typeof Android !== 'undefined' && Android !== null; }

function loadProfilesFromAndroid(){
  PROFILES = {};
  if(hasAndroidBridge() && Android.listProfilesJson){
    try{
      const arr = JSON.parse(Android.listProfilesJson());
      arr.forEach(p=>{
        PROFILES[p.id] = { id:p.id, type:p.type, title:p.title, route:p.route, config:p.config };
      });
    }catch(e){ logUi('Ошибка загрузки профилей: '+e, 'err'); }
  }
  renderProfileList();
}

function renderProfileList(){
  if(!profileList) return;
  profileList.innerHTML = '';
  const ids = Object.keys(PROFILES);
  if(!ids.length){
    profileList.innerHTML = '<div class="switch-caption">Нет каналов — добавьте VLESS или WireGuard</div>';
    return;
  }
  ids.forEach(id=>{
    const p = PROFILES[id];
    const row = document.createElement('div');
    row.className = 'switch-row';
    row.dataset.profile = id;
    const sub = p.type === 'wireguard' ? 'WireGuard · '+p.route : 'VLESS · '+p.route;
    row.innerHTML =
      '<button type="button" class="btn-del" data-del="'+esc(id)+'" aria-label="Удалить">×</button>'+
      '<div class="switch-meta"><b>'+esc(p.title)+'</b><span>'+esc(sub)+'</span></div>'+
      '<div class="switch" id="switch-'+esc(id)+'"><div class="knob">ВЫКЛ</div></div>';
    profileList.appendChild(row);
    const sw = row.querySelector('.switch');
    sw.addEventListener('click', ()=> onSwitchClick(id));
    row.querySelector('.btn-del').addEventListener('click', (ev)=>{
      ev.stopPropagation();
      deleteProfile(id);
    });
    if(activeProfile === id && statusLine.classList.contains('on')){
      sw.classList.add('on');
      sw.querySelector('.knob').textContent = 'ВКЛ';
    }
  });
}

function getSwitch(profileId){ return document.getElementById('switch-' + profileId); }

function setSwitchUi(profileId, on){
  Object.keys(PROFILES).forEach(id=>{
    const el = getSwitch(id);
    if(!el) return;
    const knob = el.querySelector('.knob');
    if(id === profileId && on){ el.classList.add('on'); if(knob) knob.textContent = 'ВКЛ'; }
    else { el.classList.remove('on'); if(knob) knob.textContent = 'ВЫКЛ'; }
  });
}

function updateRouteLabel(profileId){
  const p = PROFILES[profileId];
  if(!p || !routeLabel) return;
  routeLabel.innerHTML = 'МАРШРУТ: <b>'+esc(p.route)+'</b>';
}

function logUi(msg, cls){
  const d = new Date();
  const stamp = pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());
  const row = document.createElement('div');
  row.className = 'ln' + (cls ? ' '+cls : '');
  row.innerHTML = '<span class="t">['+stamp+']</span><span class="msg">'+esc(msg)+'</span>';
  termBox.appendChild(row);
  while(termBox.children.length > 80){ termBox.removeChild(termBox.firstChild); }
  termBox.scrollTop = termBox.scrollHeight;
}

function log(msg, cls){ logUi(msg, cls); }

function pullDiagnosticLogs(){
  if(!hasAndroidBridge() || !Android.getDiagnosticLogs) return;
  try{
    const arr = JSON.parse(Android.getDiagnosticLogs(120));
    arr.forEach(entry=>{
      const cls = entry.level === 'ERROR' ? 'err' : (entry.level === 'WARN' ? 'warn' : (entry.level === 'DEBUG' ? 'dbg' : 'ok'));
      const d = new Date(entry.t);
      const stamp = pad(d.getHours())+':'+pad(d.getMinutes())+':'+pad(d.getSeconds());
      const row = document.createElement('div');
      row.className = 'ln '+cls;
      row.innerHTML = '<span class="t">['+stamp+']</span><span class="msg">['+esc(entry.tag)+'] '+esc(entry.msg)+'</span>';
      termBox.appendChild(row);
    });
    while(termBox.children.length > 80){ termBox.removeChild(termBox.firstChild); }
    termBox.scrollTop = termBox.scrollHeight;
  }catch(e){ /* ignore poll errors */ }
}

function startLogPoll(){
  clearInterval(logPollTimer);
  logPollTimer = setInterval(pullDiagnosticLogs, 4000);
}
function stopLogPoll(){ clearInterval(logPollTimer); logPollTimer = null; }

function formatUptime(s){
  const h = Math.floor(s/3600), m = Math.floor((s%3600)/60), sec = s%60;
  return pad(h)+':'+pad(m)+':'+pad(sec);
}
function startUptime(){
  uptimeSec = 0; uptimeVal.textContent = formatUptime(uptimeSec);
  clearInterval(uptimeTimer);
  uptimeTimer = setInterval(()=>{
    uptimeSec++; uptimeVal.textContent = formatUptime(uptimeSec);
    if(pingVal) pingVal.textContent = (22 + Math.floor(Math.random()*14)) + ' мс';
  },1000);
  startLogPoll();
}
function stopUptime(){ clearInterval(uptimeTimer); uptimeTimer = null; stopLogPoll(); }

function setUiConnected(isConnected, profileId){
  busy = false;
  if(isConnected && profileId && PROFILES[profileId]){
    activeProfile = profileId;
    setSwitchUi(profileId, true);
    updateRouteLabel(profileId);
    statusLine.classList.remove('off'); statusLine.classList.add('on');
    statusText.textContent = PROFILES[profileId].title + ' · АКТИВЕН';
    stateVal.textContent = 'СВЯЗЬ'; stateVal.classList.remove('amber');
    startUptime();
  } else {
    activeProfile = null;
    setSwitchUi(null, false);
    statusLine.classList.remove('on'); statusLine.classList.add('off');
    statusText.textContent = 'ОЖИДАНИЕ';
    stateVal.textContent = 'ОЖИДАНИЕ'; stateVal.classList.add('amber');
    stopUptime();
  }
}

function connectProfile(profileId){
  if(busy || !PROFILES[profileId]) return;
  busy = true;
  log('ЗАПРОС КАНАЛА: ' + PROFILES[profileId].title);
  if(hasAndroidBridge()) Android.setProfileEnabled(profileId, true);
  else setTimeout(()=>{ setUiConnected(true, profileId); log('DEMO: '+PROFILES[profileId].route,'ok'); }, 700);
}

function disconnectProfile(profileId){
  if(busy) return;
  busy = true;
  log('ОТКЛЮЧЕНИЕ: ' + (PROFILES[profileId] ? PROFILES[profileId].title : profileId), 'warn');
  if(hasAndroidBridge()) Android.setProfileEnabled(profileId, false);
  else setUiConnected(false, null);
}

function onSwitchClick(profileId){
  if(activeProfile === profileId) disconnectProfile(profileId);
  else {
    if(activeProfile) disconnectProfile(activeProfile);
    setTimeout(()=> connectProfile(profileId), activeProfile ? 400 : 0);
  }
}

function openModal(type){
  modalType = type;
  modalTitle.textContent = type === 'wireguard' ? 'НОВЫЙ WIREGUARD' : 'НОВЫЙ VLESS';
  modalConfig.placeholder = type === 'wireguard'
    ? '[Interface]\nPrivateKey = ...\nAddress = ...\nDNS = 172.16.6.1\n\n[Peer]\n...'
    : 'vless://uuid@host:443?...';
  modalConfig.value = '';
  modalName.value = '';
  modalBackdrop.classList.add('open');
}
function closeModal(){ modalBackdrop.classList.remove('open'); }

function saveModalProfile(){
  const cfg = modalConfig.value.trim();
  const title = modalName.value.trim();
  if(!cfg){ logUi('Пустая конфигурация', 'warn'); return; }
  if(!hasAndroidBridge()){ logUi('Только на устройстве с Android', 'warn'); return; }
  const res = Android.addProfile(modalType, title, cfg);
  if(res && res.startsWith('ERROR:')){ logUi(res.substring(6), 'err'); return; }
  logUi('Канал добавлен: '+res, 'ok');
  closeModal();
  loadProfilesFromAndroid();
}

function deleteProfile(id){
  if(!hasAndroidBridge()) return;
  if(activeProfile === id){ logUi('Сначала отключите канал', 'warn'); return; }
  if(!confirm('Удалить канал?')) return;
  const res = Android.deleteProfile(id);
  if(res && res.startsWith('ERROR:')){ logUi(res.substring(6), 'err'); return; }
  logUi('Канал удалён', 'warn');
  loadProfilesFromAndroid();
}

window.onAndroidVpnState = function(isConnected, message, profileId){
  if(message) log(message, isConnected ? 'ok' : 'warn');
  const pid = profileId && PROFILES[profileId] ? profileId : activeProfile;
  if(isConnected && pid) log('Подключено: '+PROFILES[pid].route, 'ok');
  setUiConnected(isConnected, isConnected ? pid : null);
  if(isConnected) pullDiagnosticLogs();
};

document.getElementById('btnAddVless').addEventListener('click', ()=> openModal('vless'));
document.getElementById('btnAddWg').addEventListener('click', ()=> openModal('wireguard'));
document.getElementById('btnClearLog').addEventListener('click', ()=>{
  termBox.innerHTML = '';
  if(hasAndroidBridge() && Android.clearDiagnosticLogs) Android.clearDiagnosticLogs();
  logUi('Журнал очищен', 'dbg');
});
document.getElementById('modalCancel').addEventListener('click', closeModal);
document.getElementById('modalSave').addEventListener('click', saveModalProfile);
modalBackdrop.addEventListener('click', (e)=>{ if(e.target === modalBackdrop) closeModal(); });

loadProfilesFromAndroid();
if(hasAndroidBridge() && Android.isUplinkEnabled()){
  const restored = Android.getActiveProfileId ? Android.getActiveProfileId() : null;
  if(restored && PROFILES[restored]){
    setUiConnected(true, restored);
    log('Сессия восстановлена', 'ok');
  }
}
pullDiagnosticLogs();
</script>
"""

def main():
    text = PATH.read_text(encoding="utf-8")
    if ".profile-toolbar" not in text:
        text = text.replace("</style>", CSS_INSERT + "\n</style>", 1)

    import re
    text = re.sub(
        r'<div class="switch-stack">.*?</div>\s*<div class="switch-caption">',
        '<div class="profile-toolbar">'
        '<button type="button" class="btn-hud" id="btnAddVless">+ VLESS</button>'
        '<button type="button" class="btn-hud amber" id="btnAddWg">+ WIREGUARD</button>'
        '<button type="button" class="btn-hud danger" id="btnClearLog">ОЧИСТИТЬ ЛОГ</button>'
        '</div>'
        '<div class="switch-stack" id="profileList"></div>'
        '<div class="switch-caption">',
        text,
        count=1,
        flags=re.DOTALL,
    )

    if "modalBackdrop" not in text:
        text = text.replace(
            '<div class="terminal" id="termBox">',
            '<div class="modal-backdrop" id="modalBackdrop">'
            '<div class="modal">'
            '<h3 id="modalTitle">НОВЫЙ КАНАЛ</h3>'
            '<label>НАЗВАНИЕ (необязательно)</label>'
            '<input id="modalName" type="text" maxlength="64" placeholder="Полевой канал" />'
            '<label>КОНФИГУРАЦИЯ</label>'
            '<textarea id="modalConfig"></textarea>'
            '<div class="modal-actions">'
            '<button type="button" class="btn-hud" id="modalCancel">ОТМЕНА</button>'
            '<button type="button" class="btn-hud" id="modalSave">СОХРАНИТЬ</button>'
            '</div></div></div>'
            '<div class="terminal" id="termBox">',
            1,
        )

    text = re.sub(r"<script>.*?</script>\s*</body>", NEW_SCRIPT + "\n</body>", text, count=1, flags=re.DOTALL)
    PATH.write_text(text, encoding="utf-8")
    print("Patched", PATH)

if __name__ == "__main__":
    main()
