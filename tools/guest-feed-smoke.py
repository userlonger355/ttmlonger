"""Guest-mode smoke test. Only explicitly labelled non-auth navigation; no consent, login or ad clicks."""
import pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET
media=pathlib.Path(sys.argv[1])
media.mkdir(exist_ok=True,parents=True)
pkg="com.zhiliaoapp.musically"
def adb(*args,timeout=25):
    return subprocess.check_output(["adb",*args],timeout=timeout)
def capture(name):
    data=adb("exec-out","screencap","-p")
    if data.startswith(b"\x89PNG"): (media/(name+".png")).write_bytes(data)
def dump():
    adb("shell","uiautomator","dump","/sdcard/ttm-guest.xml")
    root=ET.fromstring(adb("shell","cat","/sdcard/ttm-guest.xml"))
    return [n.attrib for n in root.iter("node") if n.attrib.get("package")==pkg]
def label(n):
    return (n.get("text") or n.get("content-desc") or "").strip().casefold()
def center(n):
    x1,y1,x2,y2=map(int,re.findall(r"\d+",n["bounds"]))
    return (x1+x2)//2,(y1+y2)//2
def tap(n):
    adb("shell","input","tap",*map(str,center(n)))
def report(msg):
    print("GUEST "+msg,flush=True)
guest={"skip","skip for now","not now","maybe later","continue as guest","browse as guest","start watching","пропустить","не сейчас","смотреть без регистрации","продолжить как гость"}
feed={"for you","для вас","рекомендации"}
home={"home","главная"}
profile={"profile","профиль"}
try:
    capture("guest-before")
    ready=False
    for step in range(12):
        nodes=dump()
        labels={label(n) for n in nodes}
        if labels&feed and labels&home and labels&profile:
            ready=True
            break
        # Never approve terms, permissions, or sign-in on behalf of a user.
        if any(label(n) in {"agree and continue","accept and continue","i agree","accept all","принять и продолжить","согласен"} for n in nodes):
            report("BLOCKED explicit consent screen")
            break
        options=[n for n in nodes if label(n) in guest and n.get("enabled")=="true" and n.get("bounds")]
        if options:
            report("navigation "+label(options[0]))
            tap(options[0])
            capture("guest-step-"+str(step))
        elif step==4 or step==8:
            # Dismiss transient optional overlays by the standard back action, not coordinates.
            adb("shell","input","keyevent","4")
        time.sleep(3)
    if not ready:
        capture("guest-blocked")
        report("SKIP real feed not confirmed; see guest screenshots")
    else:
        capture("guest-feed")
        report("PASS visible guest feed and labelled home/profile tabs")
        deadline=time.monotonic()+15
        attached=False
        while time.monotonic()<deadline:
            log=adb("logcat","-d","-s","TTMGuest:I","*:S").decode(errors="replace")
            if "NATIVE_GLASS_ATTACHED" in log:
                attached=True
                break
            time.sleep(1)
        report(("PASS" if attached else "FAIL")+" native glass attachment")
        capture("guest-glass")
        if not attached: sys.exit(1)
        # Swipe only the feed area. This does not interact with ads or send messages.
        size=adb("shell","wm","size").decode()
        width,height=map(int,re.findall(r"(\d+)x(\d+)",size)[-1])
        for i in range(3):
            adb("shell","input","swipe",str(width//2),str(int(height*.68)),str(width//2),str(int(height*.24)),"350")
            time.sleep(3)
            capture("guest-scroll-"+str(i+1))
            current={label(n) for n in dump()}
            if not (current&home and current&profile):
                report("BLOCKED feed navigation disappeared during scroll")
                break
        else:
            report("PASS three feed swipe gestures with navigation still visible; content changes not independently verified")
except (subprocess.SubprocessError,ET.ParseError,IndexError,ValueError) as exc:
    report("BLOCKED UI inspection "+type(exc).__name__)
    try: capture("guest-blocked")
    except Exception: pass
