# DeckOS Lean Core — خطة التنفيذ على مراحل

> **الهدف:** APK واحد خفيف وسريع، أساس Linux يعمل بسلاسة، ثم حزم اختيارية للألعاب وUbuntu الكامل وShizuku.  
> **البناء:** GitHub Actions → APK موقّع.  
> **المرجع المعماري:** `integration-master-plan.md`. هذه الخطة هي **أولوية التنفيذ** وتلغي ترتيب المراحل السابق حيث تتعارض.

---

## 0. القرار التنفيذي

لا نبدأ بـUbuntu الكامل، ولا بـDroidDeck، ولا بدمج APK‏ات.

نبدأ بـ**Lean Core**:

1. تطبيق Android واحد بواجهة Compose.
2.runtime أساسي **Termux native** (bionic) — أخف من proot.
3. خادم **X11** مضمّن من `lorie`.
4. طرفية من `terminal-emulator` + `terminal-view`.
5. سطح مكتب مصغّر: **Openbox + Tint2 + PCManFM**.
6. إدخال، صوت، حافظة، تخزين، خطوط عربية.
7. بناء APK عبر GitHub Actions.

ثم نضيف — كحزم منفصلة لا تثقل الأساس:

- **Comfort Pack:** XFCE + تطبيقات يومية.
- **Ubuntu Pack:** بيئتك الحالية glibc، لتطبيقات لا تعمل على bionic.
- **Game Pack:** DroidDeck/Steam/Proton/FEX.
- **Phone Pack:** Shizuku.

**القاعدة:** التطبيق الأساسي لا يتضمن اللعبة ولا Ubuntu الكامل ولا DroidDeck. كل شيء ثقيل يُنزّل عند الطلب.

---

## 1. لماذا هذا هو الطريق الأفضل

| الخيار | الوزن | الأداء | التوافق | القرار |
|---|---:|---:|---:|---|
| Ubuntu 22.04 الحالي كاملًا | ثقيل جدًا (1203 حزمة) | أبطأ إقلاعًا وذاكرة | ممتاز لـglibc | **حزمة اختيارية** |
| Termux native أساسي | خفيف | الأسرع نظريًا | bionic فقط | **الأساس** |
| DroidDesk native | خفيف/متوسط | مُحسَّن لـX11 وAAudio/Turnip | bionic | **مرجع تحسين** |
| DroidDeck | ثقيل (عدة GB) | ممتاز للألعاب | Steam/Proton | **حزمة ألعاب لاحقة** |

بيئتك الحالية ثمينة للمتوافقية، لكنها ليست نقطة البداية الصحيحة لنسخة خفيفة. نُبقيها كـ**Workspace Pack** ونستخرج منها ما تحتاجه فقط.

---

## 2. فلسفة Lean Core

1. **Lean by default:** لا حزمة تدخل الأساس إلا إذا كانت ضرورية للتشغيل.
2. **Packs on demand:** المتصفح، أدوات التطوير، Ubuntu، الألعاب، Shizuku — اختيارية.
3. **One heavy engine:** سطح المكتب أو اللعبة، لا الاثنان معًا.
4. **No APK merge:** دمج على مستوى المصدر والعقود.
5. **Measure or don't claim:** كل تحسين له رقم قبل/بعد.
6. **No testkey:** توقيع DeckOS خاص.
7. **No `/data`:** تخزين محدود وSAF.
8. **Upstream first:** نعيد استخدام lorie/terminal بدل بناء خادم X وطرفية من الصفر.
9. **Patch small:** تعديلاتنا صغيرة وقابلة للمراجعة والتحديث.
10. **Cut features before quality:** إذا تجاوزنا الميزانية، نحذف ميزة لا نُضعف الاستقرار.

---

## 3. النطاق

### 3.1 Core v0.1 — داخل النطاق

