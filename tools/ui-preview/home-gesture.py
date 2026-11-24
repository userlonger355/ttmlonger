"""CI-only gesture fixture. No real TikTok account or network requests."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

def adb(*args):
    return subprocess.check_output(["adb", *args], text=True)

def labels():
    adb("shell","uiautomator","dump","/sdcard/gesture.xml")
    xml=adb("shell","cat","/sdcard/gesture.xml")
    return {n.attrib.get("text",""): n.attrib for n in ET.fromstring(xml).iter("node") if n.attrib.get("text")}

def wait_for(label, attempts=6):
    for _ in range(attempts):
        found=labels()
        if label in found:
            return found
        time.sleep(.3)
    raise AssertionError(f"Missing {label!r}: {list(found)}")

def center(node):
    x1,y1,x2,y2=map(int,re.findall(r"\d+",node["bounds"]))
    return (x1+x2)//2,(y1+y2)//2

def touch(kind,*values):
    adb("shell","input",kind,*map(str,values))

adb("shell","am","force-stop","dev.ttmlonger.app")
adb("shell","am","start","-W","-n","dev.ttmlonger.app/dev.ttmlonger.PreviewActivity","--es","screen","home")
views=wait_for("Главная: 0 · Другие: 0")
x,y=center(views["Главная"])
ox,oy=center(views["Друзья"])
touch("tap",x,y)
wait_for("Главная: 1 · Другие: 0")
print("HOME short tap PASS",flush=True)
touch("swipe",x,y,x,y,850)
wait_for("Поиск по настройкам мода")
print("HOME long press PASS",flush=True)
adb("shell","input","keyevent","4")
wait_for("Главная: 1 · Другие: 0")
print("HOME consumed native click PASS",flush=True)
touch("swipe",x,y,ox,oy,850)
wait_for("Главная: 1 · Другие: 0")
print("HOME movement cancels hold PASS",flush=True)
touch("swipe",ox,oy,ox,oy,850)
views=wait_for("Главная: 1 · Другие: 1")
assert "Поиск по настройкам мода" not in views
print("HOME other tab does not open mod PASS",flush=True)
