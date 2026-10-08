"""מנוע הבדיקה: פותח את אתרי חברות הביטוח בדפדפן, ממלא מספר רישוי ותאריך אירוע,
ומסווג את התשובה (מבוטח / לא מבוטח / דורש פעולה)."""

import asyncio
import csv
import datetime as dt
import re
from pathlib import Path

from playwright.async_api import async_playwright

# סטטוסים שמוצגים בממשק
PENDING, RUNNING, FOUND, NOT_FOUND, NEEDS_USER, UNKNOWN, ERROR, SKIPPED = (
    "pending", "running", "found", "not_found", "needs_user", "unknown", "error", "skipped")

STATUS_HE = {
    PENDING: "ממתין", RUNNING: "בודק...", FOUND: "מבוטח", NOT_FOUND: "לא מבוטח",
    NEEDS_USER: "דורש פעולה", UNKNOWN: "לא זוהתה תשובה", ERROR: "שגיאה", SKIPPED: "דולג",
}

NEGATIVE = (r"לא\s+נמצא|לא\s+אותר|אינו\s+מבוטח|אינה\s+מבוטחת|לא\s+היה\s+מבוטח|לא\s+מבוטח|"
            r"אין\s+כיסוי|אין\s+פוליסה|לא\s+קיים|לא\s+קיימת|לא\s+בוטח|no\s+policy|not\s+found|not\s+insured")
POSITIVE = (r"מבוטח|נמצא\s+כיסוי|נמצאה\s+פוליסה|קיים\s+כיסוי|קיימת\s+פוליסה|יש\s+כיסוי|בתוקף|"
            r"פוליסה\s+(?:מספר|מס['׳]?)|\binsured\b|coverage\s+found")
INPUT_ERROR = (r"שגיאה|לא\s+תקין|שגוי|נא\s+למלא|שדה\s+חובה|יש\s+להזין|invalid|required")
LOGIN_HINT = r"קוד\s+אימות|קוד\s+חד[\s-]?פעמי|סיסמה|SMS|הזדהות|כניסה\s+לאזור"

# JS שמזהה את שדות מספר הרישוי והתאריך בכל מסגרת ומסמן אותם ב-data-ankor
DETECT_JS = r"""
() => {
  const PLATE = /רישוי|מספר\s*ה?רכב|מס['׳"]?\s*רכב|לוחית|license|licence|plate|car.?(num|no|id)|vehicle|lpn|carnumber/i;
  const DATE  = /תאריך|מועד|date|אירוע|תאונה|event|accident/i;
  const NOT_PLATE = /ת\.?ז|תעודת|זהות|טלפון|נייד|דוא"?ל|mail|phone|id.?num|שם|name|פוליסה|קוד|code|captcha|search/i;
  const vis = el => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
  const own = el => [el.name, el.id, el.placeholder, el.getAttribute('aria-label'), el.title,
    el.getAttribute('formcontrolname'), el.getAttribute('ng-model'), el.getAttribute('data-name')].join(' ');
  const ctx = el => {
    let t = '';
    if (el.id) { const l = document.querySelector('label[for="' + CSS.escape(el.id) + '"]'); if (l) t += ' ' + l.innerText; }
    const pl = el.closest('label'); if (pl) t += ' ' + pl.innerText;
    const lb = el.getAttribute('aria-labelledby');
    if (lb) lb.split(/\s+/).forEach(i => { const e = document.getElementById(i); if (e) t += ' ' + e.innerText; });
    let p = el.parentElement;
    for (let i = 0; i < 3 && p; i++, p = p.parentElement) {
      const tx = (p.innerText || '').trim(); if (tx.length < 90) t += ' ' + tx; else break;
    }
    return t;
  };
  document.querySelectorAll('[data-ankor]').forEach(e => e.removeAttribute('data-ankor'));
  const inputs = [...document.querySelectorAll(
    'input:not([type=hidden]):not([type=checkbox]):not([type=radio]):not([type=submit]):not([type=button]):not([type=image]):not([type=file])'
  )].filter(vis).filter(e => !e.disabled);
  let best = {plate: [null, 0], date: [null, 0]};
  for (const el of inputs) {
    const o = own(el), c = ctx(el), all = o + ' ' + c;
    let d = 0, p = 0;
    if (el.type === 'date') d += 10;
    if (DATE.test(o)) d += 6; else if (DATE.test(c)) d += 3;
    if (/dd|\/|תאריך/.test(el.placeholder || '')) d += 2;
    if (PLATE.test(o)) p += 6; else if (PLATE.test(c)) p += 3;
    if (NOT_PLATE.test(o)) p -= 5;
    if (el.type === 'date' || DATE.test(o)) p -= 6;
    if (el.type === 'password' || el.type === 'email') { p -= 10; d -= 10; }
    if (p > best.plate[1]) best.plate = [el, p];
    if (d > best.date[1]) best.date = [el, d];
  }
  if (best.plate[0] && best.plate[0] === best.date[0]) {
    if (best.plate[1] >= best.date[1]) best.date = [null, 0]; else best.plate = [null, 0];
  }
  if (best.plate[0]) best.plate[0].setAttribute('data-ankor', 'plate');
  if (best.date[0]) best.date[0].setAttribute('data-ankor', 'date');
  return {plate: best.plate[1], date: best.date[1], dateType: best.date[0] ? best.date[0].type : null,
          inputs: inputs.length, password: inputs.some(e => e.type === 'password')};
}
"""

