"""Игра целиком через API: создание группы, вылет, победитель, утренние события, настройки."""

import pytest
from fastapi.testclient import TestClient

from app import main, rules
from app.db import Database, now_ms

from .test_api import auth, upload

MIN, SEC = 60_000, 1000


@pytest.fixture()
def env(tmp_path):
    db = Database(tmp_path / "g.sqlite3")
    main.set_db(db)
    main._join_fails.clear()
    main._creations.clear()
    return db, TestClient(main.app)


def create(client, group="Баюшки", name="Орг"):
    r = client.post("/api/v1/groups", json={"group_name": group, "name": name, "tz_offset_min": 180})
    assert r.status_code == 200, r.text
    return r.json()


def join(client, code, name):
    r = client.post("/api/v1/join", json={"invite_code": code, "name": name})
    assert r.status_code == 200, r.text
    return r.json()


def last_night(db, gid):
    """Последняя закончившаяся ночь и «вчерашний» день для данных."""
    g = db.group(gid)
    now = now_ms()
    versions = db.settings_versions(gid)
    nights = [n for _, n in rules.nights_between(now - 3 * 24 * 3_600_000, now, versions, g["tz_offset_min"])
              if n.wake_ms < now]
    return nights[-1]


def backdate(db, *member_ids, ms):
    with db.conn() as c:
        for mid in member_ids:
            c.execute("UPDATE members SET playing_since=? WHERE id=?", (ms, mid))


def test_create_group_and_owner_actions(env):
    db, client = env
    org = create(client)
    assert org["is_owner"] and len(org["invite_code"]) == 8
    friend = join(client, org["invite_code"], "Друг")
    assert not friend["is_owner"]

    info = client.get("/api/v1/group", headers=auth(friend["token"])).json()
    assert info["settings"]["bedtime"] == "00:30" and info["settings"]["wake"] == "06:00"
    assert info["owner_name"] == "Орг"
    assert not any("Час" in line for line in info["rules"])

    # Не организатор менять не может
    r = client.put("/api/v1/group", json={"settings": {"bedtime": "23:00"}}, headers=auth(friend["token"]))
    assert r.status_code == 403
    # Организатор может; кривые настройки отклоняются
    r = client.put("/api/v1/group", json={"settings": {"bedtime": "99:00"}}, headers=auth(org["token"]))
    assert r.status_code == 422
    r = client.put("/api/v1/group", json={"group_name": "Совы", "settings": {"max_session_s": 180}},
                   headers=auth(org["token"]))
    assert r.status_code == 200
    j = r.json()
    assert j["name"] == "Совы"
    assert j["settings"]["max_session_s"] == 180  # новые правила действуют сразу

    old_code = org["invite_code"]
    new_code = client.post("/api/v1/group/new-invite", headers=auth(org["token"])).json()["invite_code"]
    assert new_code != old_code
    assert client.post("/api/v1/join", json={"invite_code": old_code, "name": "X"}).status_code == 404

    # Передал роль организатора — теперь организатор друг
    client.post(f"/api/v1/group/members/{friend['member_id']}/make-owner", headers=auth(org["token"]))
    assert client.get("/api/v1/group", headers=auth(friend["token"])).json()["is_owner"]
    # Кик
    assert client.post(f"/api/v1/group/members/{org['member_id']}/kick",
                       headers=auth(friend["token"])).status_code == 200
    assert client.get("/api/v1/me", headers=auth(org["token"])).status_code == 401


def backdate_round(db, gid, ms, nights=1):
    with db.conn() as c:
        c.execute("UPDATE groups SET round_started_at=? WHERE id=?", (ms, gid))
    s = rules.validate_settings(db.settings_versions(gid)[-1][1], {"round_nights": nights})
    db.add_settings(gid, 0, s)


def night_upload(client, token, pkg, name, cat, start, end):
    return upload(client, token, {
        "perm": True, "apps": {pkg: [name, cat]}, "used": [[start, end]], "sessions": [[pkg, start, end]],
    })


