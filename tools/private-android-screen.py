#!/usr/bin/env python3
"""Local-only, password-protected touch screen for a disposable CI test device."""
import base64
import binascii
import hmac
import json
import os
import re
import struct
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PASSWORD = os.environ.pop("TTM_REMOTE_PASSWORD", "")
if len(PASSWORD) < 20:
    raise SystemExit("Set a unique session password of at least 20 characters")
AUTH = ("ttm:" + PASSWORD).encode("utf-8")
del PASSWORD
ADB = ["adb", "-s", "127.0.0.1:5555"]
LOCK = threading.Lock()
SIZE = (0, 0)
HTML = """<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>TTMLonger — тестовый Android</title>
<style>
*{box-sizing:border-box}body{margin:0;background:#111;color:#eee;font:15px system-ui;text-align:center}
header{padding:10px;display:flex;justify-content:center;gap:8px;align-items:center;flex-wrap:wrap}
button{padding:10px 16px;border:1px solid #555;background:#252525;color:white;border-radius:12px}
img{max-width:100vw;max-height:calc(100dvh - 110px);touch-action:none;user-select:none;display:block;margin:auto}
p{font-size:12px;color:#bbb;margin:6px}#status{min-width:80px}
</style><header><button id="back">Назад</button><button id="home">Домой</button>
<button id="launch">TikTok</button><span id="status">Связь…</span></header>
<img id="screen" alt="Экран тестового Android" draggable="false">
<p>Нажимай и листай экран. Ввод данных — только клавиатурой Android.</p>
<script>
const img=document.getElementById('screen'),status=document.getElementById('status');
let down=null, busy=false, url=null;
const pos=e=>{const r=img.getBoundingClientRect();return [Math.max(0,Math.min(.999,(e.clientX-r.left)/r.width)),Math.max(0,Math.min(.999,(e.clientY-r.top)/r.height))]};
async function action(data){
 if(busy)return; busy=true;
 try{const r=await fetch('/action',{method:'POST',headers:{'Content-Type':'application/json','X-TTM-Action':'1'},body:JSON.stringify(data)});if(!r.ok)throw Error();}catch{status.textContent='Ошибка касания';}finally{busy=false;}
}
async function frame(){
 if(!down&&!busy){try{
 const r=await fetch('/screen',{cache:'no-store'});if(!r.ok)throw Error();
 const next=URL.createObjectURL(await r.blob());img.onload=()=>{if(url)URL.revokeObjectURL(url);url=next};img.src=next;status.textContent='Подключено';
 }catch{status.textContent='Нет связи';}}
 setTimeout(frame,350);
}
img.addEventListener('pointerdown',e=>{if(down)return;e.preventDefault();img.setPointerCapture(e.pointerId);down={xy:pos(e),t:performance.now()}});
img.addEventListener('pointerup',e=>{if(!down)return;const d=down;down=null;const end=pos(e),ms=Math.max(80,Math.min(1800,Math.round(performance.now()-d.t)));
 const move=Math.hypot(end[0]-d.xy[0],end[1]-d.xy[1]);
 action(move<.015&&ms<450?{kind:'tap',xy:d.xy}:{kind:'swipe',xy:d.xy,end:move<.015?d.xy:end,ms});});
img.addEventListener('pointercancel',()=>{down=null});
for(const k of ['back','home','launch'])document.getElementById(k).onclick=()=>action({kind:k});
frame();
</script></html>""".encode("utf-8")

def adb(*args):
    return subprocess.check_output(ADB + list(args), timeout=12, stderr=subprocess.DEVNULL)

def point(v):
    if not isinstance(v, list) or len(v) != 2 or any(type(x) not in (int,float) or not 0 <= x < 1 for x in v):
        raise ValueError()
    if min(SIZE) <= 0:
        raise ValueError()
    return [str(int(v[0]*SIZE[0])), str(int(v[1]*SIZE[1]))]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, body=b"", typ="text/plain; charset=utf-8", extra=None):
        self.send_response(status)
        self.send_header("Content-Type",typ)
        self.send_header("Content-Length",str(len(body)))
        self.send_header("Cache-Control","no-store, private")
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("Referrer-Policy","no-referrer")
        self.send_header("X-Frame-Options","DENY")
        self.send_header("Content-Security-Policy","default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'")
        if extra:
            for key,val in extra.items(): self.send_header(key,val)
        self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError): pass

    def authorized(self):
        try:
            header=self.headers.get("Authorization","")
            ok=header.startswith("Basic ") and hmac.compare_digest(base64.b64decode(header[6:],validate=True),AUTH)
        except (ValueError,binascii.Error): ok=False
        if not ok:
            self.reply(401,b"Authentication required",extra={"WWW-Authenticate":'Basic realm="TTMLonger test session", charset="UTF-8"'})
        return ok

    def do_GET(self):
        global SIZE
        if not self.authorized(): return
        if self.path=="/": self.reply(200,HTML,"text/html; charset=utf-8"); return
        if self.path!="/screen": self.reply(404); return
        try:
            with LOCK:
                png=adb("exec-out","screencap","-p")
                if not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png)<24: raise ValueError()
                SIZE=struct.unpack(">II",png[16:24])
            self.reply(200,png,"image/png")
        except (ValueError,subprocess.SubprocessError): self.reply(503)

    def do_POST(self):
        if not self.authorized(): return
        if self.path!="/action" or self.headers.get("X-TTM-Action")!="1" or self.headers.get("Content-Type")!="application/json":
            self.reply(403); return
        try:
            n=int(self.headers.get("Content-Length","0"))
            if not 0<n<=1024: raise ValueError()
            d=json.loads(self.rfile.read(n))
            with LOCK:
                k=d["kind"]
                if k=="tap": adb("shell","input","tap",*point(d["xy"]))
                elif k=="swipe":
                    ms=d["ms"]
                    if type(ms)!=int or not 80<=ms<=1800: raise ValueError()
                    adb("shell","input","swipe",*point(d["xy"]),*point(d["end"]),str(ms))
                elif k=="back": adb("shell","input","keyevent","4")
                elif k=="home": adb("shell","input","keyevent","3")
                elif k=="launch": adb("shell","monkey","-p","com.zhiliaoapp.musically","-c","android.intent.category.LAUNCHER","1")
                else: raise ValueError()
            self.reply(204)
        except (ValueError,TypeError,KeyError,subprocess.SubprocessError): self.reply(400)

if __name__=="__main__":
    ThreadingHTTPServer(("127.0.0.1",6080),Handler).serve_forever()