CAPTCHA_JS = r"""
() => {
  const vis = el => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
    return r.width > 20 && r.height > 20 && s.visibility !== 'hidden' && s.display !== 'none'; };
  for (const f of document.querySelectorAll('iframe')) {
    const s = f.src || '';
    if (/recaptcha\/(api2|enterprise)\/(anchor|bframe)|hcaptcha|turnstile|challenges\.cloudflare/i.test(s)
        && !/size=invisible/.test(s) && vis(f)) return true;
  }
  for (const el of document.querySelectorAll('img[src*=captcha i], input[name*=captcha i], input[id*=captcha i], .cf-turnstile, .h-captcha'))
    if (vis(el)) return true;
  return false;
}
"""

SUBMIT_JS = r"""
(rx) => {
  const re = new RegExp(rx, 'i');
  const vis = el => { const r = el.getBoundingClientRect(); const s = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; };
  const anchor = document.querySelector('[data-ankor=plate]') || document.querySelector('[data-ankor=date]');
  const form = anchor && anchor.closest('form');
  const pool = [...(form || document).querySelectorAll('button, input[type=submit], input[type=button], a[role=button], [role=button], a.btn, a.button')]
    .filter(vis).filter(e => !e.disabled);
  document.querySelectorAll('[data-ankor=submit]').forEach(e => e.removeAttribute('data-ankor'));
  const label = e => (e.innerText || e.value || e.getAttribute('aria-label') || '').trim();
  let pick = pool.find(e => e.type === 'submit' && re.test(label(e))) || pool.find(e => re.test(label(e)))
          || (form ? pool.find(e => e.type === 'submit') : null);
  if (!pick && anchor) {
    // הכפתור הקרוב ביותר לשדה
    const ar = anchor.getBoundingClientRect();
    let bestD = 1e9;
    for (const e of pool) { if (!re.test(label(e))) continue;
      const r = e.getBoundingClientRect(); const dd = Math.hypot(r.x - ar.x, r.y - ar.y);
      if (dd < bestD) { bestD = dd; pick = e; } }
  }
  if (pick) { pick.setAttribute('data-ankor', 'submit'); return label(pick) || 'submit'; }
  return null;
}
"""

SUBMIT_WORDS = r"בדיק|בדוק|שלח|חפש|חיפוש|המשך|הצג|אישור|submit|check|search|continue|send"