def test_elimination_and_manual_finish(env):
    db, client = env
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    b = join(client, org["invite_code"], "Боря")
    c = join(client, org["invite_code"], "Вика")
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], a["member_id"], b["member_id"], c["member_id"], ms=night.bed_ms - 3_600_000)

    # Первый контакт: телефон узнаёт номер последнего события
    r = upload(client, org["token"], {"screen_on": True, "perm": True, "sessions": []}).json()
    assert r["last_event_id"] == 0 and r["night"]["bedtime"] == "00:30"

    t = night.bed_ms + 20 * MIN
    # Аня ночью 15 секунд в TikTok → вылет
    night_upload(client, a["token"], "com.zhiliaoapp.musically", "TikTok", 2, t, t + 15 * SEC)
    # Боря листал калькулятор 3 минуты подряд → вылет
    night_upload(client, b["token"], "com.calc", "Калькулятор", -1, t + 30 * MIN, t + 33 * MIN)
    lst = client.get("/api/v1/members", headers=auth(org["token"])).json()
    by = {m["name"]: m for m in lst["members"]}
    assert by["Аня"]["game"]["state"] == "out" and "TikTok" in by["Аня"]["game"]["reason"]
    assert by["Аня"]["game"]["at"] == t + 10 * SEC
    assert by["Боря"]["game"]["state"] == "out"
    # Испытание (7 ночей) ещё идёт: оставшиеся просто в игре, даже если остался один
    assert by["Орг"]["game"]["state"] == "in" and by["Вика"]["game"]["state"] == "in"
    assert lst["round_info"]["finished_at"] is None and lst["round_info"]["nights"] == 7

    # Не организатор завершить не может; организатор — может
    assert client.post("/api/v1/group/finish", headers=auth(a["token"])).status_code == 403
    info = client.post("/api/v1/group/finish", headers=auth(org["token"])).json()
    assert sorted(info["round"]["winners"]) == ["Вика", "Орг"]
    assert client.post("/api/v1/group/finish", headers=auth(org["token"])).status_code == 409
    lst = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]
    assert [m["game"]["state"] for m in lst[:2]] == ["winner", "winner"]

    r = upload(client, org["token"], {"screen_on": True, "perm": True, "sessions": [], "ev": 0}).json()
    if not r["night"]["in_night"]:
        texts = [e["text"] for e in r["events"]]
        assert any("Аня вылетел" in x for x in texts) and any("Победители: Вика, Орг" in x for x in texts)

    # После конца испытания нарушения уже не считаются
    night_upload(client, c["token"], "com.zhiliaoapp.musically", "TikTok", 2, t, t + 60 * SEC)
    by = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]}
    assert by["Вика"]["game"]["state"] == "winner"

    # Новый раунд: все снова в игре
    client.post("/api/v1/group/new-round", headers=auth(org["token"]))
    lst = client.get("/api/v1/members", headers=auth(a["token"])).json()
    assert lst["round"] == 2 and all(m["game"]["state"] == "in" for m in lst["members"])


def test_round_ends_by_time_all_survivors_win(env, monkeypatch):
    db, client = env
    monkeypatch.setattr(main.game, "FINISH_GRACE_MS", 48 * 3_600_000)  # не зависеть от времени запуска
    monkeypatch.setattr(main.game, "check_silent", lambda *a, **k: None)  # «тишина» проверяется отдельно
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    b = join(client, org["invite_code"], "Боря")
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], a["member_id"], b["member_id"], ms=night.bed_ms - 3_600_000)
    backdate_round(db, gid, night.bed_ms - 3_600_000, nights=1)  # испытание — одна ночь, и она прошла
    t = night.bed_ms + 20 * MIN
    night_upload(client, b["token"], "org.telegram.messenger", "Telegram", 4, t, t + 30 * SEC)
    upload(client, org["token"], {"perm": True, "sessions": []})
    # Аня ещё не прислала данные за последнюю ночь — итог ждёт её
    assert client.get("/api/v1/group", headers=auth(org["token"])).json()["round"]["finished_at"] is None
    upload(client, a["token"], {"perm": True, "sessions": []})
    rnd = client.get("/api/v1/group", headers=auth(org["token"])).json()["round"]
    assert rnd["finish_kind"] == "time" and sorted(rnd["winners"]) == ["Аня", "Орг"]
    assert rnd["nights_done"] == 1


