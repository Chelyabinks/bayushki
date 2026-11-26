"""Хранилище на SQLite. Одно соединение на поток, всё синхронно и просто."""
from __future__ import annotations

import hashlib
import json
import secrets
import sqlite3
import threading
import time
from pathlib import Path

SCHEMA_VERSION = 3

SCHEMA = """
CREATE TABLE IF NOT EXISTS groups(
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    invite_code TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    owner_id INTEGER,
    tz_offset_min INTEGER NOT NULL DEFAULT 180,
    round_no INTEGER NOT NULL DEFAULT 1,
    round_started_at INTEGER NOT NULL,
    finished_at INTEGER,
    finish_kind TEXT
);
CREATE TABLE IF NOT EXISTS group_settings(
    id INTEGER PRIMARY KEY,
    group_id INTEGER NOT NULL REFERENCES groups(id),
    effective_from INTEGER NOT NULL,
    data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_group_settings ON group_settings(group_id, effective_from);
CREATE TABLE IF NOT EXISTS members(
    id INTEGER PRIMARY KEY,
    group_id INTEGER NOT NULL REFERENCES groups(id),
    name TEXT NOT NULL,
    token_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    active INTEGER NOT NULL DEFAULT 1,
    rejoin_allowed INTEGER NOT NULL DEFAULT 0,
    playing_since INTEGER NOT NULL,
    eliminated_at INTEGER,
    elim_reason TEXT,
    won INTEGER NOT NULL DEFAULT 0,
    tz_offset_min INTEGER,
    tz_id TEXT,
    last_upload_at INTEGER,
    screen_on INTEGER,
    screen_since INTEGER,
    current_pkg TEXT,
    current_name TEXT,
    current_since INTEGER,
    usage_permission INTEGER,
    health TEXT,
    app_version INTEGER
);
CREATE TABLE IF NOT EXISTS apps(
    pkg TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    android_cat INTEGER
);
CREATE TABLE IF NOT EXISTS app_sessions(
    id INTEGER PRIMARY KEY,
    member_id INTEGER NOT NULL REFERENCES members(id),
    pkg TEXT NOT NULL,
    name TEXT NOT NULL,
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_app_sessions ON app_sessions(member_id, start_ms);
CREATE TABLE IF NOT EXISTS screen_intervals(
    id INTEGER PRIMARY KEY,
    member_id INTEGER NOT NULL REFERENCES members(id),
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_screen_intervals ON screen_intervals(member_id, start_ms);
CREATE TABLE IF NOT EXISTS used_intervals(
    id INTEGER PRIMARY KEY,
    member_id INTEGER NOT NULL REFERENCES members(id),
    start_ms INTEGER NOT NULL,
    end_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_used_intervals ON used_intervals(member_id, start_ms);
CREATE TABLE IF NOT EXISTS events(
    id INTEGER PRIMARY KEY,
    group_id INTEGER NOT NULL REFERENCES groups(id),
    member_id INTEGER,
    kind TEXT NOT NULL,
    at_ms INTEGER NOT NULL,
    text TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_events ON events(group_id, id);
CREATE TABLE IF NOT EXISTS night_cancels(
    group_id INTEGER NOT NULL REFERENCES groups(id),
    wake_date TEXT NOT NULL,
    at_ms INTEGER NOT NULL,
    PRIMARY KEY (group_id, wake_date)
);
"""

# Буквы и цифры без похожих (0/O, 1/I/L), чтобы код было легко продиктовать.
INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
MERGE_GAP_MS = 1500
INTERVAL_TABLES = ("screen_intervals", "used_intervals")


def now_ms() -> int:
    return int(time.time() * 1000)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_invite_code() -> str:
    return "".join(secrets.choice(INVITE_ALPHABET) for _ in range(8))


class NameTaken(Exception):
    pass


