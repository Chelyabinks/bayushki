"""Правила ночи — чистые функции."""
from datetime import date

import pytest

from app import rules

S = rules.validate_settings({}, {})  # 00:30–06:00, сессия 2 мин, сумма 5 мин, запрет 10 с
MSK = 180
SEC, MIN = 1000, 60_000
NIGHT = rules.night_for(date(2026, 11, 26), S, MSK)
T0 = NIGHT.bed_ms  # 00:30 по Москве


def ar(s=S, cats=None):
    return rules.AppRules.from_settings(s, cats or {})


def ev(used=(), sessions=(), s=S, cats=None, **kw):
    return rules.evaluate_night(NIGHT, s, ar(s, cats), list(used), list(sessions), **kw)


def test_night_window_msk():
    # 00:30 МСК 26.11 = 21:30 UTC 25.11
    assert rules.local_date(NIGHT.bed_ms, MSK) == date(2026, 11, 26)
    assert NIGHT.wake_ms - NIGHT.bed_ms == (5 * 60 + 30) * MIN
    late = rules.validate_settings(S, {"bedtime": "23:00", "wake": "07:00"})
    n = rules.night_for(date(2026, 11, 26), late, MSK)
    assert rules.local_date(n.bed_ms, MSK) == date(2026, 11, 25) and n.wake_ms - n.bed_ms == 8 * 60 * MIN


def test_quiet_night_and_short_checks_ok():
    used = [(T0 + 10 * MIN, T0 + 10 * MIN + 90 * SEC), (T0 + 60 * MIN, T0 + 61 * MIN)]  # 1,5 мин + 1 мин
    assert ev(used, [("com.google.android.calculator", T0 + 10 * MIN, T0 + 11 * MIN)]) is None


def test_usage_before_bedtime_does_not_count():
    assert ev([(T0 - 60 * MIN, T0 + 30 * SEC)]) is None


def test_long_session_eliminates_at_two_minutes():
    v = ev([(T0 + 5 * MIN, T0 + 9 * MIN)])
    assert v.kind == "session" and v.at_ms == T0 + 7 * MIN + 1


def test_short_lock_does_not_split_session():
    used = [(T0, T0 + 70 * SEC), (T0 + 71 * SEC, T0 + 140 * SEC)]  # «заблокировал» на секунду
    assert ev(used).kind == "session"


def test_total_of_short_checks():
    used = [(T0 + i * 10 * MIN, T0 + i * 10 * MIN + 90 * SEC) for i in range(5)]  # 5 × 1,5 мин = 7,5 мин
    v = ev(used)
    assert v.kind == "total"
    assert v.at_ms == T0 + 30 * MIN + 30 * SEC + 1  # 4-й заход: 4,5 мин + ещё 30 с


def test_forbidden_app_ten_seconds():
    sessions = [("org.telegram.messenger", T0 + MIN, T0 + MIN + 6 * SEC),
                ("com.zhiliaoapp.musically", T0 + 5 * MIN, T0 + 5 * MIN + 20 * SEC)]
    used = [(T0 + MIN, T0 + MIN + 6 * SEC), (T0 + 5 * MIN, T0 + 5 * MIN + 20 * SEC)]
    v = ev(used, sessions)
    assert v.kind == "forbidden" and v.pkg == "com.zhiliaoapp.musically" and v.at_ms == T0 + 5 * MIN + 4 * SEC


def test_game_by_android_category():
    sessions = [("com.some.game", T0, T0 + 15 * SEC)]
    assert ev(sessions=sessions, cats={"com.some.game": rules.app_categories("com.some.game", 0)}).kind == "forbidden"
    assert ev(sessions=sessions) is None  # без категории — обычное приложение


def test_calls_own_app_and_allowed_do_not_count():
    used = [(T0, T0 + 30 * MIN)]
    sessions = [("com.google.android.dialer", T0, T0 + 20 * MIN), ("ru.bayushki.app", T0 + 20 * MIN, T0 + 25 * MIN),
                ("com.yandex.navi", T0 + 25 * MIN, T0 + 30 * MIN)]
    s = rules.validate_settings(S, {"allowed_apps": ["com.yandex.navi"]})
    assert ev(used, sessions, s=s) is None
    assert ev(used, sessions).kind == "session"  # навигатор без исключения — нарушение


def test_hidden_clock_allowance():
    clock = "com.google.android.deskclock"
    # 5 минут в «Часах» — никогда не вылет
    assert ev([(T0, T0 + 5 * MIN)], [(clock, T0, T0 + 5 * MIN)]) is None
    # 6 минут «Часов» прощаются, дальше 2 минуты сверху — вылет
    v = ev([(T0, T0 + 9 * MIN)], [(clock, T0, T0 + 9 * MIN)])
    assert v.kind == "session" and v.at_ms == T0 + 8 * MIN + 1
    # Часы нельзя запретить, и в правилах про них ни слова
    assert not ar(rules.validate_settings(S, {"forbidden_apps": [clock]})).forbidden(clock)
    assert not any("час" in line.lower() and "Часы" in line for line in rules.rules_text(S))


def test_locked_screen_ignored():
    # Экран горел 30 минут, но телефон был заблокирован: «used» пустой.
    assert ev([], []) is None


def test_joined_mid_night():
    assert ev([(T0, T0 + 10 * MIN)], window_start=T0 + 9 * MIN) is None


