import gzip
import json

import pytest
from fastapi.testclient import TestClient

from app import main, rules
from app.db import Database, now_ms

MIN = 60_000


@pytest.fixture()
def env(tmp_path):
    db = Database(tmp_path / "t.sqlite3")
    main.set_db(db)
    main._join_fails.clear()
    main._creations.clear()
    main._uploads.clear()
    group = db.create_group("Тест", rules.validate_settings({}, {}))
    client = TestClient(main.app)
    return db, group, client


def join(client, code, name):
    r = client.post("/api/v1/join", json={"invite_code": code, "name": name})
    assert r.status_code == 200, r.text
    return r.json()


def auth(tok):
    return {"Authorization": f"Bearer {tok}"}


def upload(client, tok, payload, gz=True):
    body = json.dumps(payload).encode()
    headers = {**auth(tok), "Content-Type": "application/json"}
    if gz:
        body = gzip.compress(body)
        headers["Content-Encoding"] = "gzip"
    return client.post("/api/v1/upload", content=body, headers=headers)


def test_join_and_wrong_code(env):
    db, group, client = env
    assert client.post("/api/v1/join", json={"invite_code": "NOPE1234", "name": "A"}).status_code == 404
    j = join(client, group["invite_code"].lower(), "  Вася  ")
    assert j["name"] == "Вася" and j["group_name"] == "Тест"
    # имя занято
    r = client.post("/api/v1/join", json={"invite_code": group["invite_code"], "name": "вася"})
    assert r.status_code == 409
    # админ разрешил перепривязку → новый токен, старый не работает
    db.set_member_flag(j["member_id"], "rejoin_allowed", 1)
    j2 = join(client, group["invite_code"], "Вася")
    assert j2["member_id"] == j["member_id"]
    assert client.get("/api/v1/me", headers=auth(j["token"])).status_code == 401
    assert client.get("/api/v1/me", headers=auth(j2["token"])).status_code == 200


def test_join_rate_limit(env):
    _, _, client = env
    for _ in range(10):
        client.post("/api/v1/join", json={"invite_code": "WRONG123", "name": "x"})
    assert client.post("/api/v1/join", json={"invite_code": "WRONG123", "name": "x"}).status_code == 429


def test_upload_merges_and_statuses(env, monkeypatch):
    db, group, client = env
    # Делаем вид, что сейчас ночь: чужие приложения видны (окно видимости проверяется отдельно).
    monkeypatch.setattr(main.game, "reveal_windows", lambda *a, **k: [(0, 10 ** 15)])
    monkeypatch.setattr(main.game, "current_night", lambda *a, **k: None)  # не зависеть от времени запуска
    a = join(client, group["invite_code"], "Аня")
    b = join(client, group["invite_code"], "Боря")
    t = now_ms() - 30 * MIN  # всё в прошлом; "now" не шлём — без поправки часов
    # Первая отправка: TikTok открыт сейчас.
    r = upload(client, a["token"], {
        "screen_on": True, "screen_since": t - 20 * MIN, "perm": True, "ver": 10000,
        "apps": {"com.tiktok": "TikTok"},
        "cur": {"pkg": "com.tiktok", "since": t - 12 * MIN},
        "screens": [[t - 20 * MIN, t]],
        "sessions": [["com.tiktok", t - 12 * MIN, t]],
    })
    assert r.status_code == 200, r.text
    # Повторная отправка с перекрытием (сессия продолжилась) — не должно быть дублей.
    t2 = t + 5 * MIN
    upload(client, a["token"], {
        "screen_on": False, "screen_since": t2, "perm": True,
        "apps": {"com.tiktok": "TikTok"},
        "screens": [[t - 20 * MIN, t2]],
        "sessions": [["com.tiktok", t - 12 * MIN, t2]],
    }, gz=False)
    tl = client.get(f"/api/v1/members/{a['member_id']}/timeline",
                    params={"start": t - 60 * MIN, "end": t + 60 * MIN}, headers=auth(b["token"])).json()
    assert tl["screens"] == [[t - 20 * MIN, t2]]
    assert len(tl["sessions"]) == 1 and tl["sessions"][0]["name"] == "TikTok"
    assert tl["totals"] == [["TikTok", 17 * MIN]]

    lst = client.get("/api/v1/members", headers=auth(b["token"])).json()["members"]
    by = {m["name"]: m for m in lst}
    assert by["Аня"]["status"]["state"] == "idle"  # экран выключен
    assert by["Боря"]["status"] == {"state": "stale", "reason": "no_data", "app_name": None,
                                    "since_ms": None, "last_upload_ms": None}
    assert by["Аня"]["is_owner"] and not by["Боря"]["is_owner"]  # первый вошедший — организатор
    assert by["Боря"]["is_me"] and not by["Аня"]["is_me"]