def test_everyone_out_means_no_winners(env):
    db, client = env
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], a["member_id"], ms=night.bed_ms - 3_600_000)
    t = night.bed_ms + 20 * MIN
    night_upload(client, a["token"], "com.zhiliaoapp.musically", "TikTok", 2, t, t + 15 * SEC)
    night_upload(client, org["token"], "com.zhiliaoapp.musically", "TikTok", 2, t, t + 15 * SEC)
    rnd = client.get("/api/v1/group", headers=auth(org["token"])).json()["round"]
    assert rnd["finish_kind"] == "all_out" and rnd["winners"] == []
    lst = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]
    assert all(m["game"]["state"] == "out" for m in lst)


def test_events_hidden_during_night(env, monkeypatch):
    db, client = env
    org = create(client)
    gid = db.member(org["member_id"])["group_id"]
    db.add_event(gid, None, "round", now_ms(), "тест")
    monkeypatch.setattr(main.game, "describe_night", lambda *a, **k: {"in_night": True})
    r = upload(client, org["token"], {"perm": True, "ev": 0}).json()
    assert r["events"] == []


def test_short_uses_and_hidden_clock_are_fine(env):
    db, client = env
    org = create(client)
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], ms=night.bed_ms - 3_600_000)
    t = night.bed_ms + 10 * MIN
    clock = "com.google.android.deskclock"
    upload(client, org["token"], {
        "perm": True, "apps": {clock: ["Часы", -1], "com.calc": ["Калькулятор", -1]},
        "used": [[t, t + 5 * MIN], [t + 60 * MIN, t + 61 * MIN]],
        "sessions": [[clock, t, t + 5 * MIN], ["com.calc", t + 60 * MIN, t + 61 * MIN]],
    })
    me = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"][0]
    assert me["game"]["state"] == "in"
    # Каталог приложений для организатора «Часы» не показывает
    apps = client.get("/api/v1/group/apps", headers=auth(org["token"])).json()["apps"]
    assert [a["pkg"] for a in apps] == ["com.calc"]


def test_permission_revoked_at_night_eliminates(env, monkeypatch):
    db, client = env
    org = create(client)
    gid = db.member(org["member_id"])["group_id"]
    s = db.settings_versions(gid)[0][1]
    now = now_ms()
    fake = rules.Night(rules.local_date(now, 180), now - MIN, now + 60 * MIN)
    monkeypatch.setattr(main.game.rules, "current_night", lambda *a, **k: (s, fake))
    upload(client, org["token"], {"perm": False, "screen_on": True})
    me = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"][0]
    assert me["game"]["state"] == "out" and "слежку" in me["game"]["reason"]


def test_group_creation_rate_limit(env):
    _, client = env
    for i in range(5):
        create(client, name=f"n{i}")
    r = client.post("/api/v1/groups", json={"group_name": "x", "name": "y"})
    assert r.status_code == 429





def test_eliminated_member_is_not_tracked(env):
    db, client = env
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, a["member_id"], ms=night.bed_ms - 3_600_000)
    t = night.bed_ms + 20 * MIN
    r = night_upload(client, a["token"], "com.zhiliaoapp.musically", "TikTok", 2, t, t + 15 * SEC).json()
    # Телефон сразу узнаёт о своём вылете — даже если сейчас ночь
    assert r["game"]["state"] == "out" and r["tracking"] is False
    # Дальше данные не сохраняются вовсе
    later = now_ms() - 5 * MIN
    upload(client, a["token"], {"perm": True, "screen_on": True, "apps": {"com.x": ["X", -1]},
                                "used": [[later, later + MIN]], "sessions": [["com.x", later, later + MIN]],
                                "cur": {"pkg": "com.x", "since": later}})
    assert db.sessions_between(a["member_id"], later - 1, later + 2 * MIN) == []
    assert db.intervals_between("used_intervals", a["member_id"], later - 1, later + 2 * MIN) == []
    by = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]}
    assert by["Аня"]["status"]["state"] == "untracked" and by["Аня"]["status"]["app_name"] is None
    # Новый раунд — снова в игре и снова отслеживается
    client.post("/api/v1/group/new-round", headers=auth(org["token"]))
    r = upload(client, a["token"], {"perm": True, "sessions": []}).json()
    assert r["game"]["state"] == "in" and r["tracking"] is True


