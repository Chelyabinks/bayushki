"""Игра: проверка ночей после каждой загрузки, вылет, конец испытания, события для утренних уведомлений.

Отбой и подъём задаёт организатор, но у каждого участника они наступают по ЕГО местному времени
(часовой пояс телефон присылает сам). Поэтому почти все функции здесь работают «для участника».
"""
from __future__ import annotations

from datetime import datetime, time, timezone

from . import rules
from .db import Database, now_ms

LOOKBACK_MS = 8 * 24 * 3_600_000
# После последнего подъёма ждём данных от оставшихся, но не дольше этого.
FINISH_GRACE_MS = 6 * 3_600_000
# Если за ночь так и не пришло данных до этого часа (по местному времени участника) — «отключил слежку».
SILENT_DEADLINE_HOUR = 12


def tz(m, g) -> int:
    """Смещение участника от UTC в минутах (пока телефон его не прислал — как у группы)."""
    return m["tz_offset_min"] if m is not None and m["tz_offset_min"] is not None else g["tz_offset_min"]


def app_rules(db: Database, s: dict, pkgs: list[str]) -> rules.AppRules:
    cats = {p: rules.app_categories(p, r["android_cat"]) for p, r in db.app_catalog(pkgs).items()}
    return rules.AppRules.from_settings(s, cats)


def hhmm(ms: int, offset_min: int) -> str:
    return datetime.fromtimestamp((ms + offset_min * 60_000) / 1000, tz=timezone.utc).strftime("%H:%M")


def evaluate_member(db: Database, member_id: int, now: int | None = None) -> rules.Verdict | None:
    """Проверяет все ночи, которые ещё могут повлиять на участника. Если нарушение есть — вылет."""
    now = now or now_ms()
    m = db.member(member_id)
    if m is None or m["eliminated_at"] is not None:
        return None
    g = db.group(m["group_id"])
    if g["finished_at"] is not None:
        return None
    versions = db.settings_versions(g["id"])
    offset = tz(m, g)
    start = max(m["playing_since"], now - LOOKBACK_MS)
    end = member_round_end(db, g, m)
    if end is not None:
        now = min(now, end)  # после своей последней ночи испытания уже ничего не считается
    if start >= now:
        return None

    cancels = db.night_cancels(g["id"])
    for s, night in rules.nights_between(start, now, versions, offset):
        if not rules.night_active(night, s, offset):
            continue
        cancel_at = cancels.get(night.wake_date)
        lo = max(night.bed_ms, start)
        hi = min(night.wake_ms, now, cancel_at or night.wake_ms)
        if lo >= hi:
            continue
        sessions = [(r["pkg"], r["start_ms"], r["end_ms"]) for r in db.sessions_between(member_id, lo, hi)]
        used = db.intervals_between("used_intervals", member_id, lo, hi)
        ar = app_rules(db, s, [p for p, _, _ in sessions])
        v = rules.evaluate_night(night, s, ar, used, sessions, window_start=start, window_end=hi)
        if v is not None:
            name = None
            if v.pkg:
                cat = db.app_catalog([v.pkg]).get(v.pkg)
                name = cat["name"] if cat else v.pkg
            _eliminate(db, g, m, v, name)
            return v
    return None


def tracking_off(db: Database, member_id: int, now: int | None = None) -> bool:
    """Телефон сообщил, что доступ к статистике отозван. Ночью (если так настроено) — вылет."""
    now = now or now_ms()
    m = db.member(member_id)
    if m is None or m["eliminated_at"] is not None:
        return False
    g = db.group(m["group_id"])
    if g["finished_at"] is not None:
        return False
    end = member_round_end(db, g, m)
    if end is not None and now >= end:
        return False
    cur = current_night(db, g, now, tz(m, g))
    if cur is None or not cur[0]["tracking_off_eliminates"] or m["playing_since"] > now:
        return False
    _eliminate(db, g, m, rules.Verdict(now, "tracking_off"), None)
    return True


def _eliminate(db: Database, g, m, v: rules.Verdict, app_name: str | None) -> None:
    reason = rules.verdict_text(v, app_name)
    if db.eliminate(m["id"], v.at_ms, reason):
        # Время вылета — по местному времени самого участника.
        db.add_event(g["id"], m["id"], "eliminated", v.at_ms,
                     f"💀 {m['name']} вылетел в {hhmm(v.at_ms, tz(m, g))}: {reason}")


# ---------------- срок испытания ----------------

def _round_nights(db: Database, g, offset: int) -> tuple[int, list[rules.Night]]:
    count = current_settings(db, g["id"])["round_nights"]
    return count, rules.round_nights(g["round_started_at"], db.settings_versions(g["id"]), offset, count)