def test_clock_skew_and_garbage_filtered(env):
    db, group, client = env
    a = join(client, group["invite_code"], "Аня")
    t = now_ms()
    phone_now = t - 10 * MIN  # часы телефона отстают на 10 минут
    upload(client, a["token"], {
        "now": phone_now, "screen_on": True, "screen_since": phone_now - MIN,
        "screens": [[phone_now - MIN, phone_now], [1, 2]],  # второй — мусор из 1970 года
        "sessions": [],
    })
    tl = client.get(f"/api/v1/members/{a['member_id']}/timeline",
                    params={"start": t - 60 * MIN, "end": t + MIN}, headers=auth(a["token"])).json()
    assert len(tl["screens"]) == 1
    s, e = tl["screens"][0]
    assert abs(e - t) < 5_000


def test_permission_revoked_is_stale(env):
    _, group, client = env
    a = join(client, group["invite_code"], "Аня")
    t = now_ms()
    upload(client, a["token"], {"now": t, "screen_on": True, "screen_since": t, "perm": False})
    m = client.get("/api/v1/members", headers=auth(a["token"])).json()["members"][0]
    assert m["status"]["state"] == "stale" and m["status"]["reason"] == "permission"


def test_other_group_hidden(env):
    db, group, client = env
    other = db.create_group("Чужие", rules.validate_settings({}, {}))
    a = join(client, group["invite_code"], "Аня")
    x = join(client, other["invite_code"], "Икс")
    t = now_ms()
    r = client.get(f"/api/v1/members/{a['member_id']}/timeline",
                   params={"start": t - MIN, "end": t}, headers=auth(x["token"]))
    assert r.status_code == 404
    names = [m["name"] for m in client.get("/api/v1/members", headers=auth(x["token"])).json()["members"]]
    assert names == ["Икс"]


def test_no_token(env):
    _, _, client = env
    assert client.get("/api/v1/members").status_code == 401


def test_apps_hidden_from_friends_outside_night(env, monkeypatch):
    """До «за 40 минут до отбоя» друзья видят только «в телефоне или нет», без приложений."""
    db, group, client = env
    a = join(client, group["invite_code"], "Аня")
    b = join(client, group["invite_code"], "Боря")
    monkeypatch.setattr(main.game, "reveal_windows", lambda *a, **k: [])
    monkeypatch.setattr(main.game, "night_phase", lambda *a, **k: "day")
    monkeypatch.setattr(main.game, "current_night", lambda *a, **k: None)
    t = now_ms() - 10 * MIN
    upload(client, a["token"], {
        "screen_on": True, "screen_since": t, "perm": True, "apps": {"com.tiktok": "TikTok"},
        "cur": {"pkg": "com.tiktok", "since": t}, "screens": [[t, t + 5 * MIN]],
        "sessions": [["com.tiktok", t, t + 5 * MIN]],
    })
    params = {"start": t - 60 * MIN, "end": t + 60 * MIN}
    friend = client.get(f"/api/v1/members/{a['member_id']}/timeline", params=params, headers=auth(b["token"])).json()
    assert friend["sessions"] == [] and friend["totals"] == []
    assert friend["screens"] == [[t, t + 5 * MIN]]  # что экран горел — видно
    assert friend["member"]["status"]["state"] == "active" and friend["member"]["status"]["app_name"] is None
    own = client.get(f"/api/v1/members/{a['member_id']}/timeline", params=params, headers=auth(a["token"])).json()
    assert len(own["sessions"]) == 1 and own["member"]["status"]["app_name"] == "TikTok"  # себе — всё
    lst = client.get("/api/v1/members", headers=auth(b["token"])).json()["members"]
    assert {m["name"]: m for m in lst}["Аня"]["status"]["app_name"] is None


