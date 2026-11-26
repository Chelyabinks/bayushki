"""HTTP API сервера «Баюшки». Запуск: uv run uvicorn app.main:app --port 8787"""
from __future__ import annotations

import json
import os
import time
import zlib
from collections import defaultdict, deque
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

from . import game, rules
from .db import Database, NameTaken, now_ms
from .status import compute_status

DB_PATH = os.environ.get("SPY_DB", str(Path(__file__).resolve().parent.parent / "data" / "spy.sqlite3"))
MAX_BODY = 5 * 1024 * 1024           # сжатый пакет с телефона
MAX_UNZIPPED = 8 * MAX_BODY          # и он же распакованный: защита от «zip-бомбы»
MAX_AGE_MS = 8 * 24 * 3_600_000  # данные старше 8 дней в загрузке не принимаем
RETENTION_DAYS = int(os.environ.get("SPY_RETENTION_DAYS", "90"))
UPLOADS_PER_WINDOW, UPLOAD_WINDOW_S = 60, 600  # телефон шлёт раз в несколько минут; 60 за 10 мин — с запасом

# Описание API (/docs, /openapi.json) наружу не показываем.
app = FastAPI(title="Баюшки", version="2", docs_url=None, redoc_url=None, openapi_url=None)
_db: Database | None = None


def get_db() -> Database:
    global _db
    if _db is None:
        Path(DB_PATH).parent.mkdir(parents=True, exist_ok=True)
        _db = Database(DB_PATH)
        _db.purge_older_than(now_ms() - RETENTION_DAYS * 24 * 3_600_000)
    return _db


def set_db(db: Database) -> None:
    """Для тестов."""
    global _db
    _db = db


def current_member(authorization: str = Header(default=""), db: Database = Depends(get_db)):
    token = authorization.removeprefix("Bearer ").strip()
    member = db.member_by_token(token) if token else None
    if member is None:
        raise HTTPException(401, "Неверный или отозванный токен")
    return member


def owner_member(member=Depends(current_member), db: Database = Depends(get_db)):
    if db.group(member["group_id"])["owner_id"] != member["id"]:
        raise HTTPException(403, "Это может только организатор группы")
    return member


# ---------- простая защита от перебора инвайт-кодов и спама группами ----------
_join_fails: dict[str, deque] = defaultdict(deque)
_creations: dict[str, deque] = defaultdict(deque)
_uploads: dict[int, deque] = defaultdict(deque)


def _client_ip(request: Request) -> str:
    """Настоящий адрес клиента. Заголовок X-Forwarded-For сам не читаем — его может подделать кто угодно.
    uvicorn с --proxy-headers уже разобрал его и взял адрес, который дописал ngrok."""
    return request.client.host if request.client else "?"


def _check_rate(store: dict, key, limit: int, window_s: int, text: str) -> None:
    q = store[key]
    cutoff = time.monotonic() - window_s
    while q and q[0] < cutoff:
        q.popleft()
    if not q:
        del store[key]  # не копим пустые записи
        return
    if len(q) >= limit:
        raise HTTPException(429, text)


async def _read_body(request: Request) -> bytes:
    """Читает тело, но не больше MAX_BODY — огромный запрос обрываем сразу, не держа его в памяти."""
    declared = request.headers.get("content-length", "")
    if declared.isdigit() and int(declared) > MAX_BODY:
        raise HTTPException(413, "Слишком большой пакет")
    raw = bytearray()
    async for chunk in request.stream():
        raw += chunk
        if len(raw) > MAX_BODY:
            raise HTTPException(413, "Слишком большой пакет")
    return bytes(raw)


def _gunzip(raw: bytes) -> bytes:
    """Распаковка с потолком: «zip-бомба» из 5 МБ не развернётся в гигабайты."""
    d = zlib.decompressobj(wbits=31)
    try:
        out = d.decompress(raw, MAX_UNZIPPED)
    except zlib.error:
        raise HTTPException(400, "Битый gzip")
    if d.unconsumed_tail:
        raise HTTPException(413, "Слишком большой пакет")
    return out