def test_pause_does_not_hide_the_night(env):
    """«Баюшки» поставили на паузу ночью и включили утром: журнал Android всё равно
    покажет ночь целиком — нарушение найдётся по данным, пришедшим позже."""
    db, client = env
    org = create(client)
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], ms=night.bed_ms - 3_600_000)
    t = night.bed_ms + 30 * MIN
    upload(client, org["token"], {"perm": True, "used": [[t, t + 10 * MIN]],
                                  "sessions": [["com.calc", t, t + 10 * MIN]]})
    me = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"][0]
    assert me["game"]["state"] == "out" and me["game"]["at"] == t + 2 * MIN + 1


def test_silent_after_night_eliminates_at_noon(env, monkeypatch):
    db, client = env
    monkeypatch.setattr(main.game, "SILENT_DEADLINE_HOUR", 6)  # не зависеть от времени запуска
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], a["member_id"], ms=night.bed_ms - 3_600_000)
    backdate_round(db, gid, night.bed_ms - 3_600_000, nights=7)
    upload(client, org["token"], {"perm": True, "sessions": []})  # организатор на связи после ночи
    by = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]}
    assert by["Аня"]["game"]["state"] == "out" and "не прислал" in by["Аня"]["game"]["reason"]
    assert by["Орг"]["game"]["state"] == "in"


def test_silent_rule_respects_setting(env, monkeypatch):
    db, client = env
    monkeypatch.setattr(main.game, "SILENT_DEADLINE_HOUR", 6)
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], a["member_id"], ms=night.bed_ms - 3_600_000)
    s = rules.validate_settings(db.settings_versions(gid)[-1][1], {"tracking_off_eliminates": False})
    db.add_settings(gid, 0, s)
    with db.conn() as c:
        c.execute("UPDATE groups SET round_started_at=? WHERE id=?", (night.bed_ms - 3_600_000, gid))
    by = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]}
    assert by["Аня"]["game"]["state"] == "in"


def test_member_timezones(env):
    """Отбой 00:30 наступает у каждого по его местному времени."""
    db, client = env
    org = create(client)  # Москва (+3)
    nsk = join(client, org["invite_code"], "Новосибирск")
    upload(client, nsk["token"], {"perm": True, "tz": 420, "tz_id": "Asia/Novosibirsk", "sessions": []})
    lst = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]
    by = {m["name"]: m for m in lst}
    assert by["Новосибирск"]["tz"] == {"offset_min": 420, "id": "Asia/Novosibirsk"}
    assert by["Орг"]["tz"]["offset_min"] == 180
    m = db.member(nsk["member_id"])
    g = db.group(m["group_id"])
    n_msk = main.game.describe_night(db, g["id"], member=db.member(org["member_id"]))
    n_nsk = main.game.describe_night(db, g["id"], member=m)
    # В Новосибирске отбой на 4 часа раньше (по абсолютному времени)
    assert (n_msk["bed_ms"] - n_nsk["bed_ms"]) % (24 * 3_600_000) == 4 * 3_600_000


def test_night_eliminations_use_member_timezone(env):
    db, client = env
    org = create(client)
    nsk = join(client, org["invite_code"], "Новосибирск")
    upload(client, nsk["token"], {"perm": True, "tz": 420, "sessions": []})
    gid = db.member(org["member_id"])["group_id"]
    g = db.group(gid)
    now = now_ms()
    nights = [n for _, n in rules.nights_between(now - 3 * 24 * 3_600_000, now, db.settings_versions(gid), 420)
              if n.wake_ms < now]
    night = nights[-1]
    backdate(db, nsk["member_id"], ms=night.bed_ms - 3_600_000)
    t = night.bed_ms + 10 * MIN  # 00:40 в Новосибирске = 20:40 в Москве: для москвича ещё не ночь
    night_upload(client, nsk["token"], "com.zhiliaoapp.musically", "TikTok", 2, t, t + 15 * SEC)
    m = db.member(nsk["member_id"])
    assert m["eliminated_at"] == t + 10 * SEC
    assert any("вылетел в 00:40" in e["text"] for e in db.events_after(gid, 0))