def member_round_end(db: Database, g, m) -> int | None:
    """Подъём после последней ночи испытания этого участника (по его местному времени)."""
    count, nights = _round_nights(db, g, tz(m, g))
    return nights[-1].wake_ms if len(nights) == count else None


def round_end_ms(db: Database, g) -> int | None:
    """Испытание кончается, когда последнюю ночь закончил самый «западный» участник."""
    members = db.group_members(g["id"])
    ends = [member_round_end(db, g, m) for m in members] or [member_round_end(db, g, None)]
    return None if any(e is None for e in ends) else max(ends)


def round_info(db: Database, g, now: int | None = None, viewer=None) -> dict:
    """Срок испытания: сколько ночей, сколько прошло (по времени смотрящего), когда конец."""
    now = now or now_ms()
    count, nights = _round_nights(db, g, tz(viewer, g))
    winners = [m["name"] for m in db.group_members(g["id"]) if m["won"]]
    return {
        "no": g["round_no"],
        "started_at": g["round_started_at"],
        "nights": count,
        "nights_done": sum(1 for n in nights if n.wake_ms <= now),
        "ends_at": round_end_ms(db, g),
        "finished_at": g["finished_at"],
        "finish_kind": g["finish_kind"],
        "winners": winners,
    }


def finish(db: Database, group_id: int, kind: str, now: int | None = None) -> bool:
    """Завершает испытание. Побеждают все, кто не вылетел."""
    now = now or now_ms()
    for m in db.group_members(group_id):
        evaluate_member(db, m["id"], now)  # сначала досчитываем всё, что успели прислать
    g = db.group(group_id)
    alive = [m for m in db.group_members(group_id) if m["eliminated_at"] is None]
    if not db.finish_round(group_id, now, kind, [m["id"] for m in alive]):
        return False
    head = {"manual": "🏁 Организатор завершил испытание.", "time": "🏁 Испытание окончено!",
            "all_out": "🏁 Испытание окончено: вылетели все."}[kind]
    if alive:
        names = ", ".join(m["name"] for m in sorted(alive, key=lambda r: r["name"].casefold()))
        text = f"{head} 🏆 {'Победитель' if len(alive) == 1 else 'Победители'}: {names}."
    else:
        text = f"{head} Победителей в раунде {g['round_no']} нет."
    db.add_event(group_id, None, "finished", now, text)
    return True


def check_round(db: Database, group_id: int, now: int | None = None) -> None:
    """Конец испытания: вылетели все — сразу; вышел срок — после последнего подъёма,
    когда оставшиеся прислали данные за свою последнюю ночь (или прошло FINISH_GRACE_MS)."""
    now = now or now_ms()
    g = db.group(group_id)
    if g["finished_at"] is not None:
        return
    if not db.group_members(group_id):
        return
    check_silent(db, g, now)
    alive = [m for m in db.group_members(group_id) if m["eliminated_at"] is None]
    if not alive:
        finish(db, group_id, "all_out", now)
        return
    end = round_end_ms(db, g)
    if end is None or now < end:
        return
    if now < end + FINISH_GRACE_MS and any((m["last_upload_at"] or 0) < (member_round_end(db, g, m) or end)
                                           for m in alive):
        return  # ждём данных за последнюю ночь
    finish(db, group_id, "time", now)


# ---------------- ночь участника ----------------

def current_night(db: Database, g, now: int, offset: int) -> tuple[dict, rules.Night] | None:
    """Идущая сейчас ночь (по местному времени offset) с учётом досрочной отмены отбоя."""
    cur = rules.current_night(now, db.settings_versions(g["id"]), offset)
    if cur is None:
        return None
    cancel_at = db.night_cancels(g["id"]).get(cur[1].wake_date)
    return None if cancel_at is not None and cancel_at <= now else cur


def _upcoming(db: Database, g, now: int, offset: int) -> list[tuple[dict, rules.Night]]:
    """Включённые ночи, которые ещё не закончились (идущая — первой)."""
    versions = db.settings_versions(g["id"])
    return [(s, n) for s, n in rules.nights_between(now, now + 8 * 24 * 3_600_000, versions, offset)
            if n.wake_ms > now and rules.night_active(n, s, offset)]


