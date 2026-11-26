"""Правила ночи: что считается нарушением и когда участник вылетает.

Всё здесь — чистые функции без базы, их легко тестировать.

Нарушение (любое из трёх → вылет из игры):
  1. непрерывная сессия с разблокированным телефоном дольше max_session_s (по умолчанию 2 мин);
  2. все заходы за ночь в сумме дольше night_total_s (по умолчанию 5 мин);
  3. запрещённые приложения (мессенджеры, соцсети, видео, игры…) в сумме forbidden_s (по умолчанию 10 с).

Не считается: заблокированный экран, звонки, исключения организатора,
первые own_app_s секунд за ночь в самих «Баюшках» (по умолчанию 5 минут).
Скрытое правило (нигде не показывается участникам): в «Часах» можно провести до 6 минут за ночь.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone

# ---------------- приложения ----------------

OWN_PACKAGES = {"ru.bayushki.app"}

CALL_PACKAGES = {
    "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.server.telecom",
    "com.samsung.android.dialer", "com.samsung.android.incallui", "com.android.contacts",
    "com.huawei.contacts", "com.hihonor.contacts", "com.oneplus.dialer", "com.coloros.phonemanager",
    "com.miui.contacts", "com.android.phone", "com.google.android.contacts",
}

# «Часы»: до CLOCK_ALLOWANCE_MS за ночь не считаются. Участникам об этом не говорим.
CLOCK_PACKAGES = {
    "com.google.android.deskclock", "com.android.deskclock", "com.sec.android.app.clockpackage",
    "com.oneplus.deskclock", "com.coloros.alarmclock", "com.huawei.deskclock", "com.hihonor.deskclock",
    "com.android.BBKClock", "com.oplus.alarmclock", "com.realme.alarmclock", "com.motorola.timeweatherwidget",
}
CLOCK_ALLOWANCE_MS = 6 * 60_000

# Категории Android (ApplicationInfo.category): 0 игра, 2 видео, 4 соцсеть.
ANDROID_GAME, ANDROID_VIDEO, ANDROID_SOCIAL = 0, 2, 4

CATEGORIES: dict[str, str] = {
    "messengers": "Мессенджеры",
    "social": "Соцсети",
    "video": "Видео и стримы",
    "games": "Игры",
    "browsers": "Браузеры",
}

KNOWN: dict[str, set[str]] = {
    "messengers": {
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram",
        "com.whatsapp", "com.whatsapp.w4b", "com.viber.voip", "org.thoughtcrime.securesms", "com.discord",
        "com.facebook.orca", "jp.naver.line.android", "com.snapchat.android", "ru.oneme.app", "com.tencent.mm",
        "com.icq.mobile.client", "im.vector.app", "com.kakao.talk", "ru.ok.messages",
        "com.vk.im", "com.skype.raider", "us.zoom.videomeetings",
    },
    "social": {
        "com.vkontakte.android", "com.instagram.android", "com.facebook.katana", "com.facebook.lite",
        "com.twitter.android", "com.reddit.frontpage", "com.instagram.barcelona", "com.pinterest",
        "ru.ok.android", "com.bereal.ft", "com.tumblr", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "ru.yandex.zen", "ru.dzen.app", "com.linkedin.android", "org.joinmastodon.android",
        "xyz.blueskyweb.app", "com.threads.android", "ru.pikabu.android",
    },
    "video": {
        "com.google.android.youtube", "app.revanced.android.youtube", "app.rvx.android.youtube",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "tv.twitch.android.app",
        "com.netflix.mediaclient", "ru.kinopoisk", "ru.ivi.client", "ru.rutube.app", "com.vk.vkvideo",
        "video.like", "ru.more.play", "ru.start.androidmobile", "com.amediateka.android",
        "ru.kion.android", "com.wink.androidapp", "com.kick.mobile", "org.schabi.newpipe",
        "com.google.android.apps.youtube.kids", "ru.mts.mtstv", "com.anilibria.android",
    },
    "games": set(),
    "browsers": {
        "com.android.chrome", "org.mozilla.firefox", "com.yandex.browser", "com.opera.browser",
        "com.opera.mini.native", "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.brave.browser",
        "com.duckduckgo.mobile.android", "ru.yandex.searchplugin", "com.mi.globalbrowser",
        "com.android.browser", "com.vivaldi.browser", "com.kiwibrowser.browser",
    },
}


def app_categories(pkg: str, android_cat: int | None) -> set[str]:
    out = {c for c, pkgs in KNOWN.items() if pkg in pkgs}
    if android_cat == ANDROID_GAME:
        out.add("games")
    elif android_cat == ANDROID_VIDEO:
        out.add("video")
    elif android_cat == ANDROID_SOCIAL:
        out.add("social")
    return out


# Системные экраны, которые нет смысла показывать организатору в списке приложений
# (окно «Поделиться», выбор приложения и т. п.). Время в них считается как обычно.
SYSTEM_UI_PACKAGES = {
    "com.android.intentresolver", "android", "com.android.systemui", "com.android.permissioncontroller",
    "com.google.android.permissioncontroller", "com.android.settings.intelligence",
}


def always_allowed(pkg: str) -> bool:
    """Звонки: можно всегда, организатор это не меняет."""
    return pkg in CALL_PACKAGES


def hidden_app(pkg: str) -> bool:
    """Приложения со скрытыми правилами — не показываем организатору в списке."""
    return pkg in CLOCK_PACKAGES or pkg in OWN_PACKAGES or pkg in SYSTEM_UI_PACKAGES


# ---------------- настройки группы ----------------

DEFAULT_SETTINGS: dict = {
    "bedtime": "00:30",
    "wake": "06:00",
    "max_session_s": 120,
    "night_total_s": 300,
    "forbidden_s": 10,
    "forbidden_categories": ["messengers", "social", "video", "games"],
    "forbidden_apps": [],
    "allowed_apps": [],
    "days": [0, 1, 2, 3, 4, 5, 6],  # дни недели вечера, с которого начинается ночь (0 = пн)
    "tracking_off_eliminates": True,
    "round_nights": 7,  # сколько ночей длится испытание (считая от старта раунда)
    "own_app_s": 300,  # сколько можно провести в самих «Баюшках» за ночь
}

_TIME_RE = re.compile(r"^([01]\d|2[0-3]):([0-5]\d)$")


class SettingsError(ValueError):
    pass


def minutes(hhmm: str) -> int:
    m = _TIME_RE.match(hhmm)
    if not m:
        raise SettingsError(f"Время должно быть в формате ЧЧ:ММ, а не «{hhmm}»")
    return int(m.group(1)) * 60 + int(m.group(2))


def validate_settings(current: dict, patch: dict) -> dict:
    """Применяет изменения к текущим настройкам и проверяет их. Возвращает новый словарь."""
    s = {**DEFAULT_SETTINGS, **current}
    for key, value in patch.items():
        if key not in DEFAULT_SETTINGS:
            raise SettingsError(f"Неизвестная настройка: {key}")
        s[key] = value

    bed, wake = minutes(s["bedtime"]), minutes(s["wake"])
    if bed == wake:
        raise SettingsError("Отбой и подъём не могут совпадать")
    if (wake - bed) % 1440 > 16 * 60:
        raise SettingsError("Ночь не может быть длиннее 16 часов")

    def int_in(key: str, lo: int, hi: int, what: str) -> None:
        v = s[key]
        if not isinstance(v, int) or isinstance(v, bool) or not lo <= v <= hi:
            raise SettingsError(f"{what}: от {lo} до {hi}")

    int_in("max_session_s", 30, 3600, "Длина одной сессии, секунд")
    int_in("night_total_s", 60, 7200, "Сумма за ночь, секунд")
    int_in("forbidden_s", 1, 600, "Запрещённые приложения, секунд")
    int_in("round_nights", 1, 365, "Длина испытания, ночей")
    int_in("own_app_s", 0, 3600, "Время в «Баюшках» за ночь, секунд")

    cats = s["forbidden_categories"]
    if not isinstance(cats, list) or any(c not in CATEGORIES for c in cats):
        raise SettingsError("Неизвестная категория приложений")
    s["forbidden_categories"] = sorted(set(cats), key=list(CATEGORIES).index)

    for key in ("forbidden_apps", "allowed_apps"):
        v = s[key]
        if not isinstance(v, list) or len(v) > 300 or any(not isinstance(p, str) or len(p) > 200 for p in v):
            raise SettingsError("Список приложений слишком большой или битый")
        s[key] = sorted(set(v))
    # Одно приложение не может быть и в запрете, и в исключениях: исключение важнее.
    s["forbidden_apps"] = [p for p in s["forbidden_apps"] if p not in s["allowed_apps"]]

    days = s["days"]
    if not isinstance(days, list) or any(d not in range(7) for d in days):
        raise SettingsError("Дни недели: числа от 0 (пн) до 6 (вс)")
    s["days"] = sorted(set(days))
    s["tracking_off_eliminates"] = bool(s["tracking_off_eliminates"])
    return s


@dataclass
class AppRules:
    """Готовые к проверке правила по приложениям."""
    forbidden_categories: set[str]
    forbidden_apps: set[str]
    allowed_apps: set[str]
    categories_of: dict[str, set[str]]  # pkg → категории (из каталога приложений)

    @classmethod
    def from_settings(cls, s: dict, categories_of: dict[str, set[str]]) -> AppRules:
        return cls(set(s["forbidden_categories"]), set(s["forbidden_apps"]), set(s["allowed_apps"]), categories_of)

    def exempt(self, pkg: str) -> bool:
        return always_allowed(pkg) or (pkg in self.allowed_apps and pkg not in OWN_PACKAGES)

    def forbidden(self, pkg: str) -> bool:
        if self.exempt(pkg) or pkg in CLOCK_PACKAGES or pkg in OWN_PACKAGES:
            return False
        if pkg in self.forbidden_apps:
            return True
        cats = self.categories_of.get(pkg) or app_categories(pkg, None)
        return bool(cats & self.forbidden_categories)


# ---------------- ночи ----------------

@dataclass(frozen=True)
class Night:
    wake_date: date  # «ночь на это число»
    bed_ms: int
    wake_ms: int
    # Правила поменяли посреди ночи — новые действуют с этого момента (раньше — по старым).
    rules_from_ms: int | None = field(default=None, compare=False)


def _epoch_ms(d: date, minute_of_day: int, offset_min: int) -> int:
    dt = datetime.combine(d, time(minute_of_day // 60, minute_of_day % 60), tzinfo=timezone.utc)
    return int(dt.timestamp() * 1000) - offset_min * 60_000


def night_for(wake_date: date, s: dict, offset_min: int) -> Night:
    bed, wake = minutes(s["bedtime"]), minutes(s["wake"])
    bed_date = wake_date if bed < wake else wake_date - timedelta(days=1)
    return Night(wake_date, _epoch_ms(bed_date, bed, offset_min), _epoch_ms(wake_date, wake, offset_min))


def local_date(ms: int, offset_min: int) -> date:
    return datetime.fromtimestamp((ms + offset_min * 60_000) / 1000, tz=timezone.utc).date()


def night_active(n: Night, s: dict, offset_min: int) -> bool:
    """Включена ли эта ночь (по дню недели вечера, с которого она начинается)."""
    evening = local_date(n.bed_ms - 12 * 3_600_000, offset_min)  # «вечер» даже при отбое после полуночи
    return evening.weekday() in s["days"]


def pick_settings(versions: list[tuple[int, dict]], wake_date: date, offset_min: int) -> tuple[dict, Night]:
    """versions: [(effective_from_ms, settings)] по возрастанию. Правила действуют сразу:
    ночь судится по самым новым настройкам, принятым до её подъёма. Если их приняли уже
    после отбоя, они действуют с момента изменения (rules_from_ms)."""
    for eff, s in reversed(versions):
        n = night_for(wake_date, s, offset_min)
        if eff < n.wake_ms:
            if eff > n.bed_ms:
                n = Night(n.wake_date, n.bed_ms, n.wake_ms, eff)
            return s, n
    s = versions[0][1]
    return s, night_for(wake_date, s, offset_min)


def nights_between(start_ms: int, end_ms: int, versions: list[tuple[int, dict]], offset_min: int) -> list[tuple[dict, Night]]:
    """Все ночи, пересекающие [start_ms, end_ms]."""
    out = []
    d = local_date(start_ms, offset_min) - timedelta(days=1)
    last = local_date(end_ms, offset_min) + timedelta(days=1)
    while d <= last:
        s, n = pick_settings(versions, d, offset_min)
        if n.wake_ms > start_ms and n.bed_ms < end_ms:
            out.append((s, n))
        d += timedelta(days=1)
    return out


def current_night(now: int, versions: list[tuple[int, dict]], offset_min: int) -> tuple[dict, Night] | None:
    for s, n in nights_between(now, now + 1, versions, offset_min):
        if n.bed_ms <= now < n.wake_ms and night_active(n, s, offset_min):
            return s, n
    return None


def round_nights(start_ms: int, versions: list[tuple[int, dict]], offset_min: int, count: int) -> list[Night]:
    """Первые count включённых ночей испытания: с отбоем не раньше старта раунда."""
    out: list[Night] = []
    d = local_date(start_ms, offset_min) - timedelta(days=1)
    for _ in range(count * 7 + 14):
        s, n = pick_settings(versions, d, offset_min)
        if n.bed_ms >= start_ms and night_active(n, s, offset_min):
            out.append(n)
            if len(out) == count:
                break
        d += timedelta(days=1)
    return out


REVEAL_BEFORE_MS = 40 * 60_000  # за сколько до отбоя друзьям становятся видны приложения


def reveal_windows(start_ms: int, end_ms: int, versions: list[tuple[int, dict]], offset_min: int,
                   cancels: dict[date, int] | None = None, now: int | None = None) -> list[tuple[int, int]]:
    """Какие куски времени участника друзья могут видеть с приложениями:
      - «за 40 минут до отбоя» — сразу, вживую;
      - сама ночь — только когда она закончилась (подъём или отмена отбоя). Пока идёт отбой,
        никто не видит, что происходит, — чтобы не было повода заглядывать в телефон.
    Днём приложения не видны вовсе."""
    out = []
    for s, n in nights_between(start_ms - REVEAL_BEFORE_MS, end_ms + REVEAL_BEFORE_MS, versions, offset_min):
        if not night_active(n, s, offset_min):
            continue
        end = n.wake_ms
        if cancels and n.wake_date in cancels:
            end = min(end, cancels[n.wake_date])
        pre_end = min(n.bed_ms, end)
        if pre_end > n.bed_ms - REVEAL_BEFORE_MS:
            out.append((n.bed_ms - REVEAL_BEFORE_MS, pre_end))
        if end > n.bed_ms and (now is None or now >= end):
            out.append((n.bed_ms, end))
    return _union(out)


def nights_word(n: int) -> str:
    if n % 10 == 1 and n % 100 != 11:
        return "ночь"
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return "ночи"
    return "ночей"


def next_night(now: int, versions: list[tuple[int, dict]], offset_min: int) -> tuple[dict, Night] | None:
    for s, n in nights_between(now, now + 8 * 24 * 3_600_000, versions, offset_min):
        if n.wake_ms > now and night_active(n, s, offset_min):
            return s, n
    return None


# ---------------- проверка ночи ----------------

Interval = tuple[int, int]
SESSION_JOIN_GAP_MS = 3_000  # блокировка на 1–2 секунды не разрывает сессию


@dataclass
class Verdict:
    at_ms: int
    kind: str  # "session" | "total" | "forbidden" | "tracking_off"
    pkg: str | None = None
    duration_ms: int | None = None


def _union(intervals: list[Interval], gap: int = 0) -> list[Interval]:
    out: list[list[int]] = []
    for s, e in sorted(intervals):
        if e <= s:
            continue
        if out and s <= out[-1][1] + gap:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return [(s, e) for s, e in out]


def _subtract(base: Interval, holes: list[Interval]) -> list[Interval]:
    s, e = base
    out = []
    cur = s
    for hs, he in holes:
        if he <= cur or hs >= e:
            continue
        if hs > cur:
            out.append((cur, hs))
        cur = max(cur, he)
    if cur < e:
        out.append((cur, e))
    return out


def _crossing(pieces: list[Interval], already: int, limit: int) -> int | None:
    """Момент, когда накопленное время (already + куски) впервые превысит limit."""
    acc = already
    for s, e in pieces:
        if acc + (e - s) > limit:
            return s + (limit - acc) + 1
        acc += e - s
    return None


def evaluate_night(night: Night, s: dict, rules: AppRules, used: list[Interval],
                   sessions: list[tuple[str, int, int]], window_start: int | None = None,
                   window_end: int | None = None) -> Verdict | None:
    """Первое нарушение за ночь или None.

    window_start — участник вступил посреди ночи; window_end — отбой отменили досрочно.
    Данные берутся из журнала Android, который система ведёт сама: даже если «Баюшки»
    ночью поставили на паузу, утром журнал покажет всё, что было.
    """
    lo = max(night.bed_ms, window_start or night.bed_ms, night.rules_from_ms or night.bed_ms)
    hi = min(night.wake_ms, window_end or night.wake_ms)
    if lo >= hi:
        return None

    def clip(a: int, b: int) -> Interval | None:
        a, b = max(a, lo), min(b, hi)
        return (a, b) if b > a else None

    apps = sorted(((pkg, *c) for pkg, a, b in sessions if (c := clip(a, b))), key=lambda x: x[1])
    verdicts: list[Verdict] = []

    # 3. Запрещённые приложения: сумма за ночь.
    forb = [(pkg, a, b) for pkg, a, b in apps if rules.forbidden(pkg)]
    acc = 0
    for pkg, a, b in forb:
        if acc + (b - a) >= s["forbidden_s"] * 1000:
            verdicts.append(Verdict(a + s["forbidden_s"] * 1000 - acc, "forbidden", pkg, s["forbidden_s"] * 1000))
            break
        acc += b - a

    # Время, которое не считается: звонки, исключения, первые 6 минут «Часов»
    # и первые own_app_s секунд в самих «Баюшках».
    holes: list[Interval] = [(a, b) for pkg, a, b in apps if rules.exempt(pkg)]
    allowance = {"clock": CLOCK_ALLOWANCE_MS, "own": s.get("own_app_s", 300) * 1000}
    for pkg, a, b in apps:
        kind = "clock" if pkg in CLOCK_PACKAGES else "own" if pkg in OWN_PACKAGES else None
        if kind and allowance[kind] > 0:
            take = min(b - a, allowance[kind])
            holes.append((a, a + take))
            allowance[kind] -= take
    holes = _union(holes)

    used_clipped = [c for a, b in used if (c := clip(a, b))]

    # 1–2. Разблокированный телефон: длинная сессия и сумма за ночь.
    total = 0
    for sess in _union(used_clipped, SESSION_JOIN_GAP_MS):
        pieces = _subtract(sess, holes)
        found = False
        t = _crossing(pieces, 0, s["max_session_s"] * 1000)
        if t is not None:
            verdicts.append(Verdict(t, "session", _app_at(apps, t), sum(e - a for a, e in pieces)))
            found = True
        t = _crossing(pieces, total, s["night_total_s"] * 1000)
        if t is not None:
            verdicts.append(Verdict(t, "total", _app_at(apps, t), s["night_total_s"] * 1000))
            found = True
        total += sum(e - a for a, e in pieces)
        if found:
            break  # дальше смотреть незачем: нас интересует самое раннее нарушение

    return min(verdicts, key=lambda v: v.at_ms) if verdicts else None


def _app_at(apps: list[tuple[str, int, int]], t: int) -> str | None:
    for pkg, a, b in apps:
        if a <= t <= b:
            return pkg
    return None


def verdict_text(v: Verdict, app_name: str | None) -> str:
    name = app_name or v.pkg
    if v.kind == "forbidden":
        return f"запрещённое приложение: {name}"
    if v.kind == "session":
        return "сидел в телефоне дольше лимита" + (f" ({name})" if name else "")
    if v.kind == "total":
        return "короткие заходы за ночь превысили лимит" + (f" (последний — {name})" if name else "")
    if v.kind == "silent":
        return "не прислал данные за ночь (приложение выключено или на паузе)"
    return "отключил слежку ночью"


def rules_text(s: dict) -> list[str]:
    """Правила для участников. Про «Часы» здесь специально ничего нет."""
    def dur(sec: int) -> str:
        if sec < 60:
            return f"{sec} с"
        m, r = divmod(sec, 60)
        return f"{m} мин" + (f" {r} с" if r else "")

    names = ", ".join(CATEGORIES[c].lower() for c in s["forbidden_categories"]) or "нет"
    days = s["days"]
    day_names = ["пн", "вт", "ср", "чт", "пт", "сб", "вс"]
    lines = [
        f"Отбой в {s['bedtime']}, подъём в {s['wake']}.",
        f"Одна сессия после отбоя — не дольше {dur(s['max_session_s'])}.",
        f"Все заходы за ночь в сумме — не больше {dur(s['night_total_s'])}.",
        f"Запрещённые приложения ({names}) — вылет за {dur(s['forbidden_s'])}.",
        "Заблокированный экран и звонки не считаются.",
        f"Первые {dur(s.get('own_app_s', 300))} за ночь в самих «Баюшках» не считаются, дальше — как обычное время."
        if s.get("own_app_s", 300) else "Время в самих «Баюшках» после отбоя считается как обычное.",
        "Нарушил — вылетел из игры.",
        f"Испытание длится {s['round_nights']} {nights_word(s['round_nights'])}. "
        "Побеждают все, кто продержится до конца.",
    ]
    if s.get("tracking_off_eliminates"):
        lines.insert(6, "Отключил слежку или поставил «Баюшки» на паузу ночью — тоже вылет.")
    if len(days) < 7:
        lines.insert(1, "Правила действуют в ночи после: " + ", ".join(day_names[d] for d in days) + ".")
    return lines
