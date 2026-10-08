"""אנקור – בדיקת כיסוי ביטוחי צד ג'. נקודת הכניסה של התוכנה (ממשק pywebview)."""

import asyncio
import base64
import datetime as dt
import json
import os
import subprocess
import sys
import threading
from pathlib import Path

import webview

from engine import Checker

FROZEN = getattr(sys, "frozen", False)
# קבצים מובנים (בתוך ה-EXE) מול תיקייה ניתנת לכתיבה ליד ה-EXE
RES_DIR = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
APP_DIR = Path(sys.executable).parent if FROZEN else Path(__file__).resolve().parent.parent


def resource(*parts):
    return RES_DIR.joinpath(*parts)


def load_sites():
    local = APP_DIR / "sites.json"
    path = local if local.exists() else resource("app", "sites.json")
    return json.loads(path.read_text(encoding="utf-8"))["sites"]


def open_path(path):
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606 - פתיחת קובץ/תיקייה בסייר
    else:
        subprocess.Popen(["xdg-open", str(path)])


class Api:
    def __init__(self):
        self._window = None
        self._checker = None
        self._thread = None
        self._results_root = APP_DIR / "תוצאות"

    def sites(self):
        return [{"id": s["id"], "company": s["company"]} for s in load_sites() if s.get("enabled", True)]

    def start(self, plate, date_iso):
        self.stop()
        if self._thread:
            self._thread.join(timeout=20)
        date = dt.date.fromisoformat(date_iso)
        stamp = dt.datetime.now().strftime("%Y-%m-%d_%H-%M")
        out = self._results_root / f"{plate}_{date.strftime('%d-%m-%Y')}_{stamp}"
        self._checker = Checker(load_sites(), out, APP_DIR / "browser-profile", self._push)
        self._thread = threading.Thread(target=self._run, args=(self._checker, plate, date), daemon=True)
        self._thread.start()
        return {"ok": True}

    def _run(self, checker, plate, date):
        try:
            asyncio.run(checker.run(plate, date))
        except Exception as e:  # noqa: BLE001 - מוצג לעובדת
            self._js("onFatal", str(e))

    def _push(self, update):
        self._js("onUpdate", update)

    def _js(self, fn, arg):
        if self._window:
            self._window.evaluate_js(f"window.{fn} && window.{fn}({json.dumps(arg, ensure_ascii=False)})")

    def stop(self):
        if self._checker:
            self._checker.stop()

    def show(self, site_id):
        if self._checker:
            self._checker.show(site_id)

    def open_file(self, path):
        if path and Path(path).exists():
            open_path(path)

    def open_results(self):
        self._results_root.mkdir(parents=True, exist_ok=True)
        open_path(self._results_root)


def selftest():
    """בדיקה עצמית לבנייה: מוודא שמנוע Playwright ארוז ושדפדפן המערכת עולה."""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as pw:
        for channel in ("msedge", "chrome"):
            try:
                browser = pw.chromium.launch(channel=channel, headless=True)
            except Exception:  # noqa: BLE001
                continue
            page = browser.new_page()
            page.set_content("<p>ok</p>")
            assert page.inner_text("p") == "ok"
            browser.close()
            (APP_DIR / "selftest.txt").write_text(f"OK {channel}", encoding="utf-8")
            return 0
    (APP_DIR / "selftest.txt").write_text("FAIL", encoding="utf-8")
    return 1


def main():
    if "--selftest" in sys.argv:
        sys.exit(selftest())
    logo = base64.b64encode(resource("assets", "logo.png").read_bytes()).decode()
    html = resource("app", "ui", "index.html").read_text(encoding="utf-8")
    html = html.replace("__LOGO__", f"data:image/png;base64,{logo}")
    api = Api()
    api._window = webview.create_window("אנקור – בדיקת כיסוי צד ג'", html=html, js_api=api,
                                       width=1240, height=860, min_size=(900, 640))
    api._window.events.closing += api.stop
    webview.start(icon=str(resource("assets", "logo.png")))


if __name__ == "__main__":
    main()
