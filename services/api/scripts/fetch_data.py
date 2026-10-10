"""Download and compile open lexicons into data/generated/.

    uv run python scripts/fetch_data.py

Sources
- chinese-xinhua (MIT): https://github.com/pwxcoo/chinese-xinhua  -> chengyu.json (~31k idioms)
"""

import json
import sys
import urllib.request
from pathlib import Path

API_ROOT = Path(__file__).resolve().parents[1]
RAW = API_ROOT / "data" / "raw"
OUT = API_ROOT / "data" / "generated"
XINHUA_IDIOMS = "https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/idiom.json"


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {url}")
        with urllib.request.urlopen(url, timeout=120) as r:  # noqa: S310 (fixed https URL)
            dest.write_bytes(r.read())
    return dest


def clean(s: str | None) -> str | None:
    if not s:
        return None
    s = s.strip().replace("　", " ")
    return s or None


def build_chengyu() -> int:
    raw = json.loads(download(XINHUA_IDIOMS, RAW / "xinhua_idiom.json").read_text(encoding="utf-8"))
    seen: set[str] = set()
    out = []
    for d in raw:
        word = (d.get("word") or "").strip()
        explanation = clean(d.get("explanation"))
        if not word or not explanation or word in seen or not 3 <= len(word) <= 12:
            continue
        seen.add(word)
        out.append(
            {
                "word": word,
                "pinyin": clean(d.get("pinyin")),
                "explanation": explanation[:300],
                "derivation": (clean(d.get("derivation")) or "")[:200] or None,
            }
        )
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "chengyu.json").write_text(
        json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )
    return len(out)


if __name__ == "__main__":
    n = build_chengyu()
    print(f"chengyu.json: {n} entries")
    sys.exit(0 if n > 30_000 else 1)
