"""生成网页版：复制 App 的 index.html，并把内置订阅源镜像成同站点的 JSON + 最近几期音频。
浏览器不允许网页直接读取别的网站的 RSS 和音频（跨域限制），所以由 GitHub Actions 定时抓取。"""
import email.utils, hashlib, json, os, shutil, sys, urllib.request
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, '..', 'site')
ITUNES = '{http://www.itunes.com/dtds/podcast-1.0.dtd}'
MAX_FILE = 60 * 1024 * 1024
UA = {'User-Agent': 'TingDian/1.0 (podcast mirror for personal study)'}

def get(url, timeout=60):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.headers.get('Content-Type', '')

def duration(v):
    if not v: return 0
    v = v.strip()
    if v.isdigit(): return int(v)
    t = 0
    for x in v.split(':'):
        try: t = t * 60 + float(x)
        except ValueError: return 0
    return int(t)

def parse(xml):
    ch = ET.fromstring(xml).find('channel')
    items = []
    for it in ch.findall('item'):
        enc = it.find('enclosure')
        if enc is None or not enc.get('url'): continue
        try: date = int(email.utils.parsedate_to_datetime(it.findtext('pubDate', '')).timestamp() * 1000)
        except Exception: date = 0
        items.append({
            'guid': (it.findtext('guid') or enc.get('url')).strip(),
            'title': (it.findtext('title') or '未命名').strip(),
            'date': date, 'url': enc.get('url'),
            'size': int(enc.get('length') or 0) if (enc.get('length') or '0').isdigit() else 0,
            'type': enc.get('type') or '',
            'dur': duration(it.findtext(ITUNES + 'duration')),
        })
    items.sort(key=lambda x: -x['date'])
    return (ch.findtext('title') or '').strip(), items[:30]

def main():
    os.makedirs(os.path.join(OUT, 'feeds'), exist_ok=True)
    os.makedirs(os.path.join(OUT, 'audio'), exist_ok=True)
    shutil.copy(os.path.join(ROOT, '..', 'app', 'src', 'main', 'assets', 'index.html'), os.path.join(OUT, 'index.html'))
    index = {}
    for f in json.load(open(os.path.join(ROOT, 'feeds.json'))):
        try:
            title, items = parse(get(f['url'])[0])
        except Exception as e:
            print(f"[{f['id']}] 获取失败: {e}"); index[f['id']] = {'err': str(e)}; continue
        for it in items[:f.get('keep', 3)]:
            ext = '.m4a' if 'mp4' in it['type'] or 'm4a' in it['url'] else '.mp3'
            name = hashlib.sha1(it['url'].encode()).hexdigest()[:16] + ext
            try:
                data, ctype = get(it['url'], 180)
                if len(data) > MAX_FILE: continue
                open(os.path.join(OUT, 'audio', name), 'wb').write(data)
                it['local'] = 'audio/' + name
                print(f"[{f['id']}] {it['title'][:50]}  {len(data)//1024} KB")
            except Exception as e:
                print(f"[{f['id']}] 下载失败 {it['title'][:40]}: {e}")
        json.dump({'title': title, 'items': items}, open(os.path.join(OUT, 'feeds', f['id'] + '.json'), 'w'), ensure_ascii=False)
        index[f['id']] = {'title': title, 'count': len(items)}
    json.dump({'built': int(__import__('time').time() * 1000), 'feeds': index}, open(os.path.join(OUT, 'feeds', 'index.json'), 'w'), ensure_ascii=False)

if __name__ == '__main__':
    main()
