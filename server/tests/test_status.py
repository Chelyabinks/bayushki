from app.status import STALE_SCREEN_OFF_MS, STALE_SCREEN_ON_MS, compute_status

NOW = 1_800_000_000_000


def m(**kw):
    base = dict(last_upload_at=NOW, usage_permission=1, screen_on=0, screen_since=NOW - 1000,
                current_name=None, current_since=None)
    base.update(kw)
    return base


def test_active():
    s = compute_status(m(screen_on=1, current_name="TikTok", current_since=NOW - 12 * 60_000), NOW)
    assert s.state == "active" and s.app_name == "TikTok" and s.since_ms == NOW - 12 * 60_000


def test_screen_on_but_silent():
    s = compute_status(m(screen_on=1, last_upload_at=NOW - STALE_SCREEN_ON_MS - 1), NOW)
    assert (s.state, s.reason) == ("stale", "silent")


def test_idle_night_is_ok():
    s = compute_status(m(screen_on=0, last_upload_at=NOW - 8 * 3_600_000), NOW)
    assert s.state == "idle"


def test_idle_too_long():
    s = compute_status(m(screen_on=0, last_upload_at=NOW - STALE_SCREEN_OFF_MS - 1), NOW)
    assert s.state == "stale"


def test_never_uploaded():
    assert compute_status(m(last_upload_at=None), NOW).reason == "no_data"