- تطبيق واحد: Home، Desktop، Terminal، Settings.
- X11 مضمّن + سطح مكتب Openbox.
- طرفية PTY بتبويبات بسيطة.
- فتح سطح المكتب والطرفية معًا.
- إدخال لمس/فأرة/لوحة مفاتيح.
- صوت أساسي.
- حافظة ثنائية الاتجاه.
- تخزين `/workspace` + استيراد/تصدير SAF.
- خطوط عربية وRTL في الواجهة.
- 3 تطبيقات أساسية: مدير ملفات، محرر نص، طرفية سطح مكتب.
- تسجيل أخطاء محلي وتصدير.
- فحص تحديث/تراجع بسيط.

### 3.2 خارج Core v0.1

- Ubuntu كامل.
- Chromium/Firefox داخل Linux (متصفح Android يكفي مبدئيًا).
- Python/Node/أدوات تطوير.
- Steam/Proton/FEX.
- Shizuku.
- multi-window Android.
- شاشة خارجية.
- AI مساعد.
- 16 KB كامل (يُقيَّم في Phase 3، ويُبنى في Phase 4/5).

### 3.3 الحزم لاحقًا

| الحزمة | المحتوى | لماذا منفصلة |
|---|---|---|
| **Comfort** | XFCE + Thunar + محرر + عارض صور + PDF | أثقل من Openbox، لكن مألوف |
| **Daily** | متصفح، وسائط، أدوات مكتبية | حجم كبير |
| **Dev** | Python، Node، git، code-server، opencode | يستهلك ذاكرة/قرص |
| **Ubuntu** | Ubuntu 22.04 الحالي مُقلَّمًا | glibc وتوافق |
| **Games** | DroidDeck + Steam/Proton/FEX | عدة GB، بناء خاص |
| **Phone** | Shizuku capabilities | صلاحيات حساسة |

---

## 4. المعمارية

```text
┌──────────────────────────────────────────────┐
│           DeckOS Shell (Compose)             │
│ Home · Desktop · Terminal · Settings         │
├──────────────────────────────────────────────┤
│ Session Orchestrator                         │
│ preflight · start/stop · recovery · metrics  │
├───────────────┬───────────────┬──────────────┤
│ X11 Service   │ Terminal      │ Storage      │
│ lorie/LorieView│ PTY + view   │ workspace/SAF│
├───────────────┴───────────────┴──────────────┤
│ Termux Native Runtime (bootstrap + packages) │
│ Openbox · Tint2 · PCManFM · xterm            │
├──────────────────────────────────────────────┤
│ Android host · SurfaceView · Input · Audio   │
└──────────────────────────────────────────────┘

Optional packs:
  Ubuntu glibc  |  XFCE/Daily/Dev  |  DroidDeck  |  Shizuku
```

### 4.1 الوحدات

```text
app/                     Compose UI + navigation
core/session/            Orchestrator + state machine
core/display/            X11 service, surface, resize
core/terminal/           PTY, terminal view, tabs
core/runtime/            Termux bootstrap, env, processes
core/input/              touch, mouse, keyboard, IME
core/audio/              AAudio/Pulse bridge
core/storage/            workspace, SAF, mounts
core/security/           signing, manifests, policy
packs/                   optional pack installers
bridge/shizuku/          later
bench/                   startup/RAM/size measurements
```

### 4.2 نموذج العمليات

- `:app` — الواجهة.
- `:x11` — خادم X11 منفصل.
- `:session` — مشرف runtime والعمليات.
- `:game` و`:phone` لاحقًا.

المحرك الثقيل يوقف الآخر قبل البدء. لا تعمل جلسة Ubuntu كاملة ولعبة في الوقت نفسه.

---

## 5. Linux الأساسي

### 5.1 Core: Termux Native

**لماذا:** لا proot overhead، لا glibc translation، وأقرب مسار لأداء DroidDesk.

**المكوّنات:**

- Termux bootstrap arm64 (`apt-android-7` أو أحدث متوافق).
- `bash`, `coreutils`, `findutils`, `grep`, `sed`, `gawk`, `tar`, `gzip`, `xz`, `unzip`, `zip`.
- `git`, `curl`, `wget`, `openssh`, `rsync`, `file`, `less`, `nano`/`vim`.
- `x11-repo`: `openbox`, `tint2`, `pcmanfm`, `xterm` (أو بديل متاح فعليًا).
- `fontconfig` + خط عربي واضح + خط monospace.
- `dbus` عند الحاجة فقط.
- AAudio bridge للصوت؛ PulseAudio فقط إذا لزم.
- لا Python/Node في Core؛ يدخلان في Dev Pack.

