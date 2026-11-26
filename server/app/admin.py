"""Админские команды. Примеры:

    uv run python -m app.admin create-group "Баюшки"     # обычно группы создают прямо в приложении
    uv run python -m app.admin groups
    uv run python -m app.admin members
    uv run python -m app.admin allow-rejoin 3     # участник 3 может заново войти под своим именем
    uv run python -m app.admin kick 3             # удалить участника из группы
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime

from . import rules
from .main import get_db


def _fmt(ms: int | None) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%d.%m %H:%M") if ms else "—"


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(prog="python -m app.admin")
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("create-group", help="создать группу и получить инвайт-код")
    g.add_argument("name")
    sub.add_parser("groups", help="список групп и инвайт-кодов")
    sub.add_parser("members", help="список участников")
    for name in ("allow-rejoin", "kick"):
        p = sub.add_parser(name)
        p.add_argument("member_id", type=int)
    args = ap.parse_args(argv)
    db = get_db()

    if args.cmd == "create-group":
        grp = db.create_group(args.name, rules.validate_settings({}, {}))
        print(f"Группа «{grp['name']}» создана. Код: {grp['invite_code']}. Первый вошедший станет организатором.")
    elif args.cmd == "groups":
        for grp in db.list_groups():
            print(f"#{grp['id']}  {grp['name']}  код: {grp['invite_code']}  раунд {grp['round_no']}  "
                  f"организатор: #{grp['owner_id']}")
    elif args.cmd == "members":
        for m in db.all_members():
            state = "активен" if m["active"] else "удалён"
            if m["eliminated_at"]:
                state += f", вылетел {_fmt(m['eliminated_at'])}"
            print(f"#{m['id']}  {m['name']}  [{m['group_name']}]  {state}  "
                  f"последние данные: {_fmt(m['last_upload_at'])}  версия: {m['app_version'] or '—'}")
    elif args.cmd == "allow-rejoin":
        ok = db.set_member_flag(args.member_id, "rejoin_allowed", 1)
        print("Готово: при следующем входе с этим именем токен перепривяжется." if ok else "Нет такого участника")
    elif args.cmd == "kick":
        ok = db.set_member_flag(args.member_id, "active", 0)
        print("Участник удалён, его токен больше не работает." if ok else "Нет такого участника")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
