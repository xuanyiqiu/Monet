import re
import unicodedata

_SPACES = re.compile(r"[ \t 　]+")


def normalize(text: str) -> str:
    """NFC, unify exotic spaces, trim. Newlines are kept (they carry layout in documents)."""
    text = unicodedata.normalize("NFC", text)
    text = _SPACES.sub(" ", text)
    return "\n".join(line.strip() for line in text.strip().splitlines())


def is_han(ch: str) -> bool:
    cp = ord(ch)
    return (
        0x4E00 <= cp <= 0x9FFF
        or 0x3400 <= cp <= 0x4DBF
        or 0xF900 <= cp <= 0xFAFF
        or 0x20000 <= cp <= 0x2FA1F
    )


def has_han(text: str) -> bool:
    return any(is_han(c) for c in text)


def is_punct(ch: str) -> bool:
    return unicodedata.category(ch).startswith("P")