**ملاحظة توافق:** أسماء الحزم يجب التحقق منها من `x11-repo` الفعلي قبل تثبيت القائمة. لا نفترض توفر حزمة؛ نختبر ونوثّق البديل.

### 5.2 سطح المكتب

**Core profile:** Openbox + Tint2 + PCManFM + xterm.

- Openbox: نافذة خفيفة، قائمة يمين.
- Tint2: شريط مهام بسيط.
- PCManFM: ملفات.
- xterm: طرفية سطح مكتب احتياطية.
- خلفية بسيطة، أيقونات سطح مكتب قليلة.
- لا compositor ثقيل، لا تأثيرات، لا شفافية.

**Comfort profile (اختياري):** XFCE مُقلَّم + Thunar + محرر + عارض صور. يُستخدم إذا كان Openbox غير مريح أو إذا احتاج تطبيق ما XFCE.

**الاختيار:** نقيس الاثنين في Phase 2؛ Openbox هو الافتراضي، وXFCE حزمة.

### 5.3 ماذا نفعل ببيئة Ubuntu الحالية؟

- لا ننقل 1203 حزمة إلى Core.
- نُجمّدها كمرجع في `workspace-full`، ونستخرج:
  - بيانات المستخدم المهمة.
  - قائمة التطبيقات التي لا تعمل على bionic.
  - إعدادات الخط/العربية.
  - سكربتات الوكلاء.
- نُنشئ **Ubuntu Essentials Pack** لاحقًا:
  - XFCE/Xorg أساسي.
  - Chromium أو بديل.
  - Python/Node واحد.
  - أدوات ADB/opencode.
  - بدون snapd/systemd/caches مكررة.
- الهدف: خفض الحزم والقرص ≥50% مقابل خط الأساس، مع نفس التطبيقات الأساسية.

### 5.4 سياسة الحزم

- لا `snapd`، لا `systemd`، لا Playwright browsers، لا Node مكرر، لا caches.
- لا نحذف مكتبات يحتاجها تطبيق أساسي.
- كل حزمة في Core تحتاج مبررًا: «تشغيل»، «إدخال»، «صوت»، «عرض»، «تخزين».
- سقف مبدئي: **≤ 300 حزمة** في Core. يُقاس فعليًا.

---

## 6. دمج DroidDesk وDroidDeck

### 6.1 DroidDesk — نأخذ الأجزاء، لا الواجهة

**نعيد استخدام:**

- `X11ServerService` — فكرة الخدمة المنفصلة.
- `LorieView` / `CmdEntryPoint` — عرض وإدخال X11.
- `X11InputController` — لمس/فأرة.
- `ClipboardSync` — حافظة.
- `LinuxRuntime` — bootstrap، env، proot/termux، بدء الجلسة.
- `XfceMobileProfile` — فقط عند Comfort Pack.
- `AAudio`/`Turnip` — بعد قياس.

**لا نعيد استخدام:**

- Flutter UI.
- `socket_hook` كأساس دائم قبل مراجعة.
- chroot/root الافتراضي.
- ربط `/data`.

**الطريق:**

1. **Phase 1–2:** Core مستقل باستخدام `lorie` + Termux bootstrap.
2. **Phase 4:** إدخال تحسينات DroidDesk واحدًا واحدًا مع قياس:
   - AAudio.
   - Turnip/Zink.
   - bootstrap/relocation.
   - X11 input tuning.
   - mobile profile.
3. نقارن Core vs DroidDesk path؛ الأسرع يصبح الافتراضي، والآخر fallback.

### 6.2 DroidDeck — حزمة ألعاب + وضع لعب ذكي، لا Core

**لا يدخل DroidDeck في APK الأساسي.** أسبابه:

- مصدر 322M، وبناءه يعتمد حزمًا وأرشيف KDE وSteam/Proton/FEX.
- يستهلك ذاكرة/حرارة/تخزينًا كبيرًا.
- يستخدم testkey عامًا في release؛ لا نعيد استخدامه.
- يتغير بسرعة.