def describe_night(db: Database, group_id: int, now: int | None = None, member=None) -> dict:
    """Что сейчас с отбоем у участника: идёт ли ночь, когда следующий отбой, отменён ли сегодняшний."""
    now = now or now_ms()
    g = db.group(group_id)
    offset = tz(member, g)
    cancels = db.night_cancels(group_id)
    upcoming = _upcoming(db, g, now, offset)
    # Ближайшая ночь, которую ещё не отменили.
    live = next(((s, n) for s, n in upcoming if not (n.wake_date in cancels and cancels[n.wake_date] <= now)), None)
    cur = current_night(db, g, now, offset)
    target = upcoming[0] if upcoming else None  # с ней работает кнопка «Отменить отбой»
    s = (live or target or (db.settings_versions(group_id)[-1][1], None))[0]
    return {
        "bedtime": s["bedtime"],
        "wake": s["wake"],
        "tz_offset_min": offset,
        "in_night": cur is not None,
        "bed_ms": live[1].bed_ms if live else None,
        "wake_ms": live[1].wake_ms if live else None,
        "cancel_target": {
            "wake_date": target[1].wake_date.isoformat(),
            "bed_ms": target[1].bed_ms,
            "wake_ms": target[1].wake_ms,
            "cancelled": target[1].wake_date in cancels,
        } if target else None,
    }


def night_phase(db: Database, g, m, now: int) -> str:
    """curfew — у участника сейчас отбой; pre — до отбоя меньше 40 минут; day — всё остальное."""
    offset = tz(m, g)
    if current_night(db, g, now, offset) is not None:
        return "curfew"
    cancels = db.night_cancels(g["id"])
    for _, n in _upcoming(db, g, now, offset):
        if n.wake_date in cancels and cancels[n.wake_date] <= now:
            continue
        return "pre" if n.bed_ms - rules.REVEAL_BEFORE_MS <= now < n.bed_ms else "day"
    return "day"


def cancel_tonight(db: Database, group_id: int, now: int | None = None, member=None) -> str | None:
    """Отменяет ближайший (или идущий) отбой — по местному времени организатора. Возвращает текст события."""
    now = now or now_ms()
    g = db.group(group_id)
    upcoming = _upcoming(db, g, now, tz(member, g))
    if not upcoming:
        return None
    _, n = upcoming[0]
    db.cancel_night(group_id, n.wake_date.isoformat(), now)
    if n.bed_ms <= now:
        return "🌅 Организатор досрочно отменил отбой — можно пользоваться телефоном."
    return f"🌅 Организатор отменил отбой в ночь на {n.wake_date.strftime('%d.%m')}."


def restore_tonight(db: Database, group_id: int, now: int | None = None, member=None) -> bool:
    """Вернуть отменённый отбой можно, только пока он не начался."""
    now = now or now_ms()
    g = db.group(group_id)
    upcoming = _upcoming(db, g, now, tz(member, g))
    if not upcoming or upcoming[0][1].bed_ms <= now:
        return False
    db.uncancel_night(group_id, upcoming[0][1].wake_date.isoformat())
    return True


def check_silent(db: Database, g, now: int) -> None:
    """Ночь прошла, у участника уже полдень, а от него за эту ночь нет ни одной отправки —
    приложение удалено или заморожено, журнал ночи посмотреть нельзя. Если в группе включено
    «отключил слежку — вылет», это вылет."""
    versions = db.settings_versions(g["id"])
    cancels = db.night_cancels(g["id"])
    for m in db.group_members(g["id"]):
        if m["eliminated_at"] is not None:
            continue
        offset = tz(m, g)
        end = member_round_end(db, g, m)
        for s, n in rules.nights_between(now - 2 * 24 * 3_600_000, now, versions, offset):
            if not (rules.night_active(n, s, offset) and n.wake_ms <= now and n.bed_ms >= g["round_started_at"]
                    and (end is None or n.wake_ms <= end) and cancels.get(n.wake_date, n.wake_ms) > n.bed_ms):
                continue
            if not s["tracking_off_eliminates"] or m["playing_since"] > n.bed_ms:
                continue
            deadline = _local_ms(n.wake_date, SILENT_DEADLINE_HOUR, offset)
            if now >= deadline and (m["last_upload_at"] or 0) < n.wake_ms:
                _eliminate(db, g, m, rules.Verdict(deadline, "silent"), None)
                break


def _local_ms(d, hour: int, offset_min: int) -> int:
    dt = datetime.combine(d, time(hour, 0), tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000) - offset_min * 60_000


# ---------------- что видно друзьям ----------------

def reveal_windows(db: Database, g, m, start: int, end: int, now: int | None = None) -> list[tuple[int, int]]:
    """Когда друзьям видно приложения участника: за 40 минут до отбоя — сразу,
    а сама ночь — только после её конца (подъёма или отмены)."""
    return rules.reveal_windows(start, end, db.settings_versions(g["id"]), tz(m, g),
                                db.night_cancels(g["id"]), now=now or now_ms())


def current_settings(db: Database, group_id: int) -> dict:
    """Действующие настройки (новые правила действуют сразу)."""
    now = now_ms()
    versions = db.settings_versions(group_id)
    active = [v for v in versions if v[0] <= now]
    return (active or versions)[-1][1]
