# 🐱 Oliver: a petting app

An Android app where you pet Oliver (a gray Scottish Fold) on the screen, and he reacts with vibration, sounds and animation on his real photo.

## Installation
1. Copy `release/Oliver.apk` to your phone and tap it.
2. Allow "install from unknown sources" if Android asks.
3. Requires Android 8.0 or later.

## What happens when you pet him
| Action | Reaction |
|---|---|
| Slow stroke on the chin / cheeks / forehead / back / belly | The fur gives way under your finger, after about a second he starts **purring** (sound + rhythmic ~25Hz vibration that "breathes"), his eyes close with pleasure, hearts float up |
| Petting the head | His head leans into your finger |
| A single tap | A short "mrrp?" |
| Touching the ears | Ear twitch |
| Touching the eyes | Closes his eyes |
| Touching the **tail** | Tail swish, annoyed meow, sharp vibration |
| **Fast tapping** (5 in 1.5 seconds) / rough, fast stroking / pressing with the whole palm | Gets mad 💢, and if it happens again within 10 seconds, a **hiss** |
| **Too much continuous petting** (~35–48 seconds) | First a warning, then he walks away offended for 8–12 seconds and comes back with a "mrrp". Taking breaks resets the counter |

## Oliver's real voice 🎙
The built-in sounds are synthesized (the purr is convincing, the meow less so). For the real experience: **🎙 Recordings** button → record Oliver in each category (happy meow, mrrp, annoyed meow, purr, hiss). Each category can have several recordings, and the app picks one at random each time.

## Vibration
- Android 12+ with a good vibration motor (Pixel, Samsung S series): delicate "fur texture" while stroking.
- Older devices: a simpler fallback. The vibration can be turned off with the 📳 button.

## Building from source
```bash
cd oliver-app
echo "sdk.dir=/path/to/android/sdk" > local.properties
./gradlew testDebugUnitTest assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```
The signing key (`keystore/oliver.jks`) is committed to the repo on purpose: it's a personal app, and without the same key you can't install an update over the existing app.

## Structure
- `BodyZones.kt`: map of body parts, measured on the photo
- `PettingEngine.kt`: behavior logic (pure, with tests)
- `CatView.kt`: drawing: deformable mesh, breathing, eyes, tail, ears, effects
- `HapticsController.kt` / `SoundController.kt`: vibration and sound
- `RecordActivity.kt`: recording screen