**الدمج اللاحق:**

- `GamePack` منفصل، يُنزّل عند الطلب.
- `GameSession` AIDL في `:game`.
- إيقاف سطح المكتب، تفريغ الذاكرة، بدء gamescope/Proton.
- إدخال/صوت/عرض خاص باللعبة.
- توقيع DeckOS، manifest موقّع، SHA-256.
- بناء DroidDeck في workflow منفصل (manual/large runner).

**الخلاصة:** DroidDeck ممتاز كخلفية ألعاب، لكنه ليس أساسًا لتطبيق خفيف.

### 6.3 التكامل الذكي — DroidDesk host + DroidDeck game session

**الفكرة:** ليست دمج الكودين في كتلة واحدة، بل **مضيف واحد + محركان قابلان للتبديل**:

- **DroidDesk = Desktop Host:** runtime، X11، إدخال، حافظة، تخزين، صوت.
- **DroidDeck = Game Engine:** Arch/proot، Steam، Proton/FEX، gamescope، Turnip.
- **DeckOS Shell = Orchestrator:** واجهة موحدة، توجيه، إدارة جلسات، قياس.

#### المبادئ

1. **جلسة ثقيلة واحدة:** لا سطح مكتب كامل مع لعبة في الوقت نفسه.
2. **لا ننسخ DroidDeck كاملًا:** نأخذ محرك الجلسة فقط، لا واجهته ولا KDE.
3. **لا rootfs/Steam داخل APK:** تُنزّل حزمة اللعب عند الطلب.
4. **خدمات مشتركة:** العرض/الصوت/الإدخال/الحافظة/التخزين بعقود واحدة.
5. **Fallback دائم:** فشل اللعبة لا يقتل التطبيق؛ ننظّف ونعود لسطح المكتب.

#### العقد (مبسط)

```text
ISessionManager
  startDesktop()
  startGame(GameRequest)
  stop()
  getState()

IGameSession
  prepare()
  launch()
  stop()
  metrics()

IDesktopSession
  start()
  resize(width, height, dpi)
  stop()
```

#### آلة الحالة

```text
IDLE
  → PREFLIGHT
  → STOP_DESKTOP
  → PREPARE_GAME
  → RUN_GAME
  → STOP_GAME
  → RESTORE_DESKTOP
  → IDLE

أي فشل → CLEANUP → IDLE (مع log وسبب واضح)
```

#### تسلسل تشغيل لعبة

1. المستخدم يختار لعبة من المكتبة.
2. **Preflight:** الحزمة مثبتة؟ RAM كافية؟ مساحة؟ GPU؟ حرارة؟ Steam جاهز؟
3. إن كانت الحزمة ناقصة: تنزيل/تحقق SHA-256/تثبيت.
4. إيقاف سطح المكتب: إنهاء Openbox/PCManFM/X11، تحرير الذاكرة، إبقاء الخدمات المشتركة.
5. بدء `:game` عبر `SessionService` و`ProotFastPath` من DroidDeck.
6. إنشاء سطح gamescope/Wayland، توجيه الصوت/الإدخال، بدء اللعبة.
7. Overlay: FPS، حرارة، بطارية، خروج، إنهاء قسري.
8. عند الخروج: إنهاء مجموعة العمليات، فك mounts، تنظيف sockets.
9. إعادة تشغيل سطح المكتب، أو البقاء في Home إذا اختار المستخدم.

#### ما يُعاد استخدامه من كل مشروع

| المكوّن | DroidDesk | DroidDeck | القرار |
|---|---|---|---|
| Runtime/installer | `LinuxRuntime` | `LinuxRuntimeInstaller` | DroidDeck للعب، DroidDesk للمكتب |
| Fast path | bootstrap native | `ProotFastPath` | حسب الوضع |
| X11/عرض | `X11ServerService` + `LorieView` | Wayland/gamescope | سطح واحد في كل مرة |
| إدخال | `X11InputController` | input relay | موحّد في Shell |
| صوت | AAudio | audio relay | AAudio مشترك |
| GPU | Turnip/AdrenoTools | Turnip/AdrenoTools | مشترك، سياق واحد |
| Steam/Proton/FEX | — | `steam-compatibility`, `droiddeck-fex`, `droiddeck-proton-run` | Game فقط |
| واجهة | لا (Flutter) | لا (KDE/launcher) | Compose فقط |
| التوقيع | لا testkey | لا testkey | مفتاح DeckOS |

