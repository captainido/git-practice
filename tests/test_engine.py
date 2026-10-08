"""בדיקת המנוע מול טפסים מדומים (שרת מקומי, Chromium ללא ממשק).
הרצה: python tests/test_engine.py"""

import asyncio
import datetime as dt
import http.server
import sys
import tempfile
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))
import engine  # noqa: E402

RESULT_JS = """
<script>
function check(e){ if(e) e.preventDefault();
  const p = document.querySelector('[name=%(p)s]').value, d = document.querySelector('[name=%(d)s]').value;
  setTimeout(() => { document.getElementById('out').innerText =
    (p === '1234567' && /2026/.test(d)) ? 'הרכב היה מבוטח בתאריך האירוע. פוליסה מספר 555'
                                         : 'לא נמצאה פוליסה לרכב זה'; }, 400); }
</script>"""

PAGES = {
    # טקסט רגיל, label, כפתור submit
    "a": """<h1>בדיקה אם הרכב מבוטח</h1><form onsubmit="check(event)">
            <label for=c>מספר רישוי</label><input id=c name=car>
            <label for=t>תאריך אירוע</label><input id=t name=evdate placeholder="dd/mm/yyyy">
            <button type=submit>בדיקה</button></form><div id=out></div>""" + RESULT_JS % {"p": "car", "d": "evdate"},
    # שדה type=date, טלפון שאסור לגעת בו, כפתור שאינו submit
    "b": """<div><span>טלפון</span><input name=phone></div>
            <div><span>מס' רכב</span><input name=lic></div>
            <div><span>מועד התאונה</span><input type=date name=d></div>
            <a role=button onclick="check()">חפש</a><div id=out></div>""" + RESULT_JS % {"p": "lic", "d": "d"},
    # טופס בתוך iframe + שדה תאריך לקריאה בלבד
    "c": """<p>מבוטח? בדקו כאן</p><iframe src="/inner" width=600 height=300></iframe>""",
    "inner": """<form onsubmit="check(event)"><input placeholder="מספר רכב" name=plate>
            <input readonly placeholder="תאריך" name=dt><input type=submit value="שלח"></form>
            <div id=out></div>""" + RESULT_JS % {"p": "plate", "d": "dt"},
    # קאפצ'ה גלויה -> needs_user
    "d": """<form><label>מספר רישוי<input name=x></label><label>תאריך<input name=y></label>
            <img src="/captcha.png" width=120 height=40 alt=""><button>בדוק</button></form>""",
}


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        key = self.path.strip("/")
        body = PAGES.get(key, "")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(f"<html dir=rtl><body>{body}</body></html>".encode())

    def log_message(self, *a):
        pass


def test_classify():
    site = {}
    assert engine.classify("הרכב אינו מבוטח בחברתנו", site)[0] == "not_found"
    assert engine.classify("לא נמצאה פוליסה בתוקף", site)[0] == "not_found"
    assert engine.classify("הרכב מבוטח. פוליסה מספר 12", site)[0] == "found"
    assert engine.classify("מספר רישוי לא תקין", site)[0] == "error"
    assert engine.classify("", site)[0] is None


def main():
    test_classify()
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{srv.server_port}"
    sites = [{"id": k, "company": k, "url": f"{base}/{k}"} for k in ("a", "b", "c", "d")]
    final = {}

    def upd(u):
        final[u["id"]] = u
        print(f"  {u['id']}: {u['status_he']:<12} {u['message']}")

    async def go():
        tmp = Path(tempfile.mkdtemp())
        ch = engine.Checker(sites, tmp / "res", tmp / "prof", upd, headless=True, timeout=8, user_timeout=3)
        task = asyncio.create_task(ch.run("1234567", dt.date(2026, 9, 1)))
        while len([u for u in final.values() if u["status"] not in ("pending", "running", "needs_user")]) < 4:
            await asyncio.sleep(0.5)
        ch._stop.set()
        await task
        assert (tmp / "res" / "סיכום.csv").exists()

    asyncio.run(go())
    expected = {"a": "found", "b": "found", "c": "found", "d": "unknown"}
    bad = {k: final[k]["status"] for k in expected if final[k]["status"] != expected[k]}
    print("OK" if not bad else f"FAILED: {bad}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