class Database:
    def __init__(self, path: str | Path):
        self.path = str(path)
        self._local = threading.local()
        self._write_lock = threading.Lock()
        with self.conn() as c:
            version = c.execute("PRAGMA user_version").fetchone()[0]
            if version < SCHEMA_VERSION and c.execute(
                    "SELECT 1 FROM sqlite_master WHERE name='groups'").fetchone():
                # Ранние схемы были только для проверки — начинаем с чистой базы.
                for t in ("night_cancels", "alive_intervals", "events", "used_intervals", "screen_intervals", "app_sessions", "apps",
                          "members", "group_settings", "groups"):
                    c.execute(f"DROP TABLE IF EXISTS {t}")
            c.executescript(SCHEMA)
            # Колонки, добавленные позже, — без потери данных.
            cols = {r[1] for r in c.execute("PRAGMA table_info(members)")}
            for col, typ in (("tz_offset_min", "INTEGER"), ("tz_id", "TEXT")):
                if col not in cols:
                    c.execute(f"ALTER TABLE members ADD COLUMN {col} {typ}")
            c.execute(f"PRAGMA user_version={SCHEMA_VERSION}")

    def conn(self) -> sqlite3.Connection:
        c = getattr(self._local, "conn", None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=30)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA journal_mode=WAL")
            c.execute("PRAGMA foreign_keys=ON")
            self._local.conn = c
        return c

    # ---------- группы ----------

    def create_group(self, name: str, settings: dict, tz_offset_min: int = 180) -> sqlite3.Row:
        with self._write_lock, self.conn() as c:
            while True:
                code = new_invite_code()
                if not c.execute("SELECT 1 FROM groups WHERE invite_code=?", (code,)).fetchone():
                    break
            now = now_ms()
            cur = c.execute(
                "INSERT INTO groups(name, invite_code, created_at, tz_offset_min, round_started_at) VALUES(?,?,?,?,?)",
                (name, code, now, tz_offset_min, now),
            )
            c.execute("INSERT INTO group_settings(group_id, effective_from, data) VALUES(?,?,?)",
                      (cur.lastrowid, 0, json.dumps(settings, ensure_ascii=False)))
            return c.execute("SELECT * FROM groups WHERE id=?", (cur.lastrowid,)).fetchone()

    def group(self, group_id: int) -> sqlite3.Row:
        return self.conn().execute("SELECT * FROM groups WHERE id=?", (group_id,)).fetchone()

    def group_by_invite(self, code: str) -> sqlite3.Row | None:
        return self.conn().execute(
            "SELECT * FROM groups WHERE invite_code=?", (code.strip().upper(),)
        ).fetchone()

    def list_groups(self) -> list[sqlite3.Row]:
        return self.conn().execute("SELECT * FROM groups ORDER BY id").fetchall()

    def update_group(self, group_id: int, **fields) -> None:
        allowed = {"name", "invite_code", "owner_id", "tz_offset_min", "round_no", "round_started_at"}
        assert set(fields) <= allowed
        if not fields:
            return
        sets = ", ".join(f"{k}=?" for k in fields)
        with self._write_lock, self.conn() as c:
            c.execute(f"UPDATE groups SET {sets} WHERE id=?", (*fields.values(), group_id))

    def regenerate_invite(self, group_id: int) -> str:
        while True:
            code = new_invite_code()
            if self.group_by_invite(code) is None:
                self.update_group(group_id, invite_code=code)
                return code

    def settings_versions(self, group_id: int) -> list[tuple[int, dict]]:
        rows = self.conn().execute(
            "SELECT effective_from, data FROM group_settings WHERE group_id=? ORDER BY effective_from, id",
            (group_id,)).fetchall()
        return [(r["effective_from"], json.loads(r["data"])) for r in rows]

    def add_settings(self, group_id: int, effective_from: int, settings: dict) -> None:
        with self._write_lock, self.conn() as c:
            # Ещё не вступившие в силу версии заменяем новой.
            c.execute("DELETE FROM group_settings WHERE group_id=? AND effective_from>=?", (group_id, effective_from))
            c.execute("INSERT INTO group_settings(group_id, effective_from, data) VALUES(?,?,?)",
                      (group_id, effective_from, json.dumps(settings, ensure_ascii=False)))

    def start_round(self, group_id: int) -> int:
        now = now_ms()
        with self._write_lock, self.conn() as c:
            g = c.execute("SELECT round_no FROM groups WHERE id=?", (group_id,)).fetchone()
            no = g["round_no"] + 1
            c.execute("UPDATE groups SET round_no=?, round_started_at=?, finished_at=NULL, finish_kind=NULL WHERE id=?",
                      (no, now, group_id))
            c.execute("UPDATE members SET eliminated_at=NULL, elim_reason=NULL, won=0, playing_since=? WHERE group_id=?",
                      (now, group_id))
            return no

    def finish_round(self, group_id: int, at_ms: int, kind: str, winner_ids: list[int]) -> bool:
        """Завершает раунд: kind = time | manual | all_out. False — уже был завершён."""
        with self._write_lock, self.conn() as c:
            cur = c.execute("UPDATE groups SET finished_at=?, finish_kind=? WHERE id=? AND finished_at IS NULL",
                            (at_ms, kind, group_id))
            if cur.rowcount == 0:
                return False
            c.executemany("UPDATE members SET won=1 WHERE id=?", [(i,) for i in winner_ids])
            return True

    # ---------- участники ----------

    def join(self, group_id: int, name: str) -> tuple[sqlite3.Row, str]:
        """Создаёт участника и возвращает (участник, токен). NameTaken — имя занято."""
        token = secrets.token_urlsafe(32)
        now = now_ms()
        with self._write_lock, self.conn() as c:
            # lower() в SQLite не знает кириллицу — сравниваем в Python.
            existing = next((m for m in c.execute(
                "SELECT * FROM members WHERE group_id=? AND active=1", (group_id,))
                if m["name"].casefold() == name.casefold()), None)
            if existing is not None:
                if not existing["rejoin_allowed"]:
                    raise NameTaken
                # Разрешили перепривязать имя (новый телефон / переустановка). Статус в игре сохраняется.
                c.execute("UPDATE members SET token_hash=?, rejoin_allowed=0 WHERE id=?",
                          (hash_token(token), existing["id"]))
                member_id = existing["id"]
            else:
                cur = c.execute(
                    "INSERT INTO members(group_id, name, token_hash, created_at, playing_since) VALUES(?,?,?,?,?)",
                    (group_id, name, hash_token(token), now, now),
                )
                member_id = cur.lastrowid
                c.execute("UPDATE groups SET owner_id=? WHERE id=? AND owner_id IS NULL", (member_id, group_id))
            return c.execute("SELECT * FROM members WHERE id=?", (member_id,)).fetchone(), token

    def member_by_token(self, token: str) -> sqlite3.Row | None:
        return self.conn().execute(
            "SELECT * FROM members WHERE token_hash=? AND active=1", (hash_token(token),)
        ).fetchone()

    def member(self, member_id: int) -> sqlite3.Row | None:
        return self.conn().execute(
            "SELECT * FROM members WHERE id=? AND active=1", (member_id,)
        ).fetchone()

    def group_members(self, group_id: int) -> list[sqlite3.Row]:
        return self.conn().execute(
            "SELECT * FROM members WHERE group_id=? AND active=1 ORDER BY id", (group_id,)
        ).fetchall()

    def all_members(self) -> list[sqlite3.Row]:
        return self.conn().execute(
            "SELECT m.*, g.name AS group_name FROM members m JOIN groups g ON g.id=m.group_id ORDER BY m.id"
        ).fetchall()

    def set_member_flag(self, member_id: int, column: str, value: int) -> bool:
        assert column in ("active", "rejoin_allowed")
        with self._write_lock, self.conn() as c:
            ok = c.execute(f"UPDATE members SET {column}=? WHERE id=?", (value, member_id)).rowcount > 0
            if column == "active" and value == 0:
                # Ушёл владелец — группа переходит к самому давнему участнику.
                g = c.execute("SELECT g.id, g.owner_id FROM groups g JOIN members m ON m.group_id=g.id "
                              "WHERE m.id=?", (member_id,)).fetchone()
                if g and g["owner_id"] == member_id:
                    nxt = c.execute("SELECT id FROM members WHERE group_id=? AND active=1 ORDER BY id LIMIT 1",
                                    (g["id"],)).fetchone()
                    c.execute("UPDATE groups SET owner_id=? WHERE id=?", (nxt["id"] if nxt else None, g["id"]))
            return ok

    def eliminate(self, member_id: int, at_ms: int, reason: str) -> bool:
        with self._write_lock, self.conn() as c:
            return c.execute("UPDATE members SET eliminated_at=?, elim_reason=? WHERE id=? AND eliminated_at IS NULL",
                             (at_ms, reason, member_id)).rowcount > 0

    # ---------- события (для утренних уведомлений) ----------

    def add_event(self, group_id: int, member_id: int | None, kind: str, at_ms: int, text: str) -> None:
        with self._write_lock, self.conn() as c:
            c.execute("INSERT INTO events(group_id, member_id, kind, at_ms, text) VALUES(?,?,?,?,?)",
                      (group_id, member_id, kind, at_ms, text))

    def events_after(self, group_id: int, after_id: int, limit: int = 50) -> list[sqlite3.Row]:
        return self.conn().execute("SELECT * FROM events WHERE group_id=? AND id>? ORDER BY id LIMIT ?",
                                   (group_id, after_id, limit)).fetchall()

    def last_event_id(self, group_id: int) -> int:
        r = self.conn().execute("SELECT MAX(id) FROM events WHERE group_id=?", (group_id,)).fetchone()
        return r[0] or 0

    # ---------- загрузка данных ----------

    def _merge(self, c: sqlite3.Connection, table: str, member_id: int, start: int, end: int,
               pkg: str | None = None, name: str | None = None) -> None:
        """Вставляет интервал, склеивая его с пересекающимися (тот же пакет)."""
        where = "member_id=? AND start_ms<=? AND end_ms>=?"
        params: list = [member_id, end + MERGE_GAP_MS, start - MERGE_GAP_MS]
        if pkg is not None:
            where += " AND pkg=?"
            params.append(pkg)
        rows = c.execute(f"SELECT id, start_ms, end_ms FROM {table} WHERE {where}", params).fetchall()
        for r in rows:
            start = min(start, r["start_ms"])
            end = max(end, r["end_ms"])
        if rows:
            c.executemany(f"DELETE FROM {table} WHERE id=?", [(r["id"],) for r in rows])
        if pkg is None:
            c.execute(f"INSERT INTO {table}(member_id, start_ms, end_ms) VALUES(?,?,?)",
                      (member_id, start, end))
        else:
            c.execute(f"INSERT INTO {table}(member_id, pkg, name, start_ms, end_ms) VALUES(?,?,?,?,?)",
                      (member_id, pkg, name or pkg, start, end))

    def store_upload(self, member_id: int, *, received_at: int, screens: list[tuple[int, int]],
                     used: list[tuple[int, int]], sessions: list[tuple[str, str, int, int]],
                     apps: dict[str, tuple[str, int | None]], state: dict) -> None:
        with self._write_lock, self.conn() as c:
            for pkg, (name, cat) in apps.items():
                c.execute("INSERT INTO apps(pkg, name, android_cat) VALUES(?,?,?) "
                          "ON CONFLICT(pkg) DO UPDATE SET name=excluded.name, "
                          "android_cat=COALESCE(excluded.android_cat, apps.android_cat)", (pkg, name, cat))
            for start, end in screens:
                self._merge(c, "screen_intervals", member_id, start, end)
            for start, end in used:
                self._merge(c, "used_intervals", member_id, start, end)
            for pkg, name, start, end in sessions:
                self._merge(c, "app_sessions", member_id, start, end, pkg, name)
            c.execute(
                """UPDATE members SET last_upload_at=?, screen_on=?, screen_since=?, current_pkg=?,
                   current_name=?, current_since=?, usage_permission=?, health=?, app_version=?
                   WHERE id=?""",
                (received_at, state["screen_on"], state["screen_since"], state["current_pkg"],
                 state["current_name"], state["current_since"], state["usage_permission"],
                 state["health"], state["app_version"], member_id),
            )

    def touch(self, member_id: int, received_at: int, app_version: int | None) -> None:
        """Выбывший участник: данные не храним, только отметка «приложение на связи»."""
        with self._write_lock, self.conn() as c:
            c.execute("UPDATE members SET last_upload_at=?, app_version=COALESCE(?, app_version), screen_on=0, "
                      "current_pkg=NULL, current_name=NULL WHERE id=?", (received_at, app_version, member_id))

    def set_member_tz(self, member_id: int, offset_min: int, tz_id: str | None) -> None:
        with self._write_lock, self.conn() as c:
            c.execute("UPDATE members SET tz_offset_min=?, tz_id=COALESCE(?, tz_id) WHERE id=?",
                      (offset_min, tz_id, member_id))

    # ---------- отмена отбоя ----------

    def cancel_night(self, group_id: int, wake_date: str, at_ms: int) -> None:
        with self._write_lock, self.conn() as c:
            c.execute("INSERT OR REPLACE INTO night_cancels(group_id, wake_date, at_ms) VALUES(?,?,?)",
                      (group_id, wake_date, at_ms))

    def uncancel_night(self, group_id: int, wake_date: str) -> None:
        with self._write_lock, self.conn() as c:
            c.execute("DELETE FROM night_cancels WHERE group_id=? AND wake_date=?", (group_id, wake_date))

    def night_cancels(self, group_id: int) -> dict:
        """{дата подъёма: момент отмены}"""
        from datetime import date
        rows = self.conn().execute("SELECT wake_date, at_ms FROM night_cancels WHERE group_id=?", (group_id,))
        return {date.fromisoformat(r["wake_date"]): r["at_ms"] for r in rows}

    # ---------- чтение ----------

    def intervals_between(self, table: str, member_id: int, start: int, end: int) -> list[tuple[int, int]]:
        assert table in INTERVAL_TABLES
        rows = self.conn().execute(
            f"SELECT start_ms, end_ms FROM {table} WHERE member_id=? AND end_ms>? AND start_ms<? ORDER BY start_ms",
            (member_id, start, end)).fetchall()
        return [(r["start_ms"], r["end_ms"]) for r in rows]

    def sessions_between(self, member_id: int, start: int, end: int) -> list[sqlite3.Row]:
        return self.conn().execute(
            "SELECT pkg, name, start_ms, end_ms FROM app_sessions WHERE member_id=? AND end_ms>? AND start_ms<? "
            "ORDER BY start_ms", (member_id, start, end)).fetchall()

    def app_catalog(self, pkgs: list[str] | None = None) -> dict[str, sqlite3.Row]:
        if pkgs is None:
            rows = self.conn().execute("SELECT * FROM apps").fetchall()
        else:
            rows = [r for p in set(pkgs) if (r := self.conn().execute("SELECT * FROM apps WHERE pkg=?", (p,)).fetchone())]
        return {r["pkg"]: r for r in rows}

    def group_used_pkgs(self, group_id: int, since_ms: int) -> list[str]:
        rows = self.conn().execute(
            "SELECT DISTINCT s.pkg FROM app_sessions s JOIN members m ON m.id=s.member_id "
            "WHERE m.group_id=? AND s.end_ms>?", (group_id, since_ms)).fetchall()
        return [r["pkg"] for r in rows]

    def purge_older_than(self, cutoff_ms: int) -> None:
        with self._write_lock, self.conn() as c:
            c.execute("DELETE FROM app_sessions WHERE end_ms<?", (cutoff_ms,))
            for t in INTERVAL_TABLES:
                c.execute(f"DELETE FROM {t} WHERE end_ms<?", (cutoff_ms,))
            c.execute("DELETE FROM events WHERE at_ms<?", (cutoff_ms,))