#### ما الذي يجعل وضع اللعب «خفيفًا»؟

DroidDeck ليس خفيفًا بطبيعته: Arch + proot + Steam + Proton + FEX + gamescope = طبقات كثيرة. الخفة تأتي من **عدم تحميل ما لا تحتاجه**:

1. **Lazy pack:** لا Arch ولا Steam في APK الأساسي؛ تُنزّل عند أول لعبة.
2. **إيقاف سطح المكتب:** لا Openbox/PCManFM/X11 أثناء اللعب.
3. **تقليم Arch:** إزالة docs/locales/خدمات غير ضرورية؛ إبقاء Steam/Proton/FEX فقط.
4. **إقلاع مباشر:** `steam -applaunch <id>` بدل Big Picture إن أمكن.
5. **دقة أقل + FSR:** 720p/900p مع تكبير، وسقف 30/45/60 FPS.
6. **Turnip:** افتراضي على Adreno؛ llvmpipe fallback فقط.
7. **ARM native أولًا:** FEX فقط لألعاب x86.
8. **Pre-warm محدود:** تجهيز الحاوية بعد أول تشغيل، دون إبقاء لعبة كاملة في الذاكرة.
9. **حماية حرارية:** إيقاف/تخفيض عند تجاوز الحد.
10. **لعبة واحدة:** لا تشغيل متعدد، ولا متصفح Linux خلف اللعبة.

**الحقيقة:** لعبة AAA عبر Proton/FEX ستبقى ثقيلة. «الخفيف» هنا يعني: أساس صغير، بدء أسرع، ذاكرة أقل، وسطح مكتب متوقف — وليس سرعة أصلية.

#### التوجيه الذكي

```text
لعبة Steam/Windows       → Game Mode (DroidDeck)
تطبيق bionic             → Desktop Mode (Termux Native)
تطبيق glibc/Electron     → Ubuntu Pack
صلاحيات هاتف             → Phone Pack (Shizuku)
```

- إذا نقصت RAM/المساحة: رفض واضح + اقتراح إغلاق التطبيقات أو تقليل الدقة.
- إذا فشل الإقلاع: تنظيف + العودة إلى Desktop Mode + تقرير.
- إذا ارتفعت الحرارة: تحذير ثم إيقاف مؤقت/خفض إطار.
- إذا كانت اللعبة ARM-native: تجاوز FEX/Proton.
- إذا كانت x86: FEX/Proton مع توضيح التكلفة.

#### ما لم يُنفَّذ بعد

**لا يوجد كود دمج فعلي بين DroidDeck وDroidDesk حتى الآن.** الموجود: المصادر، التقييم، وهذه الخطة. التنفيذ يبدأ بعد Core v0.1، ضمن **Game Mode**، وليس قبل أساسيات X11/الطرفية.

### 6.4 جدول الاختيار

| الاستخدام | المحرك |
|---|---|
| طرفية + سطح مكتب خفيف | Termux Native Core |
| تطبيق glibc/Electron قديم | Ubuntu Pack |
| برنامج bionic | Termux Native |
| لعبة Steam/Windows | Game Pack (DroidDeck) |
| صلاحيات هاتف | Phone Pack (Shizuku) |

---

## 7. GitHub Actions — بناء APK

### 7.1 المستودع

- مستودع GitHub خاص/عام، branch `main`.
- `sources/` المحلي لا يُرفع (322M+). نستخدم submodules أو forks مثبتة.
- `.github/workflows/`:
  - `core-ci.yml`
  - `core-release.yml`
  - `pack-ubuntu.yml` (manual)
  - `pack-game.yml` (manual/large runner)
  - `pack-phone.yml` (manual)

### 7.2 Core CI