COOKIE_JS = r"""
() => {
  const re = /^(אישור|מאשר|אני מסכים|מסכים|הבנתי|קבל|סגור|accept|agree|ok|got it)/i;
  for (const b of document.querySelectorAll('button, a, [role=button]')) {
    const box = b.closest('[class*=cookie i], [id*=cookie i], [class*=consent i], [id*=consent i], [aria-label*=cookie i]');
    if (box && re.test((b.innerText || '').trim())) { b.click(); return true; }
  }
  return false;
}
"""


def format_date(d: dt.date, fmt: str) -> str:
    return (fmt.replace("dd", f"{d.day:02d}").replace("mm", f"{d.month:02d}")
               .replace("yyyy", f"{d.year:04d}"))


def classify(text: str, site: dict):
    neg = NEGATIVE + ("|" + site["negative"] if site.get("negative") else "")
    pos = POSITIVE + ("|" + site["positive"] if site.get("positive") else "")
    for status, rx in ((NOT_FOUND, neg), (FOUND, pos), (ERROR, INPUT_ERROR)):
        m = re.search(rx, text, re.I)
        if m:
            return status, snippet(text, m.start())
    return None, ""


def snippet(text: str, pos: int, width: int = 140) -> str:
    s = text[max(0, pos - width // 2): pos + width]
    return re.sub(r"\s+", " ", s).strip()


class Target:
    """המסגרת שבה נמצא הטופס והסלקטורים של השדות בה."""

    def __init__(self, frame, plate_sel, date_sel, date_type):
        self.frame, self.plate_sel, self.date_sel, self.date_type = frame, plate_sel, date_sel, date_type

    def locator(self, sel):
        return self.frame.locator(sel)

    async def evaluate(self, js, arg=None):
        return await self.frame.evaluate(js, arg)


class Checker:
    def __init__(self, sites, results_dir: Path, profile_dir: Path, on_update,
                 headless=False, concurrency=6, timeout=90, user_timeout=300):
        self.sites = [s for s in sites if s.get("enabled", True)]
        self.results_dir = results_dir
        self.profile_dir = profile_dir
        self.on_update = on_update
        self.headless = headless
        self.concurrency = concurrency
        self.timeout = timeout
        self.user_timeout = user_timeout
        self.pages = {}
        self.results = {}
        self.ctx = None
        self.loop = None
        self._stop = asyncio.Event()

    def update(self, site, status, message="", detail=""):
        self.results[site["id"]] = {"status": status, "message": message, "detail": detail}
        self.on_update({"id": site["id"], "company": site["company"], "status": status,
                        "status_he": STATUS_HE[status], "message": message, "detail": detail})

    async def _launch(self, pw):
        self.profile_dir.mkdir(parents=True, exist_ok=True)
        common = dict(user_data_dir=str(self.profile_dir), headless=self.headless, locale="he-IL",
                      timezone_id="Asia/Jerusalem", viewport=None, args=["--start-maximized"])
        errors = []
        for channel in ("msedge", "chrome", None):
            try:
                kw = dict(common, channel=channel) if channel else common
                return await pw.chromium.launch_persistent_context(**kw)
            except Exception as e:  # noqa: BLE001 - ננסה את הדפדפן הבא
                errors.append(f"{channel}: {e}")
        raise RuntimeError("לא נמצא דפדפן Microsoft Edge או Google Chrome במחשב.\n" + "\n".join(errors))

    async def run(self, plate: str, date: dt.date):
        self.loop = asyncio.get_running_loop()
        self.results_dir.mkdir(parents=True, exist_ok=True)
        for s in self.sites:
            self.update(s, PENDING)
        async with async_playwright() as pw:
            self.ctx = await self._launch(pw)
            first = self.ctx.pages[0] if self.ctx.pages else None
            sem = asyncio.Semaphore(self.concurrency)

            async def one(site):
                async with sem:
                    if self._stop.is_set():
                        return self.update(site, SKIPPED, "הבדיקה הופסקה")
                    try:
                        await asyncio.wait_for(self.check_site(site, plate, date),
                                               timeout=self.user_timeout + self.timeout)
                    except asyncio.TimeoutError:
                        self.update(site, UNKNOWN, "תם הזמן – בדקו ידנית בלשונית האתר")
                    except Exception as e:  # noqa: BLE001
                        if self._stop.is_set():
                            self.update(site, SKIPPED, "הבדיקה הופסקה")
                        else:
                            self.update(site, ERROR, "האתר לא נטען או השתנה", str(e)[:300])

            await asyncio.gather(*(one(s) for s in self.sites))
            self.write_summary(plate, date)
            if first and len(self.ctx.pages) > 1:
                try:
                    await first.close()
                except Exception:  # noqa: BLE001
                    pass
            # הדפדפן נשאר פתוח עד שהעובדת סוגרת אותו / מתחילה בדיקה חדשה
            while not self._stop.is_set() and self.ctx.pages:
                await asyncio.sleep(1)
            try:
                await self.ctx.close()
            except Exception:  # noqa: BLE001
                pass

    def stop(self):
        if self.loop:
            self.loop.call_soon_threadsafe(self._stop.set)
            if self.ctx:
                asyncio.run_coroutine_threadsafe(self.ctx.close(), self.loop)

    def show(self, site_id):
        page = self.pages.get(site_id)
        if page and self.loop:
            asyncio.run_coroutine_threadsafe(page.bring_to_front(), self.loop)

    # ---------- בדיקת אתר בודד ----------
    async def frames_eval(self, page, js, arg=None):
        out = []
        for f in page.frames:
            try:
                out.append((f, await (f.evaluate(js, arg) if arg is not None else f.evaluate(js))))
            except Exception:  # noqa: BLE001 - מסגרת שנסגרה / חסומה
                pass
        return out

    async def page_text(self, page):
        parts = await self.frames_eval(page, "() => document.body ? document.body.innerText : ''")
        return "\n".join(t for _, t in parts if t)

    async def has_captcha(self, page):
        return any(r for _, r in await self.frames_eval(page, CAPTCHA_JS))

    async def check_site(self, site, plate, date):
        self.update(site, RUNNING, "טוען את האתר...")
        page = await self.ctx.new_page()
        self.pages[site["id"]] = page
        await page.goto(site["url"], wait_until="domcontentloaded", timeout=45000)
        try:
            await page.wait_for_load_state("networkidle", timeout=12000)
        except Exception:  # noqa: BLE001 - אתרים עם חיבורים פתוחים
            pass
        await self.frames_eval(page, COOKIE_JS)
        for sel in site.get("pre_click", []):
            await page.locator(sel).first.click(timeout=8000)
            await page.wait_for_timeout(800)

        frame = await self.locate_fields(page, site)
        if frame is None:
            text = await self.page_text(page)
            if re.search(LOGIN_HINT, text, re.I):
                return await self.wait_for_user(site, page, "האתר דורש הזדהות/קוד אימות – השלימו ידנית", text)
            return await self.wait_for_user(site, page, "לא זוהו שדות בטופס – מלאו ידנית בלשונית האתר", text)

        self.update(site, RUNNING, "ממלא פרטים...")
        await self.fill_plate(frame, site, plate)
        if frame.date_sel:
            await self.fill_date(frame, site, date)
        baseline = await self.page_text(page)

        if await self.has_captcha(page):
            return await self.wait_for_user(site, page, "יש לסמן 'אני לא רובוט' וללחוץ על כפתור הבדיקה", baseline)

        await self.submit(frame, site)
        await self.await_result(site, page, baseline, self.timeout)

    async def locate_fields(self, page, site):
        if site.get("plate_selector"):
            for f in page.frames:
                if await f.locator(site["plate_selector"]).count():
                    return Target(f, site["plate_selector"], site.get("date_selector"), None)
        best, best_score = None, 0
        for f, r in await self.frames_eval(page, DETECT_JS):
            score = r["plate"] + max(r["date"], 0)
            if r["plate"] >= 3 and score > best_score:
                best_score = score
                best = Target(f, "[data-ankor=plate]",
                              "[data-ankor=date]" if r["date"] >= 3 else None, r["dateType"])
        return best

    async def fill_plate(self, frame, site, plate):
        loc = frame.locator(frame.plate_sel).first
        await loc.click()
        await loc.fill("")
        await loc.type(plate, delay=40)

    async def fill_date(self, frame, site, date):
        loc = frame.locator(frame.date_sel).first
        if frame.date_type == "date":
            await loc.fill(date.isoformat())
            return
        value = format_date(date, site.get("date_format", "dd/mm/yyyy"))
        await loc.click()
        await loc.press("Control+A")
        await loc.press("Backspace")
        await loc.type(value, delay=40)
        await loc.press("Escape")
        current = await loc.input_value()
        if re.sub(r"\D", "", current) != re.sub(r"\D", "", value):
            # שדה לקריאה בלבד / בורר תאריכים – קובעים ערך ישירות
            await loc.evaluate("""(el, v) => {
                const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                el.removeAttribute('readonly'); setter.call(el, v);
                el.dispatchEvent(new Event('input', {bubbles: true}));
                el.dispatchEvent(new Event('change', {bubbles: true}));
                el.dispatchEvent(new Event('blur', {bubbles: true}));
            }""", value)
        await frame.locator("body").click(position={"x": 2, "y": 2}, force=True)

    async def submit(self, frame, site):
        if site.get("submit_selector"):
            await frame.locator(site["submit_selector"]).first.click()
            return
        label = await frame.evaluate(SUBMIT_JS, SUBMIT_WORDS)
        if label:
            await frame.locator("[data-ankor=submit]").first.click()
        else:
            await frame.locator(frame.plate_sel).first.press("Enter")

    async def await_result(self, site, page, baseline, timeout, waiting_user=False):
        if not waiting_user:
            self.update(site, RUNNING, "ממתין לתשובה...")
        base_lines = set(l.strip() for l in baseline.splitlines())
        end = asyncio.get_running_loop().time() + timeout
        while asyncio.get_running_loop().time() < end and not self._stop.is_set():
            await page.wait_for_timeout(1500)
            text = await self.page_text(page)
            new = "\n".join(l for l in text.splitlines() if l.strip() and l.strip() not in base_lines)
            status, snip = classify(new, site)
            if status in (FOUND, NOT_FOUND):
                await self.finish(site, page, status, snip)
                return
            if status == ERROR and not waiting_user:
                await self.finish(site, page, ERROR, "האתר דחה את הנתונים: " + snip)
                return
            if not waiting_user and await self.has_captcha(page):
                waiting_user = True
                end += self.user_timeout
                self.update(site, NEEDS_USER, "נדרש אימות 'אני לא רובוט' – לחצו 'הצג' ואשרו")
        await self.finish(site, page, UNKNOWN, "לא זוהתה תשובה ברורה – בדקו את צילום המסך / הלשונית")

    async def wait_for_user(self, site, page, message, baseline):
        self.update(site, NEEDS_USER, message)
        await self.await_result(site, page, baseline, self.user_timeout, waiting_user=True)

    async def finish(self, site, page, status, message):
        shot = self.results_dir / f"{site['id']}.png"
        try:
            await page.screenshot(path=str(shot), full_page=True, timeout=15000)
        except Exception:  # noqa: BLE001
            shot = None
        self.update(site, status, message, str(shot) if shot else "")

    def write_summary(self, plate, date):
        with open(self.results_dir / "סיכום.csv", "w", newline="", encoding="utf-8-sig") as fh:
            w = csv.writer(fh)
            w.writerow(["חברה", "סטטוס", "פירוט", "כתובת"])
            for s in self.sites:
                r = self.results.get(s["id"], {})
                w.writerow([s["company"], STATUS_HE.get(r.get("status"), ""), r.get("message", ""), s["url"]])