def test_validation():
    with pytest.raises(rules.SettingsError):
        rules.validate_settings(S, {"bedtime": "25:00"})
    with pytest.raises(rules.SettingsError):
        rules.validate_settings(S, {"bedtime": "06:00", "wake": "06:00"})
    with pytest.raises(rules.SettingsError):
        rules.validate_settings(S, {"forbidden_categories": ["porn"]})
    s = rules.validate_settings(S, {"forbidden_apps": ["a", "b"], "allowed_apps": ["b"]})
    assert s["forbidden_apps"] == ["a"]


def test_settings_changes_apply_immediately():
    newer = rules.validate_settings(S, {"bedtime": "23:00"})
    # Поменяли в 23:30 (до старого отбоя) — новые правила уже на эту ночь, но с момента изменения
    changed_at = NIGHT.bed_ms - 60 * MIN
    s, n = rules.pick_settings([(0, S), (changed_at, newer)], date(2026, 11, 26), MSK)
    assert s["bedtime"] == "23:00" and n.rules_from_ms == changed_at
    # Использование до изменения правил не считается, после — считается
    assert rules.evaluate_night(n, s, ar(s), [(n.bed_ms, changed_at)], []) is None
    v = rules.evaluate_night(n, s, ar(s), [(changed_at + MIN, changed_at + 5 * MIN)], [])
    assert v.kind == "session"
    # Поменяли посреди ночи лимит — со следующей минуты действует новый
    stricter = rules.validate_settings(S, {"max_session_s": 30})
    mid = T0 + 60 * MIN
    s2, n2 = rules.pick_settings([(0, S), (mid, stricter)], date(2026, 11, 26), MSK)
    assert s2["max_session_s"] == 30 and n2.rules_from_ms == mid and n2.bed_ms == T0
    # Следующие ночи — целиком по новым
    s3, n3 = rules.pick_settings([(0, S), (mid, stricter)], date(2026, 11, 27), MSK)
    assert s3["max_session_s"] == 30 and n3.rules_from_ms is None


def test_own_app_allowance():
    own = "ru.bayushki.app"
    # 4 минуты в «Баюшках» — не считается (лимит 5 минут), но длинная сессия уже да
    assert ev([(T0, T0 + 4 * MIN)], [(own, T0, T0 + 4 * MIN)]) is None
    v = ev([(T0, T0 + 8 * MIN)], [(own, T0, T0 + 8 * MIN)])
    assert v.kind == "session" and v.at_ms == T0 + 7 * MIN + 1  # 5 мин бесплатно + 2 мин лимита
    # Организатор выключил поблажку — считается с первой секунды
    s = rules.validate_settings(S, {"own_app_s": 0})
    assert ev([(T0, T0 + 3 * MIN)], [(own, T0, T0 + 3 * MIN)], s=s).kind == "session"
    # «Баюшки» нельзя ни запретить, ни сделать исключением в обход лимита
    s = rules.validate_settings(S, {"allowed_apps": [own], "forbidden_apps": []})
    assert ev([(T0, T0 + 8 * MIN)], [(own, T0, T0 + 8 * MIN)], s=s).kind == "session"
    assert not ar(rules.validate_settings(S, {"forbidden_apps": [own]})).forbidden(own)
    assert any("Баюшк" in line for line in rules.rules_text(S))


def test_cancelled_night_window_end():
    used = [(T0 + 60 * MIN, T0 + 70 * MIN)]
    assert ev(used).kind == "session"
    assert ev(used, window_end=T0 + 30 * MIN) is None  # отбой отменили в 01:00


def test_reveal_windows():
    span = (T0 - 3 * 3_600_000, T0 + 3_600_000)
    # Ночь идёт: видно только «за 40 минут до отбоя»
    w = rules.reveal_windows(*span, [(0, S)], MSK, now=T0 + 30 * MIN)
    assert (T0 - 40 * MIN, T0) in w and not any(a < T0 + MIN < b for a, b in w)
    # После подъёма — и вся ночь
    w = rules.reveal_windows(*span, [(0, S)], MSK, now=NIGHT.wake_ms)
    assert (T0 - 40 * MIN, NIGHT.wake_ms) in w
    # Отбой отменили в 00:31 — ночь видна с этого момента
    w = rules.reveal_windows(*span, [(0, S)], MSK, {NIGHT.wake_date: T0 + MIN}, now=T0 + 2 * MIN)
    assert (T0 - 40 * MIN, T0 + MIN) in w


def test_days_of_week():
    weekdays = rules.validate_settings(S, {"days": [0, 1, 2, 3, 6]})  # без пт и сб
    fri = rules.night_for(date(2026, 11, 28), weekdays, MSK)  # ночь с пт 27 на сб 28
    sun = rules.night_for(date(2026, 11, 30), weekdays, MSK)  # с вс на пн
    assert not rules.night_active(fri, weekdays, MSK)
    assert rules.night_active(sun, weekdays, MSK)


def test_round_nights_count_from_start():
    versions = [(0, S)]
    # Старт в 23:00 — ближайшая ночь уже первая
    nights = rules.round_nights(T0 - 90 * MIN, versions, MSK, 3)
    assert [n.bed_ms for n in nights] == [T0, T0 + 24 * 3_600_000, T0 + 48 * 3_600_000]
    # Старт посреди ночи — она не засчитывается
    assert rules.round_nights(T0 + MIN, versions, MSK, 1)[0].bed_ms == T0 + 24 * 3_600_000
    # Выключенные дни пропускаются
    only_sun = rules.validate_settings(S, {"days": [6]})
    two = rules.round_nights(T0, [(0, only_sun)], MSK, 2)
    assert two[1].bed_ms - two[0].bed_ms == 7 * 24 * 3_600_000
    assert rules.nights_word(1) == "ночь" and rules.nights_word(3) == "ночи" and rules.nights_word(14) == "ночей"