**Triggers:** `push`, `pull_request`, `workflow_dispatch`.

**الخطوات:**

1. Checkout + submodules.
2. Java 17 (Temurin).
3. Android SDK + NDK المطلوب.
4. Gradle cache (لا cache للـrootfs).
5. `./gradlew lintDebug testDebugUnitTest assembleDebug`.
6. بناء X11/lorie إن لم يكن prebuilt.
7. تحليل الحجم: `apkanalyzer`/`unzip -l`.
8. رفع APK + SHA-256 + تقرير الحجم.
9. فشل إذا تجاوز APK الحد أو ظهرت P0 في lint.

**الحدود:**

- `arm64-v8a` فقط.
- `minSdk 28`، `targetSdk 28`، `compileSdk 35/36`.
- Core build ≤ 20 دقيقة على runner عادي.
- لا بناء DroidDeck هنا.

### 7.3 Release

**Trigger:** tag `v*`.

1. `assembleRelease`.
2. توقيع بمفتاح DeckOS من GitHub Secrets:
   - `KEYSTORE_BASE64`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
3. `apksigner verify`.
4. SHA-256 + SBOM + mapping.
5. GitHub Release + artifact.
6. لا testkey، لا secrets في logs.

### 7.4 Workflows الثقيلة

| Workflow | Trigger | Runner | لماذا |
|---|---|---|---|
| `core-ci` | push/PR | ubuntu-latest | سريع |
| `core-release` | tag | ubuntu-latest | توقيع |
| `pack-ubuntu` | manual | ubuntu-latest | rootfs أكبر |
| `pack-game` | manual | large runner أو local | DroidDeck ثقيل |
| `pack-phone` | manual | ubuntu-latest | Shizuku لاحقًا |

**ملاحظة:** GitHub-hosted runner بمساحة ~14GB قد لا يكفي لبناء DroidDeck + packs. لا نضع Game Pack في CI الأساسي.

### 7.5 هيكل workflow مقترح

```yaml
name: core-apk
on:
  push:
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew lintDebug testDebugUnitTest assembleDebug
      - run: ./scripts/apk-size-report.sh
      - uses: actions/upload-artifact@v4
        with:
          name: deckos-core-debug
          path: app/build/outputs/apk/debug/*.apk
```

> استخدم إصدارات Actions مثبّتة بـcommit SHA في المستودع النهائي، لا tags متحركة.

---

## 8. المراحل

| المرحلة | المدة | الهدف | بوابة الخروج |
|---|---:|---|---|
| **0 — Scaffold** | 3–5 أيام | مستودع + CI + APK وهمي | APK يُبنى ويُثبَّت |
| **1 — Core Boot** | 1–2 أسبوع | X11 + طرفية + إدخال | سطح + طرفية معًا |
| **2 — Minimal Desktop** | 2 أسابيع | Termux + Openbox + 3 تطبيقات | سطح مكتب أساسي سلس |
| **3 — Lean Performance** | 1–2 أسبوع | تقليم + AAudio/Turnip + قياس | ميزانية الحجم/الذاكرة |
| **4 — DroidDesk Fast Path** | 2–3 أسابيع | دمج تحسينات DroidDesk | أسرع أو مساوٍ، بلا مخاطر |
| **5 — Game + Packs** | لاحقًا | دمج DroidDeck أولًا، ثم Ubuntu/Dev/Phone | الأساس لم يتضخم + لعبة تعمل |

### Phase 0 — Scaffold

- إنشاء مستودع Android/Gradle/Compose.
- إضافة `third_party/termux-app` و`third_party/termux-x11` كـsubmodules مثبتة.
- إضافة notices/تراخيص.
- إنشاء `core-ci.yml` و`apk-size-report.sh`.
- بناء APK "Hello DeckOS" وتوقيعه debug.
- تثبيت على S24 Ultra.

**Gate:** APK يُثبَّت ويفتح شاشة Home؛ CI أخضر.

### Phase 1 — Core Boot

- دمج `lorie`/`LorieView` أو خدمة X11.
- دمج `terminal-emulator`/`terminal-view`.
- PTY بسيط + فتح `bash`.
- إدخال لمس/فأرة/لوحة مفاتيح.
- فتح سطح X11 تجريبي + طرفية في نفس الشاشة.