def _clean_name(name: str) -> str:
    name = " ".join(name.split())
    if not name:
        raise HTTPException(422, "Имя не может быть пустым")
    return name


# ---------- модели ----------
class JoinIn(BaseModel):
    invite_code: str = Field(min_length=4, max_length=32)
    name: str = Field(min_length=1, max_length=32)
    tz_offset_min: int | None = Field(default=None, ge=-720, le=840)
    tz_id: str | None = Field(default=None, max_length=64)


class CreateIn(BaseModel):
    group_name: str = Field(min_length=1, max_length=40)
    name: str = Field(min_length=1, max_length=32)
    tz_offset_min: int = Field(default=180, ge=-720, le=840)
    tz_id: str | None = Field(default=None, max_length=64)
    settings: dict = Field(default_factory=dict)


class SettingsIn(BaseModel):
    group_name: str | None = Field(default=None, min_length=1, max_length=40)
    tz_offset_min: int | None = Field(default=None, ge=-720, le=840)
    settings: dict = Field(default_factory=dict)


def _registration(db: Database, member, token: str) -> dict:
    g = db.group(member["group_id"])
    return {"token": token, "member_id": member["id"], "name": member["name"], "group_name": g["name"],
            "invite_code": g["invite_code"], "is_owner": g["owner_id"] == member["id"]}


@app.get("/health")
def health():
    return {"ok": True, "time": now_ms()}


@app.post("/api/v1/groups")
def create_group(body: CreateIn, request: Request, db: Database = Depends(get_db)):
    ip = _client_ip(request)
    _check_rate(_creations, ip, 5, 3600, "Слишком много новых групп, попробуй через час")
    try:
        settings = rules.validate_settings({}, body.settings)
    except rules.SettingsError as e:
        raise HTTPException(422, str(e))
    group = db.create_group(" ".join(body.group_name.split()), settings, body.tz_offset_min)
    _creations[ip].append(time.monotonic())
    member, token = db.join(group["id"], _clean_name(body.name))
    db.set_member_tz(member["id"], body.tz_offset_min, body.tz_id)
    return _registration(db, member, token)


@app.post("/api/v1/join")
def join(body: JoinIn, request: Request, db: Database = Depends(get_db)):
    ip = _client_ip(request)
    _check_rate(_join_fails, ip, 10, 600, "Слишком много неверных попыток, подожди 10 минут")
    group = db.group_by_invite(body.invite_code)
    if group is None:
        _join_fails[ip].append(time.monotonic())
        raise HTTPException(404, "Такого кода группы нет")
    try:
        member, token = db.join(group["id"], _clean_name(body.name))
        if body.tz_offset_min is not None:
            db.set_member_tz(member["id"], body.tz_offset_min, body.tz_id)
    except NameTaken:
        raise HTTPException(409, "Это имя в группе уже занято. Если это ты на новом телефоне — попроси "
                                 "организатора нажать «Разрешить перевход» у твоего имени.")
    return _registration(db, member, token)


@app.get("/api/v1/me")
def me(member=Depends(current_member), db: Database = Depends(get_db)):
    g = db.group(member["group_id"])
    return {"member_id": member["id"], "name": member["name"], "group_name": g["name"],
            "is_owner": g["owner_id"] == member["id"]}


@app.post("/api/v1/leave")
def leave(member=Depends(current_member), db: Database = Depends(get_db)):
    db.set_member_flag(member["id"], "active", 0)
    return {"ok": True}


# ---------- группа и настройки ----------

def _group_info(db: Database, member) -> dict:
    g = db.group(member["group_id"])
    settings = game.current_settings(db, g["id"])
    owner = db.member(g["owner_id"]) if g["owner_id"] else None
    return {
        "id": g["id"],
        "name": g["name"],
        "invite_code": g["invite_code"],
        "is_owner": g["owner_id"] == member["id"],
        "owner_name": owner["name"] if owner else None,
        "settings": settings,
        "rules": rules.rules_text(settings),
        "categories": rules.CATEGORIES,
        "night": game.describe_night(db, g["id"], member=db.member(member["id"])),
        "round": game.round_info(db, g, viewer=db.member(member["id"])),
    }


