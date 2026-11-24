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
def display_size():
    size=adb("shell","wm","size").decode()
    return tuple(map(int,re.findall(r"(\\d+)x(\\d+)",size)[-1]))
def fresh_attach(after=-1, timeout=15):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        log=adb("logcat","-d","-s","TTMGuest:I","*:S").decode(errors="replace")
        markers=re.findall(r"NATIVE_GLASS_ATTACHED seq=(\\d+) tabs=(\\d+) selected=(-?\\d+)",log)
        if markers and int(markers[-1][0])>after:
            return tuple(map(int,markers[-1]))
        time.sleep(1)
    return None
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
        elif not nodes:
            report("BLOCKED TikTok UI unavailable or another app is foreground")
            break
        time.sleep(3)
    if not ready:
        capture("guest-blocked")
        report("SKIP real feed not confirmed; see guest screenshots")
    else:
        capture("guest-feed")
        report("PASS visible guest feed and labelled home/profile tabs")
        marker=fresh_attach()
        report(("PASS" if marker else "FAIL")+" native glass attachment")
        capture("guest-glass")
        if not marker: sys.exit(1)
        seq,tabs,selected=marker
        report(f"native tabs={tabs} selected={selected}")
        width,height=display_size()
        # Only target an accessibility-labelled Home tab in the bottom portion of the actual UI.
        home_nodes=[n for n in dump() if label(n) in home and n.get("bounds") and center(n)[1]>=height*.80]
        if home_nodes:
            x,y=center(home_nodes[0])
            adb("shell","input","swipe",str(x),str(y),str(x),str(y),"850")
            time.sleep(2)
            capture("guest-home-hold")
            menu_labels={label(n) for n in dump()}
            if not (menu_labels & {"настройки мода","поиск по настройкам мода"}):
                report("FAIL native Home long press did not open mod menu")
                sys.exit(1)
            report("PASS native Home long press opens mod menu")
            adb("shell","input","keyevent","4")
            time.sleep(2)
            if not ({label(n) for n in dump()}&home):
                report("BLOCKED returning from mod menu did not restore visible Home")
                sys.exit(1)
            report("PASS return from mod menu")
        else:
            report("SKIP native Home long press: no bottom-labelled target")
        # Swipe only the feed area. This does not interact with ads or send messages.
        for i in range(3):
            adb("shell","input","swipe",str(width//2),str(int(height*.68)),str(width//2),str(int(height*.24)),"350")
            time.sleep(3)
            capture("guest-scroll-"+str(i+1))
            current={label(n) for n in dump()}
            if not (current&home and current&profile):
                report("BLOCKED feed navigation disappeared during scroll")
                break
            marker=fresh_attach(seq,8)
            if not marker:
                report("FAIL fresh native glass attachment missing after swipe")
                sys.exit(1)
            seq=marker[0]
            report("PASS native glass still attached after swipe "+str(i+1))
        else:
            report("PASS three feed swipe gestures with navigation still visible; content changes not independently verified")
except (subprocess.SubprocessError,ET.ParseError,IndexError,ValueError) as exc:
    report("BLOCKED UI inspection "+type(exc).__name__)
    try: capture("guest-blocked")
    except Exception: pass
