"""Feedback on bad translations: stored so the most reported expressions can drive lexicon fixes."""

from collections import Counter
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol
from uuid import UUID, uuid4

from monet_api.documents.store import now


@dataclass(slots=True)
class FeedbackItem:
    user_id: str
    text: str
    target_lang: str
    translation: str | None = None
    comment: str | None = None
    id: UUID | None = None
    created_at: datetime | None = None


class FeedbackRepo(Protocol):
    async def add(self, item: FeedbackItem) -> UUID: ...
    async def top_reported(self, limit: int = 20) -> list[tuple[str, int]]: ...


class MemoryFeedbackRepo:
    def __init__(self) -> None:
        self.items: list[FeedbackItem] = []

    async def add(self, item: FeedbackItem) -> UUID:
        item.id, item.created_at = uuid4(), now()
        self.items.append(item)
        return item.id

    async def top_reported(self, limit: int = 20) -> list[tuple[str, int]]:
        return Counter(i.text for i in self.items).most_common(limit)
