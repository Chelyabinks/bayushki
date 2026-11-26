"""Статус участника: 🟢 в телефоне / 🌙 отложил / ⚠️ слежка отключена.

Логика «тишины»: при выключенном экране телефон специально молчит, поэтому
долгое отсутствие данных подозрительно только если последний отчёт был
«экран включён» (тогда отчёты должны идти раз в 5 минут).
"""
from __future__ import annotations

import os
from dataclasses import dataclass

# Экран включён, но отчётов нет дольше этого — значит приложение убито / нет сети.
STALE_SCREEN_ON_MS = int(os.environ.get("SPY_STALE_ON_MIN", "20")) * 60_000
# Экран выключен: запасная задача всё равно отзывается раз в ~час,
# но ночью Android откладывает её. 12 часов тишины — уже повод для ⚠️.
STALE_SCREEN_OFF_MS = int(os.environ.get("SPY_STALE_OFF_HOURS", "12")) * 3_600_000


@dataclass
class Status:
    state: str  # "active" | "idle" | "stale"
    reason: str | None = None  # для stale: "no_data" | "silent" | "permission"
    app_name: str | None = None
    since_ms: int | None = None
    last_upload_ms: int | None = None

    def to_dict(self) -> dict:
        return {
            "state": self.state,
            "reason": self.reason,
            "app_name": self.app_name,
            "since_ms": self.since_ms,
            "last_upload_ms": self.last_upload_ms,
        }


def compute_status(member, now: int) -> Status:
    last = member["last_upload_at"]
    if last is None:
        return Status("stale", reason="no_data")
    if member["usage_permission"] == 0:
        return Status("stale", reason="permission", last_upload_ms=last)
    age = now - last
    if member["screen_on"]:
        if age > STALE_SCREEN_ON_MS:
            return Status("stale", reason="silent", last_upload_ms=last)
        return Status("active", app_name=member["current_name"],
                      since_ms=member["current_since"] or member["screen_since"], last_upload_ms=last)
    if age > STALE_SCREEN_OFF_MS:
        return Status("stale", reason="silent", last_upload_ms=last)
    return Status("idle", since_ms=member["screen_since"], last_upload_ms=last)