@app.get("/api/v1/group")
def group_info(member=Depends(current_member), db: Database = Depends(get_db)):
    return _group_info(db, member)


@app.put("/api/v1/group")
def update_group(body: SettingsIn, member=Depends(owner_member), db: Database = Depends(get_db)):
    gid = member["group_id"]
    fields = {}
    if body.group_name is not None:
        fields["name"] = " ".join(body.group_name.split())
    if body.tz_offset_min is not None:
        fields["tz_offset_min"] = body.tz_offset_min
    if body.settings:
        try:
            new = rules.validate_settings(game.current_settings(db, gid), body.settings)
        except rules.SettingsError as e:
            raise HTTPException(422, str(e))
        db.add_settings(gid, now_ms(), new)  # новые правила действуют сразу
    db.update_group(gid, **fields)
    return _group_info(db, member)


@app.post("/api/v1/group/new-round")
def new_round(member=Depends(owner_member), db: Database = Depends(get_db)):
    no = db.start_round(member["group_id"])
    n = game.current_settings(db, member["group_id"])["round_nights"]
    db.add_event(member["group_id"], None, "round", now_ms(),
                 f"🔄 Начался раунд {no}: все снова в игре! Испытание — {n} {rules.nights_word(n)}.")
    return _group_info(db, member)


@app.post("/api/v1/group/finish")
def finish_round(member=Depends(owner_member), db: Database = Depends(get_db)):
    if not game.finish(db, member["group_id"], "manual"):
        raise HTTPException(409, "Испытание уже завершено")
    return _group_info(db, member)


@app.post("/api/v1/group/cancel-night")
def cancel_night(member=Depends(owner_member), db: Database = Depends(get_db)):
    """Отменить ближайший отбой (идущий — закончить сейчас). Ночь всё равно идёт в счёт испытания."""
    text = game.cancel_tonight(db, member["group_id"], member=member)
    if text is None:
        raise HTTPException(409, "Нечего отменять: отбой выключен на всю неделю")
    db.add_event(member["group_id"], None, "night_cancelled", now_ms(), text)
    return _group_info(db, member)


@app.post("/api/v1/group/restore-night")
def restore_night(member=Depends(owner_member), db: Database = Depends(get_db)):
    if not game.restore_tonight(db, member["group_id"], member=member):
        raise HTTPException(409, "Вернуть отбой можно только до его начала")
    db.add_event(member["group_id"], None, "night_restored", now_ms(), "🌙 Отбой сегодня всё-таки будет, как обычно.")
    return _group_info(db, member)


@app.post("/api/v1/group/new-invite")
def new_invite(member=Depends(owner_member), db: Database = Depends(get_db)):
    db.regenerate_invite(member["group_id"])
    return _group_info(db, member)


def _target(db: Database, member, target_id: int):
    t = db.member(target_id)
    if t is None or t["group_id"] != member["group_id"]:
        raise HTTPException(404, "Нет такого участника в твоей группе")
    return t


@app.post("/api/v1/group/members/{target_id}/kick")
def kick(target_id: int, member=Depends(owner_member), db: Database = Depends(get_db)):
    t = _target(db, member, target_id)
    if t["id"] == member["id"]:
        raise HTTPException(400, "Себя удалить нельзя — используй «Выйти из группы»")
    db.set_member_flag(t["id"], "active", 0)
    return {"ok": True}


@app.post("/api/v1/group/members/{target_id}/allow-rejoin")
def allow_rejoin(target_id: int, member=Depends(owner_member), db: Database = Depends(get_db)):
    db.set_member_flag(_target(db, member, target_id)["id"], "rejoin_allowed", 1)
    return {"ok": True}