**Gate:** 10 دورات فتح/إغلاق، لا crash، إدخال صحيح، 60fps واجهة.

### Phase 2 — Minimal Desktop

- تثبيت Termux bootstrap في مساحة التطبيق.
- إصلاح مسارات bootstrap/`PREFIX`.
- تثبيت Openbox/Tint2/PCManFM/xterm.
- خطوط عربية + إعداد RTL في الواجهة.
- صوت أساسي + حافظة + `/workspace` + SAF.
- إقلاع سطح المكتب، ثم طرفية.

**Gate:** سطح مكتب + طرفية، فتح 3 تطبيقات، صوت، عربية، لا P0.

### Phase 3 — Lean Performance

- قياس خط الأساس: APK، runtime، RAM، بدء، حرارة.
- تقليم الحزم/الملفات/الخطوط/الخدمات.
- Turnip/Zink إن أمكن؛ AAudio.
- تحسين بدء X11/Termux.
- تقييم 16 KB: هل نبني `libtermux`/bootstrap أم نقيّد الأجهزة؟
- إزالة أي ميزة لا تبرر وزنها.

**Gate:** ميزانية الحجم/الذاكرة/البدء تتحقق أو تُوثَّق الفجوة.

### Phase 4 — DroidDesk Fast Path

- نقل `LinuxRuntime` المحسّن، `AAudio`، `Turnip`، input tuning.
- مقارنة A/B مع Core.
- اعتماد المسار الأسرع افتراضيًا.
- مراجعة `socket_hook`؛ لا نستخدمه إن أمكن.
- الحفاظ على fallback مستقر.

**Gate:** تحسين قابل للقياس، لا تراجع أمني/استقرار.

### Phase 5 — Game Mode ثم Packs

> **لا تبدأ قبل Gate 4.** اللعبة ليست بديلًا عن Core؛ هي وضع إضافي فوقه.

#### 5A — Game Mode (أولوية أولى)

- تعريف `ISessionManager` و`IGameSession` وحالات الجلسة.
- نقل `SessionService`/`ProotFastPath`/`Turnip`/gamescope من DroidDeck إلى `:game`.
- إيقاف سطح المكتب وتحرير الذاكرة قبل اللعب.
- تنزيل/تحقق/تثبيت Game Pack عند الطلب.
- توجيه الصوت/الإدخال/العرض إلى اللعبة.
- Overlay: FPS/حرارة/بطارية/خروج.
- تنظيف عند الخروج/الانهيار + عودة سطح المكتب.
- تقليم Arch وإقلاع مباشر للعبة.
- A/B: ARM native vs FEX/Proton، 720p/900p + FSR.
- اختبار 3 ألعاب: خفيفة، متوسطة، ثقيلة.

**Gate 5A:** لعبة واحدة تعمل، الخروج يعيد سطح المكتب، لا crash، استهلاك موثق.

#### 5B — بقية الحزم

- `Ubuntu Essentials` من بيئتك الحالية.
- `Dev` (Python/Node/opencode/code-server).
- `Phone` (Shizuku).
- كل حزمة: manifest موقّع، SHA-256، تثبيت/إزالة نظيفان، لا تغيير في حجم Core.

**Gate 5B:** Core APK لا يزيد؛ الحزم تُثبَّت وتُزال دون آثار.

---

## 9. ميزانية الأداء والحجم

> أرقام مبدئية للتفاوض والقياس، وليست وعودًا.

| المقياس | الهدف | كيف يُقاس |
|---|---:|---|
| Core APK (arm64) | ≤ 70MB | `apkanalyzer`/`ls -lh` |
| Core runtime بعد التثبيت | ≤ 900MB | `du -sh` |
| حزم Core | ≤ 300 | `dpkg`/`pkg list` |
| RAM host خامل | ≤ 250MB | `dumpsys meminfo` |
| RAM سطح المكتب خامل | ≤ 700MB | `free -m` داخل الجلسة |
| فتح التطبيق | ≤ 2.0s | Perfetto/systrace |
| فتح الطرفية | ≤ 2.5s | stopwatch/Perfetto |
| سطح المكتب جاهز | ≤ 8s | stopwatch |
| UI jank | < 1% إطارات >16ms | `gfxinfo` |
| استقرار | 20/20 دورة | اختبار يدوي |
| حرارة 30 دقيقة | < 42°C | Battery/thermal API |