# ---------- защита сервера ----------

def test_gzip_bomb_rejected(env):
    """5 МБ сжатых нулей не должны разворачиваться в память целиком."""
    _, group, client = env
    a = join(client, group["invite_code"], "Аня")
    bomb = gzip.compress(b"0" * (main.MAX_UNZIPPED + 1024 * 1024))
    assert len(bomb) < main.MAX_BODY
    r = client.post("/api/v1/upload", content=bomb,
                    headers={**auth(a["token"]), "Content-Encoding": "gzip", "Content-Type": "application/json"})
    assert r.status_code == 413


def test_oversized_and_broken_bodies(env):
    _, group, client = env
    a = join(client, group["invite_code"], "Аня")
    big = b"x" * (main.MAX_BODY + 1)
    assert client.post("/api/v1/upload", content=big, headers=auth(a["token"])).status_code == 413
    r = client.post("/api/v1/upload", content=b"not gzip at all",
                    headers={**auth(a["token"]), "Content-Encoding": "gzip"})
    assert r.status_code == 400


def test_upload_rate_limit(env, monkeypatch):
    _, group, client = env
    monkeypatch.setattr(main, "UPLOADS_PER_WINDOW", 2)
    a = join(client, group["invite_code"], "Аня")
    t = now_ms()
    for _ in range(2):
        assert upload(client, a["token"], {"now": t, "screen_on": False}).status_code == 200
    assert upload(client, a["token"], {"now": t, "screen_on": False}).status_code == 429


def test_forged_forwarded_for_does_not_bypass_limits(env):
    """Подставной X-Forwarded-For не даёт обойти лимит неверных кодов."""
    _, _, client = env
    for i in range(10):
        client.post("/api/v1/join", json={"invite_code": "WRONG123", "name": "x"},
                    headers={"X-Forwarded-For": f"10.0.0.{i}"})
    r = client.post("/api/v1/join", json={"invite_code": "WRONG123", "name": "x"},
                    headers={"X-Forwarded-For": "10.0.0.99"})
    assert r.status_code == 429


def test_api_docs_hidden(env):
    _, _, client = env
    for path in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(path).status_code == 404


def test_friend_screen_hidden_during_curfew(env, monkeypatch):
    """Пока у друга отбой, не видно даже, включал ли он экран. Себе — видно всё."""
    from types import SimpleNamespace
    _, group, client = env
    a = join(client, group["invite_code"], "Аня")
    b = join(client, group["invite_code"], "Боря")
    t = now_ms() - 30 * MIN
    bed = t + 10 * MIN
    monkeypatch.setattr(main.game, "current_night", lambda *a, **k: ({}, SimpleNamespace(bed_ms=bed)))
    upload(client, a["token"], {
        "screen_on": False, "perm": True,
        "screens": [[t, t + 5 * MIN], [t + 8 * MIN, t + 20 * MIN], [t + 25 * MIN, t + 28 * MIN]],
        "sessions": [],
    })
    params = {"start": t - MIN, "end": t + 60 * MIN}
    friend = client.get(f"/api/v1/members/{a['member_id']}/timeline", params=params, headers=auth(b["token"])).json()
    assert friend["screens"] == [[t, t + 5 * MIN], [t + 8 * MIN, bed]]
    own = client.get(f"/api/v1/members/{a['member_id']}/timeline", params=params, headers=auth(a["token"])).json()
    assert len(own["screens"]) == 3