@app.post("/api/v1/group/members/{target_id}/make-owner")
def make_owner(target_id: int, member=Depends(owner_member), db: Database = Depends(get_db)):
    db.update_group(member["group_id"], owner_id=_target(db, member, target_id)["id"])
    return {"ok": True}


@app.get("/api/v1/group/apps")
def group_apps(member=Depends(owner_member), db: Database = Depends(get_db)):
    """Приложения, которыми участники пользовались за 30 дней, и как к ним относятся правила."""
    gid = member["group_id"]
    s = game.current_settings(db, gid)
    pkgs = [p for p in db.group_used_pkgs(gid, now_ms() - 30 * 24 * 3_600_000) if not rules.hidden_app(p)]
    pkgs = sorted(set(pkgs) | set(s["forbidden_apps"]) | set(s["allowed_apps"]))
    catalog = db.app_catalog(pkgs)
    ar = rules.AppRules.from_settings(s, {p: rules.app_categories(p, r["android_cat"]) for p, r in catalog.items()})
    out = []
    for p in pkgs:
        row = catalog.get(p)
        out.append({
            "pkg": p,
            "name": row["name"] if row else p,
            "categories": sorted(ar.categories_of.get(p) or rules.app_categories(p, None)),
            "locked": rules.always_allowed(p),
            "forbidden": ar.forbidden(p),
            "allowed": ar.exempt(p),
        })
    out.sort(key=lambda a: (not a["forbidden"], a["name"].casefold()))
    return {"apps": out}


# ---------- загрузка данных с телефона ----------

@app.post("/api/v1/upload")
async def upload(request: Request, member=Depends(current_member), db: Database = Depends(get_db)):
    _check_rate(_uploads, member["id"], UPLOADS_PER_WINDOW, UPLOAD_WINDOW_S, "Слишком часто, попробуй позже")
    _uploads[member["id"]].append(time.monotonic())
    raw = await _read_body(request)
    if request.headers.get("content-encoding", "").lower() == "gzip":
        raw = _gunzip(raw)
    elif len(raw) > MAX_BODY:
        raise HTTPException(413, "Слишком большой пакет")
    try:
        p = json.loads(raw)
    except ValueError:
        raise HTTPException(400, "Битый JSON")

    received = now_ms()
    # Телефон может спешить/отставать: сдвигаем все его времена на разницу часов.
    skew = 0
    if isinstance(p.get("now"), int) and abs(received - p["now"]) > 60_000:
        skew = received - p["now"]

    def fix(t) -> int:
        return int(t) + skew

    lo, hi = received - MAX_AGE_MS, received + 60_000

    def ok(s: int, e: int) -> bool:
        return lo <= s <= e <= hi

    # apps: {pkg: "Название"} (старый формат) или {pkg: ["Название", категория Android]}
    apps: dict[str, tuple[str, int | None]] = {}
    for pkg, v in list((p.get("apps") or {}).items())[:2000]:
        name, cat = (v[0], v[1] if len(v) > 1 else None) if isinstance(v, list) else (v, None)
        apps[str(pkg)[:200]] = (str(name)[:100], cat if isinstance(cat, int) else None)

    def intervals(key: str) -> list[tuple[int, int]]:
        out = []
        for item in (p.get(key) or [])[:20_000]:
            s, e = fix(item[0]), fix(item[1])
            if ok(s, e):
                out.append((s, e))
        return out

    sessions = []
    for item in (p.get("sessions") or [])[:20_000]:
        pkg = str(item[0])[:200]
        s, e = fix(item[1]), fix(item[2])
        if ok(s, e):
            sessions.append((pkg, apps.get(pkg, (pkg, None))[0], s, e))

    cur = p.get("cur") or None
    perm = bool(p.get("perm", True))
    state = {
        "screen_on": 1 if p.get("screen_on") else 0,
        "screen_since": fix(p["screen_since"]) if isinstance(p.get("screen_since"), int) else None,
        "current_pkg": cur.get("pkg") if cur else None,
        "current_name": apps.get(cur.get("pkg"), (cur.get("pkg"), None))[0] if cur else None,
        "current_since": fix(cur["since"]) if cur and isinstance(cur.get("since"), int) else None,
        "usage_permission": 1 if perm else 0,
        "health": json.dumps(p.get("health") or {}, ensure_ascii=False)[:2000],
        "app_version": int(p["ver"]) if isinstance(p.get("ver"), int) else None,
    }
    gid = member["group_id"]
    g = db.group(gid)
    # Часовой пояс телефон присылает сам: отбой у каждого — по его местному времени.
    tz_off = p.get("tz")
    if isinstance(tz_off, int) and not isinstance(tz_off, bool) and -720 <= tz_off <= 840:
        tz_id = p.get("tz_id")
        db.set_member_tz(member["id"], tz_off, str(tz_id)[:64] if isinstance(tz_id, str) else None)
    if _tracked(member, g):
        db.store_upload(member["id"], received_at=received, screens=intervals("screens"), used=intervals("used"),
                        sessions=sessions, apps=apps, state=state)
        if perm:
            game.evaluate_member(db, member["id"], received)
        else:
            game.tracking_off(db, member["id"], received)
    else:
        # Выбыл или раунд окончен — ничего о человеке не храним, только «приложение на связи».
        db.touch(member["id"], received, state["app_version"])
    game.check_round(db, gid, received)
    me = db.member(member["id"])
    g = db.group(gid)

    night = game.describe_night(db, gid, received, member=me)
    # Новости группы отдаём только после подъёма: ночью ничто не должно звать в телефон.
    events = []
    last_seen = p.get("ev")
    if isinstance(last_seen, int) and not night["in_night"]:
        events = [{"id": e["id"], "at": e["at_ms"], "text": e["text"]} for e in db.events_after(gid, last_seen)]
    return {
        "ok": True,
        "server_time": received,
        "night": night,
        "events": events,
        # Первый раз (ev не прислан) — просто запоминаем, с какого события начинать.
        "last_event_id": db.last_event_id(gid) if not isinstance(last_seen, int) else None,
        # Своё положение в игре телефон узнаёт сразу (о вылете — даже ночью),
        # tracking=false — данные больше не нужны, телефон перестаёт следить.
        "game": _game_state(me, g),
        "round": g["round_no"],
        "tracking": _tracked(me, g),
    }