def test_curfew_status_and_night_revealed_after_wake(env, monkeypatch):
    db, client = env
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    t = now_ms() - 20 * MIN
    upload(client, a["token"], {"perm": True, "screen_on": True, "screen_since": t,
                                "apps": {"com.calc": ["Калькулятор", -1]}, "cur": {"pkg": "com.calc", "since": t},
                                "sessions": [["com.calc", t, t + 10 * MIN]]})
    # У Ани сейчас отбой: друзья видят только «отбой», без приложения
    monkeypatch.setattr(main.game, "night_phase", lambda db, g, m, now: "curfew" if m["name"] == "Аня" else "day")
    by = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]}
    assert by["Аня"]["phase"] == "curfew" and by["Аня"]["status"]["state"] == "curfew"
    assert by["Аня"]["status"]["app_name"] is None
    # Сама Аня видит себя как есть
    own = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(a["token"])).json()["members"]}
    assert own["Аня"]["status"]["state"] == "active"
    # За 40 минут до отбоя — видно приложение
    monkeypatch.setattr(main.game, "night_phase", lambda db, g, m, now: "pre")
    by = {m["name"]: m for m in client.get("/api/v1/members", headers=auth(org["token"])).json()["members"]}
    assert by["Аня"]["status"]["app_name"] == "Калькулятор"


def test_cancel_night(env):
    db, client = env
    org = create(client)
    a = join(client, org["invite_code"], "Аня")
    assert client.post("/api/v1/group/cancel-night", headers=auth(a["token"])).status_code == 403
    info = client.post("/api/v1/group/cancel-night", headers=auth(org["token"])).json()
    target = info["night"]["cancel_target"]
    assert target["cancelled"] is True
    # Следующий отбой для телефона — уже следующая ночь
    assert info["night"]["bed_ms"] != target["bed_ms"] and info["night"]["in_night"] is False
    # До начала — можно вернуть
    if target["bed_ms"] > now_ms():
        info = client.post("/api/v1/group/restore-night", headers=auth(org["token"])).json()
        assert info["night"]["cancel_target"]["cancelled"] is False
        assert info["night"]["bed_ms"] == target["bed_ms"]
    else:
        assert client.post("/api/v1/group/restore-night", headers=auth(org["token"])).status_code == 409


def test_cancel_running_night_stops_rules(env):
    db, client = env
    org = create(client)
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    backdate(db, org["member_id"], ms=night.bed_ms - 3_600_000)
    # Отбой отменили в 01:00 — после этого можно сидеть хоть час
    db.cancel_night(gid, night.wake_date.isoformat(), night.bed_ms + 30 * MIN)
    t = night.bed_ms + 40 * MIN
    upload(client, org["token"], {"perm": True, "apps": {"com.zhiliaoapp.musically": ["TikTok", 2]},
                                  "used": [[t, t + 60 * MIN]],
                                  "sessions": [["com.zhiliaoapp.musically", t, t + 60 * MIN]]})
    me = client.get("/api/v1/members", headers=auth(org["token"])).json()["members"][0]
    assert me["game"]["state"] == "in"
    # А ночь всё равно идёт в счёт испытания
    assert rules.round_nights(night.bed_ms - MIN, db.settings_versions(gid), 180, 1)[0].wake_date == night.wake_date


def test_rules_change_applies_immediately(env):
    db, client = env
    org = create(client)
    r = client.put("/api/v1/group", json={"settings": {"bedtime": "23:15", "own_app_s": 120}},
                   headers=auth(org["token"])).json()
    assert r["settings"]["bedtime"] == "23:15" and r["settings"]["own_app_s"] == 120
    assert "pending_settings" not in r


def test_catalog_hides_own_app_and_system_screens(env):
    db, client = env
    org = create(client)
    gid = db.member(org["member_id"])["group_id"]
    night = last_night(db, gid)
    t = night.bed_ms - 5 * 3_600_000
    upload(client, org["token"], {
        "perm": True,
        "apps": {"ru.bayushki.app": ["Баюшки", -1], "com.android.intentresolver": ["IntentResolver", -1],
                 "com.calc": ["Калькулятор", -1]},
        "sessions": [["ru.bayushki.app", t, t + MIN], ["com.android.intentresolver", t + MIN, t + 2 * MIN],
                     ["com.calc", t + 2 * MIN, t + 3 * MIN]],
    })
    apps = client.get("/api/v1/group/apps", headers=auth(org["token"])).json()["apps"]
    assert [a["pkg"] for a in apps] == ["com.calc"]
