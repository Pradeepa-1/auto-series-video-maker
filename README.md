# Auto Series Video Maker

Native Android app (Kotlin, Compose, Media3 Transformer). Picks a video, splits it into N-second parts,
renders each as 1080×1920 (or 720×1280) H.264/AAC MP4 with a blurred full-bleed background, the
untouched clip centred, a title banner, series name and part number, and saves to
`Movies/AutoSeriesVideoMaker/Project_###/Series_##/Part_##.mp4`.

## APK உருவாக்க (Android Studio இல்லாமல்)
1. இந்த folder-ஐ GitHub repository-ல் upload/push செய்யுங்கள்.
2. **Actions → Build APK → Run workflow** (push செய்தாலும் தானாக ஓடும்).
3. முடிந்ததும் Artifacts-ல் `AutoSeriesVideoMaker-debug-apk` download செய்து, `app-debug.apk`-ஐ phone-ல் install செய்யுங்கள் (Unknown sources அனுமதி).

## Android Studio-வில்
`File → Open` இந்த folder → Gradle sync → Run. (Gradle 8.9, JDK 17.)
Release APK: `Build → Generate Signed Bundle / APK`.

## Test
முதலில் 20 நிமிட வீடியோ, 50 s → 24 parts (Series 1: 10, Series 2: 10, Series 3: 4). Part 1-ஐ gallery-ல் திறந்து banner, blur, audio, part number சரிபார்க்கவும்.
Unit tests: `gradle :app:testDebugUnitTest` (split/series math).

## Limitations
- Export-ஓடும்போது app foreground-ல் இருக்க வேண்டும் (screen ON வைக்கப்படும்); interrupted export-ஐ resume செய்யும் வசதி இல்லை — முடிந்த parts gallery-ல் இருக்கும்.
- AI title suggestion சேர்க்கப்படவில்லை; title கைமுறையாக உள்ளது.
- History SharedPreferences-ல் (Room அல்ல).
