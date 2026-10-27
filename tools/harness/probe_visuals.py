"""Bounded incremental recovery of run-journaled screenshot pairs; never a visual verdict."""
import hashlib
import json
import os
from pathlib import Path
import re
import time
from PIL import Image, ImageDraw


def journal(evidence, tags, now=None):
    path = Path(evidence) / 'visual-journal.json'
    data = json.loads(path.read_text()) if path.exists() else {'captures': []}
    for tag in tags:
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', tag): raise ValueError('invalid capture tag')
        if any(r['tag'] == tag for r in data['captures']): raise ValueError('duplicate capture tag')
        data['captures'].append({'tag': tag, 'requestedAt': time.time() if now is None else now})
    temp = path.with_suffix('.tmp'); temp.write_text(json.dumps(data, indent=2)); os.replace(temp, path)


def _thumbnail(data):
    from io import BytesIO
    with Image.open(BytesIO(data)) as im:
        if im.format != 'PNG' or im.width * im.height > 3840 * 2160: raise ValueError('invalid image')
        im.verify()
    with Image.open(BytesIO(data)) as im:
        im.load()  # verify() alone does not validate compressed image data
        im.thumbnail((320, 180))
        return im.convert('RGB')


def recover(evidence, shots, now=None):
    evidence, shots = Path(evidence), Path(shots)
    path = evidence / 'visual-journal.json'
    if not path.exists(): return None
    now = time.time() if now is None else now
    entries = json.loads(path.read_text())['captures']
    if len(entries) > 12: raise ValueError('too many captures')
    dest = evidence / 'visual-originals'; dest.mkdir(exist_ok=True)
    previous_path = evidence / 'visual-index.json'
    previous = json.loads(previous_path.read_text()) if previous_path.exists() else {'captures': []}
    prior = {r['tag']: r for r in previous.get('captures', []) if r.get('state') == 'RECOVERED'}
    records, images, thumbnails = [], {}, {}
    for item in entries:
        tag, start = item['tag'], item['requestedAt']
        row = {'tag': tag, 'state': 'MISSING_OR_INCOMPLETE'}
        try:
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', tag): raise ValueError('unsafe tag')
            retained_png, retained_json = dest / (tag + '.png'), dest / (tag + '.json')
            if tag in prior:
                try:
                    if retained_png.is_symlink() or retained_json.is_symlink(): raise ValueError('symlink')
                    if retained_png.stat().st_size > 32 * 1024 * 1024 or retained_json.stat().st_size > 1024 * 1024: raise ValueError('oversized')
                    image, raw = retained_png.read_bytes(), retained_json.read_bytes()
                    if hashlib.sha256(image).hexdigest() != prior[tag]['pngSha256'] or hashlib.sha256(raw).hexdigest() != prior[tag]['metadataSha256']:
                        raise ValueError('retained hash changed')
                    images[tag] = image
                    records.append(prior[tag]); continue
                except (OSError, ValueError, KeyError, TypeError, SyntaxError, EOFError, Image.DecompressionBombError): pass
            png, meta = shots / (tag + '.png'), shots / (tag + '.json')
            if png.is_symlink() or meta.is_symlink(): raise ValueError('symlink')
            if not (start <= png.stat().st_mtime <= now + 1 and start <= meta.stat().st_mtime <= now + 1): raise ValueError('stale files')
            if png.stat().st_size > 32 * 1024 * 1024 or meta.stat().st_size > 1024 * 1024: raise ValueError('oversized')
            image = png.read_bytes(); raw = meta.read_bytes(); m = json.loads(raw)
            captured = m.get('capturedAt')
            if type(captured) not in (int, float) or not start * 1000 <= captured <= (now + 1) * 1000: raise ValueError('capture timestamp')
            thumb = _thumbnail(image)
            if png.read_bytes() != image or meta.read_bytes() != raw: raise ValueError('files changed during read')
            for name, content in ((tag + '.png', image), (tag + '.json', raw)):
                target = dest / name; tmp = dest / (name + '.tmp'); tmp.write_bytes(content); os.replace(tmp, target)
            images[tag] = image
            thumbnails[tag] = thumb
            row.update(state='RECOVERED', png=tag + '.png', metadata=tag + '.json',
                       pngSha256=hashlib.sha256(image).hexdigest(), metadataSha256=hashlib.sha256(raw).hexdigest(), capturedAt=captured)
            if tag in prior: row['repaired'] = True
        except (OSError, ValueError, KeyError, TypeError, SyntaxError, EOFError, Image.DecompressionBombError): pass
        records.append(row)
    index = {'state': 'INCREMENTAL_UNVERIFIED', 'captures': records, 'missing': sum(r['state'] != 'RECOVERED' for r in records)}
    sheet_path = evidence / 'visual-sheet.jpg'
    old_sheet_hash = previous.get('sheetSha256')
    try: sheet_valid = bool(old_sheet_hash) and hashlib.sha256(sheet_path.read_bytes()).hexdigest() == old_sheet_hash
    except OSError: sheet_valid = False
    if records == previous.get('captures') and (sheet_valid or (not images and not sheet_path.exists())): return previous
    if images:
        for tag, data in images.items():
            if tag not in thumbnails: thumbnails[tag] = _thumbnail(data)
        cols, tw, th, label = 3, 320, 180, 34
        sheet = Image.new('RGB', (cols * tw, ((len(records) + cols - 1) // cols) * (th + label)), (20, 20, 20))
        draw = ImageDraw.Draw(sheet)
        for i, row in enumerate(records):
            x, y = (i % cols) * tw, (i // cols) * (th + label)
            draw.text((x + 2, y + 2), row['tag'][:50], fill=(240, 230, 190))
            draw.text((x + 2, y + 17), row['state'], fill=(150, 230, 150) if row['state'] == 'RECOVERED' else (255, 175, 120))
            if row['state'] == 'RECOVERED': sheet.paste(thumbnails[row['tag']], (x, y + label))
        tmp = evidence / 'visual-sheet.tmp.jpg'; sheet.save(tmp, quality=82)
        index['sheetSha256'] = hashlib.sha256(tmp.read_bytes()).hexdigest()
        os.replace(tmp, sheet_path)
    else:
        sheet_path.unlink(missing_ok=True)
    # Publish the index last; failure to write the sheet leaves the old index retryable.
    tmp = previous_path.with_suffix('.tmp'); tmp.write_text(json.dumps(index, indent=2)); os.replace(tmp, previous_path)
    print('TRACE visual evidence recovered=%d expected=%d missing=%d index=%s sheet=%s' %
          (len(records) - index['missing'], len(records), index['missing'], previous_path, sheet_path if images else 'none'), flush=True)
    return index