# ---------- просмотр ----------

def _tracked(m, g) -> bool:
    """Следим только за теми, кто в игре, и только пока раунд идёт."""
    return m["eliminated_at"] is None and g["finished_at"] is None


def _status_for(viewer, m, g, now: int, phase: str) -> dict:
    """Что друзья видят о человеке прямо сейчас:
      - у него отбой — только «отбой» (что он делает ночью, станет видно после подъёма);
      - за 40 минут до отбоя — всё, включая приложение;
      - днём — только «в телефоне или нет»;
      - выбывших (и всех после конца раунда) не отслеживаем вовсе."""
    if not _tracked(m, g):
        return {"state": "untracked", "reason": None, "app_name": None, "since_ms": None,
                "last_upload_ms": m["last_upload_at"]}
    st = compute_status(m, now).to_dict()
    if m["id"] == viewer["id"]:
        return st
    if phase == "curfew":
        return {"state": "curfew", "reason": None, "app_name": None, "since_ms": None,
                "last_upload_ms": m["last_upload_at"]}
    if phase != "pre":
        st["app_name"] = None
    return st


def _tz_info(m, g) -> dict:
    return {"offset_min": game.tz(m, g), "id": m["tz_id"]}


def _game_state(m, g) -> dict:
    if m["won"]:
        return {"state": "winner", "at": g["finished_at"], "reason": None}
    if m["eliminated_at"] is not None:
        return {"state": "out", "at": m["eliminated_at"], "reason": m["elim_reason"]}
    return {"state": "in", "at": None, "reason": None}


