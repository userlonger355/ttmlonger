#!/usr/bin/env python3
"""Prepare the exact supplied MargyT 2.3.0 source, keeping its native code."""
from pathlib import Path
import argparse, hashlib, zipfile
from PIL import Image, ImageDraw

SOURCE_SHA = 'ff3872ec999be2ccabbf98739d3ae94d4e47041148a3de8f91c5ec1f65702ac9'

def replace(root, name, old, new):
    p = root / name
    s = p.read_text(encoding='utf-8')
    if s.count(old) != 1:
        raise RuntimeError('Unexpected source anchor: ' + name + ' / ' + old[:60])
    p.write_text(s.replace(old,new),encoding='utf-8')

def prepare(root):
    j = 'inject/java/cat/narezany/margyt/'
    replace(root,'margyt/build.py','LABEL = "MargyT"','LABEL = "TTMLonger"')
    replace(root,'margyt/build.py','SETTINGS_LABEL = "MargyT settings"','SETTINGS_LABEL = "TTMLonger settings"')
    replace(root,'margyt/build.py','"Плагин MargyT",','"Плагин TTMLonger",')
    replace(root,'margyt/build.py','"Стиль MargyT",','"Стиль TTMLonger",')
    replace(root,'margyt/artwork.py','DEFAULT_RASTER = "icons/vicecity"','DEFAULT_RASTER = "icons/ttmlonger"')
    replace(root,j+'Text.java','pick("Настройки MargyT", "Налаштування MargyT", "MargyT settings")','pick("Настройки TTMLonger", "Налаштування TTMLonger", "TTMLonger settings")')
    replace(root,j+'SettingsActivity.java','(Version.LITE ? "MargyT Lite" : "MargyT")','(Version.LITE ? "TTMLonger Lite" : "TTMLonger")')
    replace(root,j+'SettingsActivity.java','        return view;\n    }\n\n    private View idRow', '''        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        TextView build = new TextView(this);
        build.setText("0.0.1a (dev buld)  ·  Автор: @Ueod5");
        build.setTextColor(skin.text);
        build.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        build.setGravity(Gravity.CENTER);
        build.setPadding(dp(12), dp(10), dp(12), dp(10));
        build.setOnClickListener(v -> open("https://t.me/Ueod5"));
        footer.addView(build);
        TextView notice = new TextView(this);
        notice.setText("TTMLonger — изменённая сборка на основе MargyT. Не является сборкой @narezany.");
        notice.setTextColor(skin.muted());
        notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(12), dp(4), dp(12), dp(4));
        footer.addView(notice);
        footer.addView(view);
        return footer;
    }

    private View idRow''')
    replace(root,j+'Updater.java','"https://git.narez.xyz/narezany/MargyT/raw/branch/main/version.json";','""; // TTMLonger has no release channel yet; never replace this fork with an upstream APK.')
    out=root/'icons/ttmlonger';out.mkdir(exist_ok=True)
    bg=Image.new('RGBA',(512,512),(24,24,27,255));fg=Image.new('RGBA',(512,512));d=ImageDraw.Draw(fg)
    for x,y in [(100,158),(216,218)]:
        d.rounded_rectangle((x,y,x+190,y+42),radius=12,fill=(246,246,246,255))
        d.rounded_rectangle((x+74,y+28,x+116,y+160),radius=10,fill=(246,246,246,255))
    full=Image.alpha_composite(bg,fg)
    for name,img in [('background',bg),('foreground',fg),('full',full)]:img.save(out/(name+'.png'))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('archive',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    if hashlib.sha256(a.archive.read_bytes()).hexdigest()!=SOURCE_SHA: raise SystemExit('Wrong source ZIP: SHA-256 differs')
    if a.output.exists():raise SystemExit('Output must not exist')
    with zipfile.ZipFile(a.archive) as z:
        for member in z.infolist():
            path=Path(member.filename)
            if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0]!='margyt':raise ValueError('Unexpected ZIP path')
        z.extractall(a.output)
    prepare(a.output/'margyt')
