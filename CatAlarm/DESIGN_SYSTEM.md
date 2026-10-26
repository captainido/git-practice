# Oliver · Design System

Premium, quiet, dark. Tonal surfaces instead of shadows, one champagne-gold accent, Rubik throughout (Hebrew, RTL).
All values live in `app/src/main/res/values/` — components never hardcode them.

## Tokens

| Category | Tokens | Notes |
|---|---|---|
| Color · surfaces | `canvas` #0B0C0F · `surface_1` #141519 · `surface_2` #1C1D22 · `surface_3` #25272D | Depth by tone, not elevation |
| Color · lines | `outline` #2C2E35 · `hairline` 12% white | 1dp card stroke |
| Color · text | `text_primary` #F4F1EC · `text_secondary` #A9A49C · `text_tertiary` #6F6B66 | Disabled alarm time uses tertiary |
| Color · accent | `gold` #D8B67A · `on_gold` · `gold_container` · `on_gold_container` | Only for primary action, active state, overline |
| Color · feedback | `danger` · `danger_container` | Delete, setup-needed banner |
| Type | `Clock` 104 light · `Display` 64 light · `CardTime` 52 light · `Headline` 26 medium · `Title` 18 medium · `Body` 16 · `BodySmall` 14 · `Label` 15 medium · `Overline` 13 medium gold | `TextAppearance.Oliver.*` |
| Spacing | `space_1`…`space_9` = 4, 8, 12, 16, 20, 24, 32, 40, 56dp | 4dp grid |
| Radius | `radius_sm` 12 · `md` 16 · `lg` 24 · `xl` 32 · `pill` | Cards lg, hero xl, buttons pill |
| Size | `touch_target` 48 · `button_tall` 64 · `day_chip` 38 · `avatar` 132 · `avatar_ring` 168 | |
| Motion | `motion_short` 150 · `medium` 300 · `long` 600 · `ambient` 1800ms | Ambient = ringing pulse, photo cross-fade |

## Components

| Component | Where | Variants / states |
|---|---|---|
| `Oliver.Card` | Alarm cards, hero, banners | default · danger (banner) |
| Alarm card (`item_alarm`) | Alarm list | collapsed (time, label, summary, switch) · expanded (days, date, label, vibrate, call, delete) · inactive (time in tertiary) |
| Day chip (`day_chip`) | Expanded card | selected = gold fill · unselected = outline |
| `Oliver.Button.Primary` | Ringing: Stop | gold pill, 64dp |
| `Oliver.Button.Glass` | Ringing: Snooze | translucent pill, 64dp |
| `Oliver.Row` | Card options, settings | icon + text (+ switch) |
| FAB | Add alarm | gold, 72dp, bottom-center |

## Behaviour (Android Clock parity)

- One-time alarm switches off after it rings; repeating alarms by weekday; optional specific date.
- New / changed time → nearest upcoming occurrence; snackbar "ההתראה נקבעה לעוד …".
- Notifications: *upcoming* 2h ahead with "ביטול ההתראה", *snoozed* with "ביטול", *missed* after auto-silence.
- Ringing: full-screen on lock screen, heads-up with Snooze/Stop when in use; Back does nothing; volume keys per setting.
- Settings: snooze length (10), silence after (10), gradual volume (on), volume keys (snooze).