@app.get("/api/v1/members")
def members(member=Depends(current_member), db: Database = Depends(get_db)):
    now = now_ms()
    gid = member["group_id"]
    game.check_round(db, gid, now)
    g = db.group(gid)
    out = []
    for m in sorted(db.group_members(gid), key=lambda r: r["name"].casefold()):
        phase = game.night_phase(db, g, m, now)
        out.append({
            "id": m["id"],
            "name": m["name"],
            "is_me": m["id"] == member["id"],
            "is_owner": m["id"] == g["owner_id"],
            "tz": _tz_info(m, g),
            "phase": phase,
            "status": _status_for(member, m, g, now, phase),
            "game": _game_state(m, g),
        })
    # В игре — сверху, вылетевшие — ниже, по времени вылета.
    order = {"winner": 0, "in": 1, "out": 2}
    out.sort(key=lambda x: (order[x["game"]["state"]], -(x["game"]["at"] or 0) if x["game"]["state"] == "out" else 0))
    me = db.member(member["id"])
    return {"server_time": now, "members": out, "round": g["round_no"],
            "round_info": game.round_info(db, g, now, viewer=me),
            "night": game.describe_night(db, gid, now, member=me)}


@app.get("/api/v1/members/{member_id}/timeline")
def timeline(member_id: int, start: int, end: int, member=Depends(current_member), db: Database = Depends(get_db)):
    if end <= start or end - start > 3 * 24 * 3_600_000:
        raise HTTPException(400, "Неверный интервал")
    target = db.member(member_id)
    if target is None or target["group_id"] != member["group_id"]:
        raise HTTPException(404, "Нет такого участника в твоей группе")
    now = now_ms()
    g = db.group(member["group_id"])

    def clip(s: int, e: int) -> list[int]:
        return [max(s, start), min(e, end)]

    # Пока у друга идёт отбой, его ночь скрыта целиком — даже то, включал ли он экран.
    # Откроется после подъёма (или отмены отбоя).
    cut = None
    if target["id"] != member["id"] and _tracked(target, g):
        cur = game.current_night(db, g, now, game.tz(target, g))
        if cur is not None:
            cut = cur[1].bed_ms

    def visible(rows) -> list[list[int]]:
        out = []
        for s, e in rows:
            s, e = clip(s, e)
            if cut is not None:
                if s >= cut:
                    continue
                e = min(e, cut)
            out.append([s, e])
        return out

    screens = visible(db.intervals_between("screen_intervals", member_id, start, end))
    used = visible(db.intervals_between("used_intervals", member_id, start, end))
    sessions = []
    totals: dict[str, int] = defaultdict(int)
    # Чужие приложения видно только ночью (и за 40 минут до отбоя); свои — всегда.
    windows = None if target["id"] == member["id"] else game.reveal_windows(db, g, target, start, end, now)
    for r in db.sessions_between(member_id, start, end):
        s, e = clip(r["start_ms"], r["end_ms"])
        pieces = [(s, e)] if windows is None else [(max(s, a), min(e, b)) for a, b in windows if min(e, b) > max(s, a)]
        for ps, pe in pieces:
            sessions.append({"pkg": r["pkg"], "name": r["name"], "start": ps, "end": pe})
            totals[r["name"]] += pe - ps
    # Ночи, попадающие в этот интервал, — чтобы подсветить их на полосе.
    versions = db.settings_versions(g["id"])
    offset = game.tz(target, g)
    nights = [[n.bed_ms, n.wake_ms] for s, n in rules.nights_between(start, end, versions, offset)
              if rules.night_active(n, s, offset)]
    return {
        "member": {"id": target["id"], "name": target["name"],
                   "status": _status_for(member, target, g, now, game.night_phase(db, g, target, now)),
                   "tz": _tz_info(target, g), "phase": game.night_phase(db, g, target, now),
                   "game": _game_state(target, g)},
        "server_time": now,
        "screens": screens,
        "used": used,
        "nights": nights,
        "sessions": sessions,
        "totals": sorted(([k, v] for k, v in totals.items()), key=lambda kv: -kv[1]),
    }