**إن فشل هدف:** نحذف ميزة أو نؤجلها، لا نغيّر القياس.

---

## 10. الأمان والامتثال

- توقيع DeckOS خاص؛ لا testkey.
- `android:exported=false` للخدمات الحساسة.
- لا `/data`؛ `/workspace` + SAF.
- مراجعة `socket_hook` قبل أي استخدام.
- لا root افتراضي.
- لا Shizuku في Core.
- manifests موقّعة + SHA-256 لكل pack.
- GPL: مصدر مقابل، notices، patches، سكربتات بناء.
- Steam/Proton: لا تضمين؛ تنزيل من Valve.
- DroidDeck: لا نعيد استخدام testkey؛ مراجعة ترخيص `DroidDeck-Proton`.

---

## 11. المخاطر

| الخطر | الأثر | المعالجة |
|---|---|---|
| 16 KB/Android 16 | فشل تشغيل | تقييم مبكر؛ rebuild أو تقييد أجهزة |
| X11 على Android 16 | فشل Surface/إدخال | اختبار جهاز حديث؛ fallback |
| bootstrap/package availability | سطح مكتب لا يبدأ | قائمة حزم مختبرة؛ بدائل |
| `socket_hook` | هشاشة/أمان | لا نستخدمه افتراضيًا |
| DroidDeck drift | دمج مؤلم | pin commit؛ لا Core |
| حجم/ذاكرة | تجربة سيئة | ميزانية صارمة؛ حزم |
| GitHub runner | بناء طويل/نفاد مساحة | Core فقط؛ packs منفصلة |
| GPL/Steam | توزيع متوقف | بوابة compliance |
| تعقيد Compose/Android | تأخير | وحدات صغيرة؛ Phase 0 |

---

## 12. القرارات المطلوبة

1. **Core = Termux native** (مقترح) أم Ubuntu proot؟  
2. **Openbox** افتراضي (مقترح) أم XFCE مباشرة؟  
3. **لا متصفح Linux في Core** (مقترح)؛ المتصفح Android افتراضيًا.  
4. **Ubuntu Pack** لاحقًا (مقترح) أم مطلوب من البداية؟  
5. **DroidDeck** حزمة ألعاب (مقترح) أم دمج مبكر؟  
6. **Shizuku** لاحقًا (مقترح).  
7. **GitHub repo** خاص أم عام؟  
8. **مفتاح التوقيع**: من يملكه وكيف يُحفظ؟  
9. **الجهاز الأساسي**: S24 Ultra / Android 14.  
10. **الاسم**: DeckOS مؤقتًا.

**التوصية:** ابدأ بـ1، 2، 3، 4، 5، 6 كما هي. لا تنتظر الباقي.

---

## 13. الخطوات الفورية

1. إنشاء مستودع Android جديد + Compose skeleton.
2. إضافة submodules:
   - `termux-app` (terminal modules).
   - `termux-x11` (lorie).
3. إنشاء `core-ci.yml` + `apk-size-report.sh`.
4. بناء APK فارغ وتثبيته.
5. Phase 1: X11 + طرفية.
6. Phase 2: Termux + Openbox + 3 تطبيقات.
7. قياس، ثم Phase 3/4.

**لا تبدأ بـ:** Ubuntu الكامل، DroidDeck، Shizuku، أو دمج APK‏ات.

---

## 14. المصادر المحلية

تم تنزيل المراجع في `sources/` مع commits مثبتة وتفاصيل في `sources/README.md`:

- DroidDesk `a410944`
- DroidDeck `0e78180`
- termux-app `8629e63`
- termux-x11 `5986cfc`
- Shizuku `b844bc4`

هذه مراجع قراءة/دمج، وليست محتوى يُرفع إلى Git.
