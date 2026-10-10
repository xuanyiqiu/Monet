"""Deterministic provider for tests, CI and offline development. No network, no cost."""

import json

from monet_api.llm.base import LLMError


class FakeProvider:
    name = "fake"
    model = "fake-1"

    def __init__(self, fail: bool = False) -> None:
        self.fail = fail
        self.calls = 0

    async def complete_json(self, system: str, user: str) -> dict:
        self.calls += 1
        if self.fail:
            raise LLMError("fake provider configured to fail")
        p = json.loads(user)
        tgt = p["target_lang"]
        if p["task"] == "annotate_lines":
            return {
                "lines": [
                    {
                        "id": line["id"],
                        "translation": f"[{tgt}] {line['text']}",
                        "expressions": [
                            {"text": c["text"], "kind": c["kind"], "meaning": c["meaning"], "confidence": 0.9}
                            for c in line.get("candidates", [])
                        ],
                    }
                    for line in p["lines"]
                ]
            }
        return {
            "translation": f"[{tgt}] {p['text']}",
            "note": None,
            "words": [{"text": w, "gloss": f"<{w}>", "ipa": None} for w in p["tokens"]],
            "expressions": [
                {
                    "text": c["text"],
                    "kind": c["kind"],
                    "literal": c.get("literal"),
                    "meaning": c["meaning"],
                    "origin": c.get("origin"),
                    "register": c.get("register"),
                    "equivalent": None,
                    "confidence": 0.95,
                }
                for c in p.get("candidates", [])
            ],
        }
