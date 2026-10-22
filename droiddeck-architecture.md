# DroidDeck — تشريح معماريّ معمّق (المرحلة 3)

**المشروع:** `Droid-Deck/DroidDeck`
**تاريخ التحليل:** 2026-10-21
**نوع التحليل:** تحليل ساكن (Static Analysis) للكود المصدري، سكربتات runtime، ملفات `docs/`، إصدارات GitHub، وissues العامة.
**حدود مهمة:** لم يتم تشغيل التطبيق على جهاز Android فعلي، ولم تُجرَ قياسات أداء مستقلة. كل حكم أدائي مبني على أرقام المشروع نفسه أو على استنتاج من الكود موسوم بذلك.
**علاقة التقرير بالمرحلتين السابقتين:** يبني هذا التقرير على `/root/Desktop/deck/termux-x11-architecture.md` و`/root/Desktop/deck/droiddesk-architecture.md`، ولا يكررهما. المقارنة معهما في القسم 14.

> **تنبيه منهجي:** DroidDeck مشروع سريع التغيّر (أُنشئ في 2026-09-24، وأصدر 0.5.0 في 2026-10-18). الأرقام والملفات المذكورة صحيحة عند تاريخ التحليل وقد تتغير. المسارات المشار إليها نسبية داخل المستودع.

---

## 0. ملخص تنفيذي

### 0.1 بطاقة المشروع

| البُعد | القيمة |
|---|---|
| المستودع | `Droid-Deck/DroidDeck` |
| الترخيص | GPL-3.0 |
| اللغة السائدة | Kotlin (Android) + Java + C/C++ (compositor/JNI) + Bash/Python (runtime) |
| المنصة | Android 9+، `arm64-v8a` فقط |
| المعرّف | `com.droiddeck.launcher` (سابقًا `com.steamdeck.launcher`) |
| البناء | `compileSdk 34`، `minSdk 26`، `targetSdk 28`، `versionCode 14`، `versionName 0.5.0` |
| النجوم | 11,937 |
| Fork | 613 |
| العناصر المفتوحة | 376 (issues + PRs) |
| المساهمون | 49 (أعلى مساهمين: 551 و398 و101 commit) |
| أُنشئ | 2026-09-24 |
| آخر دفع | 2026-10-21 |
| أحدث إصدار | 0.5.0 — 2026-10-18 — APK ~101MB — 83,492 تحميلًا للنسخة القياسية |
| حجم المستودع | ~190MB (مع الوسائط والتوثيق) |
| حجم الشيفرة | 462 ملف Kotlin، 23 ملف Java، 281 ملف C/C++ تحت `app/src/main` |

### 0.2 اثنتا عشرة نتيجة رئيسية

1. **DroidDeck ليس محاكيًا كاملًا (Full-System Emulator).** هو تطبيق Android يشغّل عميل Steam الرسمي ARM64 وProton ARM64 الأصليين، ويستخدم FEX فقط لترجمة تعليمات x86-64 داخل مساحة المستخدم عند الحاجة.
2. **الرهان المعماري الأهم هو «Valve ARM64» وليس Wine fork.** المشروع لا يبني Wine خاصًا به، بل يستخدم `Proton Experimental (ARM64)` و`Proton 11.0 (ARM64)` من Steam (app id `4427310`)، مع FEX (app id `3127680`) وSteam Linux Runtime.
3. **الرسم أصلي بالكامل على GPU.** Turnip/Mesa يعمل داخل الـguest عبر KGSL، وDXVK/VKD3D-Proton يترجمان Direct3D إلى Vulkan. لا يوجد تحويل GPU إلى CPU ولا virgl افتراضي.
4. **الطبقة الوحيدة «الثقيلة» هي ترجمة x86-64.** في ألعاب Windows يعمل Wine بصيغة ARM64EC ويُحمَّل FEX داخل العملية كـDLL؛ أي أن تعليمات اللعبة x86-64 وحدها هي التي تُترجم، بينما syscalls وGPU وSteam أصليّة.
5. **proot ليس محاكيًا، لكنه ليس حاوية أمنية.** يستخدم لترجمة المسارات والتعامل مع syscalls التي يحجبها Android، بتكلفة 15–60µs لكل trap، مع fastpath داخل العملية يخفّض تكلفة مسارات الملفات إلى أقل من 1µs.
6. **العرض يمر عبر compositor Wayland مخصص.** gamescope يرسم إلى compositor مكتوب بـC/Vulkan، يستورد dma-buf كـ`VkImage` ثم ينسخه إلى swapchain — نسخة كاملة لكل إطار. المسار صفري النسخ (AHB layers) نموذج أولي مُقيّد بمتغير بيئة، وليس الافتراضي.
7. **التجربة ليست «Steam فقط».** تشمل سطح مكتب KDE Plasma، Flatpak/Flathub، متاجر GOG/Epic/Amazon، تطبيقات AppImage، SteamVR على Quest، ونظارات XR، ومحاكيات، وأدوات سطح مكتب.
8. **المتطلب الحقيقي ليس root بل Adreno حديث.** Adreno 730+ أو 8xx مدعوم؛ Adreno 6xx تجريبي؛ Mali وXclipse وPowerVR وAdreno 710 غير مدعومة. على Android 14+ يجب تفعيل «Disable child process restrictions» في خيارات المطوّر.
9. **النضج منخفض رغم الشعبية.** 0.5.0 بعد أقل من شهر من إنشاء المستودع، 376 عنصرًا مفتوحًا، وأعطال مرتبطة بطرازات وأجهزة محددة. هذا مشروع beta عالي الوتيرة وليس منتجًا إنتاجيًا.
10. **الأمان: لا root، لكن لا عزل.** التطبيق يملك `MANAGE_EXTERNAL_STORAGE` وصلاحيات كاميرا/ميكروفون/تثبيت حزم، ويشغّل Steam وألعابًا غير موثوقة بنفس UID. proot يترجم ولا يعزل.
11. **المقارنة مع DroidDesk/Termux:X11 ليست متكافئة.** DroidDeck «جهاز ألعاب Steam» متكامل، بينما DroidDesk وTermux:X11 «سطح مكتب Linux». الأول أقوى في الألعاب، والثانيان أقوى في الحرية وسطح المكتب.
12. **على بيئة التحليل الحالية لا يمكن تشغيل DroidDeck كما هو.** البيئة aarch64 Ubuntu 22.04 فوق نواة Android مُموّهة، مع `/dev/kgsl-3d0` موجود لكن `/dev/dri` غير متاح، ولا Steam/Wine/Vulkan tools. DroidDeck يحتاج Android framework وSurfaceFlinger وAdreno وUID تطبيق.

### 0.3 الحكم العملي

DroidDeck هو **أقرب شيء إلى «Steam Deck على Android»** من حيث التكامل: عميل Steam الرسمي، Proton الرسمي ARM64، gamescope، إدخال Steam، وصوت منخفض الكمون. لكنه:

- **ممتاز** لمالك جهاز Adreno 730+/8xx يريد مكتبة Steam بأقل إعداد.
- **غير مناسب** لأجهزة Mali/Xclipse/PowerVR أو Adreno 710.
- **غير مناسب** لمن يريد عزلًا أمنيًا أو استقرارًا إنتاجيًا.
- **ليس بديلًا** عن Steam Deck في الأداء/الدعم لكل الألعاب، لأن x86-64 يظل مترجمًا عبر FEX.

---

## 1. ما هو DroidDeck؟

### 1.1 التعريف

DroidDeck تطبيق Android يحوّل الهاتف/اليد المحمولة إلى «تجربة SteamOS»:

- واجهة Steam Big Picture بعميل Valve الرسمي.
- ألعاب Windows عبر Proton ARM64 (وليس Wine fork).
- ألعاب Linux x86-64 عبر FEX.
- ألعاب Linux ARM64 أصلية عند توفرها.
- سطح مكتب KDE Plasma وأدوات Linux عند الحاجة.
- متاجر إضافية: GOG، Epic Games، Amazon Games.
- دعم SteamVR/Quest ونظارات XR في 0.5.0.

### 1.2 ما ليس DroidDeck

- **ليس QEMU/KVM**: لا يوجد جهاز افتراضي ولا نواة Linux كاملة مُحاكاة.
- **ليس Winlator**: لا يستخدم Wine مبنيًا خصيصًا مع Box86/Box64 وXServer تقليدي.
- **ليس Termux**: لا يبدأ من shell؛ يبدأ من تطبيق Android بجلسة Steam.
- **ليس حاوية أمنية**: proot يغيّر المسارات ولا يعزل العمليات.
- **ليس بديلًا كاملًا عن Steam Deck**: التوافق والأداء يعتمدان على FEX وTurnip وتعريفات الجهاز.

### 1.3 الفكرة المعمارية في سطر

> **Android هو المضيف، وLinux هو الضيف، وSteam/Proton هما التطبيق، وFEX هو الجسر الوحيد لتعليمات x86-64.**

---

## 2. المنهجية والحدود

### 2.1 المصادر

- المستودع الكامل بعد `git clone --depth 50` إلى `/tmp/opencode/research-droiddeck/DroidDeck`.
- `README.md`، `AGENTS.md`، `LICENSE`.
- `docs/development/*.md` (خصوصًا `x86-64-steam-client.md`، `game-environment.md`، `proot-performance.md`، `flatpak.md`، `thor-sd-storage-investigation.md`).
- `app/src/main/AndroidManifest.xml` و`app/build.gradle`.
- Kotlin/Java: `SessionService.kt`، `LinuxRuntimeInstaller.java`، `LinuxVulkanDriver.java`، `TurnipDriver.java`، `WaylandCompositor.java`، `CompositorHost.kt`، `PulseAudioComponent.java`، `DirectAudioRelayComponent.java`.
- C: `app/src/main/cpp/waylandcomp/src/compositor.c`، `vk_present.c`، `ahb_swapchain.c`.
- Shell/Python: `tools/linuxfs/overlay/usr/local/bin/*` و`tools/linuxfs/preload/*` و`tools/proot/fastpath`.
- GitHub API: repo metadata، releases، assets، contributors، issues.
- كتالوج المكوّنات: `Droid-Deck/DroidDeck-Components` (`linuxfs.json`، `fex.json`، `components.json`).

### 2.2 ما تم التحقق منه بالكود

- اسم الحزمة، SDK، ABI، الصلاحيات، الخدمة الأمامية.
- تثبيت runtime من كتالوج SHA-256 مع فك Zstd.
- بناء بيئة الجلسة ومتغيرات `BL_*`.
- تنزيل عميل Steam ARM64 من CDN والتحقق لكل جزء.
- تسجيل Proton ARM64 كأداة توافق وربط FEX.
- مسار gamescope/compositor والنسخ لكل إطار.
- proot والـbinds وfastpath.
- الصوت عبر PulseAudio + DirectAudio.
- سطح مكتب KDE.

### 2.3 ما لم يتم التحقق منه ديناميكيًا

- FPS الحقيقي على Adreno 730/750/8xx.
- الحرارة والاستهلاك والبطارية.
- توافق الألعاب الفعلي (خصوصًا D3D12 وanti-cheat).
- كمون الصوت والإدخال.
- سلوك Android 14/15/16 مع الخدمة الأمامية وchild process restrictions.
- صحة الشيفرة الأصلية المسبقة البناء (provenance).
- أداء FEX على ألعاب متعددة الأنوية.

### 2.4 اصطلاحات الأدلة

| الرمز | المعنى |
|---|---|
| [كود] | مقروء مباشرة من الشيفرة المصدرية |
| [وثيقة] | من `docs/` أو README |
| [إصدار] | من release notes |
| [Issue] | من GitHub issues |
| [استنتاج] | تحليل المؤلف وليس ادعاءً من المشروع |

---

## 3. الهوية والنضج

### 3.1 الأرقام

| المؤشر | القيمة | الملاحظة |
|---|---|---|
| النجوم | 11,937 | نمو استثنائي في أقل من شهر |
| Fork | 613 | مؤشر على تعديلات/نسخ محلية كثيرة |
| العناصر المفتوحة | 376 | يشمل PRs |
| المساهمون | 49 | لكن أعلى 3 مساهمين يملكون معظم العمل |
| الإصدارات | 0.4.0، 0.5.0، وغيرها | 0.5.0 هي الناضجة نسبيًا |
| تحميلات 0.5.0 | 83,492 للنسخة القياسية | + نسخ antutu/ludashi/pubg |
| الترخيص | GPL-3.0 | متوافق مع أسلاف المشروع |
| أسلاف | WinNative وBannerlator | مذكورة في README |

### 3.2 الإصدارات

- **0.5.0 (2026-10-18):** SteamVR على Quest 3/3S، Quest 2 تجريبي، نظارات VITURE/RayNeo/XREAL، Cardboard VR، Epic Games Launcher لنظام Linux عبر FEX، عميل Steam x86-64 اختياري، ألعاب Linux x86 عبر FEX، تحسينات متجر/إنجازات.
- **0.4.0 (2026-10-12):** إصدار أقدم؛ التفاصيل في release notes.
- **0.5.0 assets:** أربع APKs بحجم ~101MB (قياسي، antutu، ludashi، pubg) + `SHA256SUMS.txt`.

### 3.3 النضج

**إيجابًا:**

- وتيرة تطوير عالية جدًا.
- توثيق داخلي غير عادي لمشروع بهذا العمر (`docs/development` غني جدًا).
- CI وبناء reproducible جزئيًا (`tools/build_local.sh`، متطلبات Docker/Java 17/NDK/Rust).
- اختبارات native regression مذكورة لبعض الطبقات.

**سلبيًا:**

- عمر المشروع أقل من شهر؛ لا تاريخ استقرار طويل.
- 376 عنصرًا مفتوحًا مع أعطال جهازية.
- bus factor: ثلاثة مساهمين رئيسيين.
- اعتماد كبير على مكوّنات Valve ومكوّنات مسبقة البناء.

---

## 4. البنية العامة

### 4.1 الطبقات الخمس

```text
┌──────────────────────────────────────────────────────────────────────┐
│ 5) اللعبة                                                             │
│    Windows x86-64 PE  →  Wine ARM64EC + FEX (in-process)              │
│    أو Linux x86-64    →  FEX user-mode                                │
│    أو Linux ARM64     →  native                                       │
├──────────────────────────────────────────────────────────────────────┤
│ 4) Proton / Steam Runtime                                             │
│    Proton Experimental (ARM64) / Proton 11.0 (ARM64)                  │
│    DXVK 2.7.1 / VKD3D-Proton  →  Vulkan                               │
├──────────────────────────────────────────────────────────────────────┤
│ 3) جلسة Linux                                                        │
│    Arch Linux ARM rootfs + gamescope 3.16 + Xwayland + KDE Plasma     │
│    Steam ARM64 client + PulseAudio 13 + Flatpak                       │
├──────────────────────────────────────────────────────────────────────┤
│ 2) proot + preloads                                                  │
│    ترجمة مسارات + syscall filtering + fastpath + libblsession         │
│    ~25 bind mounts، fake_id0، kill-on-exit                            │
├──────────────────────────────────────────────────────────────────────┤
│ 1) Android                                                            │
│    SessionService + SurfaceView/SurfaceControl + Input + AudioTrack    │
│    AdrenoTools (Turnip host) + compositor Wayland مخصص (C/Vulkan)      │
└──────────────────────────────────────────────────────────────────────┘
```

### 4.2 المكوّنات الرئيسية

| المكوّن | الملف/المسار | الدور |
|---|---|---|
| نقطة الدخول | `MainActivity.kt` | واجهة Android وإدارة التثبيت |
| خدمة الجلسة | `session/SessionService.kt` (1,891 سطرًا) | تشغيل proot والجلسة ومراقبة الحالة |
| تثبيت runtime | `runtime/LinuxRuntimeInstaller.java` | تنزيل `linuxfs.tar.zst` والتحقق والفك |
| تشغيل proot | `runtime/LinuxRuntime.java`، `ProotFastPath.kt` | تجميع الأوامر والـbinds |
| تعريف GPU | `gpu/LinuxVulkanDriver.java`، `TurnipDriver.java` | استيراد ICD إلى الضيف |
| compositor | `wayland/WaylandCompositor.java` + `cpp/waylandcomp` | عرض Wayland على Surface |
| الصوت | `audio/PulseAudioComponent.java`، `DirectAudioRelayComponent.java` | PulseAudio + relay إلى Android |
| الإدخال | `input/*` | لمس/يد/لوحة/Steam Input |
| سطح المكتب | `tools/desktop-kde` | KDE Plasma + Vapor theme |
| سكربت الجلسة | `tools/linuxfs/overlay/usr/local/bin/droiddeck-session` (1,259 سطرًا) | gamescope/Steam/Proton |
| أداة التوافق | `steam-compatibility`، `droiddeck-proton-run` | تسجيل Proton ARM64 وتشغيله |
| FEX | `droiddeck-fex` (846 سطرًا) | تجهيز rootfs وFEX والـthunks |

### 4.3 لماذا هذا التصميم؟

- **إعادة استخدام Valve:** بدلًا من بناء Wine/Proton fork، يستخدم المشروع حزم Valve الرسمية. هذا يقلل الصيانة ويزيد التوافق.
- **عزل المسؤوليات:** Android يدير النافذة/الصوت/الإدخال، وLinux يدير Steam/الألعاب.
- **تقليل الاحتكاك:** لا يحتاج المستخدم إلى Termux أو proot-distro أو إعداد X11.
- **قابلية التحديث:** runtime وSteam وProton تُحدَّث independently عن APK.

---

## 5. مسار تشغيل اللعبة من النقرة إلى الإطار

### 5.1 التثبيت الأول

1. المستخدم يثبّت APK (sideload) ويمنح الصلاحيات.
2. التطبيق ينزّل `linuxfs.json` من `Droid-Deck/DroidDeck-Components` (GitHub raw).
3. يقرأ `url` و`sha256` و`size`، ينزّل `linuxfs.tar.zst` (~790.9MB)، يتحقق من SHA-256، ثم يفك Zstd إلى `files/linuxfs`.
4. الكتالوج يصف: Arch Linux ARM aarch64، gamescope 3.16، Xwayland، Mesa 26.2 (Turnip KGSL + Zink)، GTK 2، PulseAudio، وسكربتات الجلسة.
5. عند أول تشغيل لـSteam: ينزّل عميل Steam ARM64 من Valve CDN، ثم Proton ARM64 وFEX عند الحاجة.

**ملاحظة أمنية:** الكتالوج نفسه غير موقّع رقميًا؛ الثقة مبنية على TLS + GitHub + SHA-256 داخل الكتالوج. أي سيطرة على المستودع/الكتالوج تعني سيطرة على runtime.

### 5.2 تكوين الجلسة

`SessionService` يبني أمر proot تقريبًا كالتالي [كود/وثيقة]:

```text
libproot.so
  --kill-on-exit
  --kernel-release=<kompat>
  -i <uid>:<uid>                 # fake_id0
  --bind=/dev /proc /sys ...
  --bind=/dev/kgsl-3d0 ...
  --bind=/dev/shm ...
  --bind=<storage> ...
  /usr/bin/env -i ... droiddeck-session <mode>
```

ثم:

- `nice -6` لعملية proot بعد ثانيتين لتقليل التنافس مع اللعبة.
- `libblsession.so` يُحمَّل في كل عملية ضيف عبر `/etc/ld.so.preload` لتعديل المسارات وsyscalls.
- `libblfastpath` يعالج مسارات الملفات الشائعة داخل العملية بدلًا من trap إلى proot.

### 5.3 عميل Steam ARM64

`droiddeck-steam-install` [كود]:

- القناة: `steamdeck_publicbeta`.
- المانيفست: `steam_client_steamdeck_publicbeta_linuxarm64`.
- CDNs: `client-update.steamstatic.com`, Akamai, Fastly.
- يحمّل كل جزء، يتحقق من SHA-256 الموجود في المانيفست، ثم `unzip`.
- ينشئ روابط `~/.steam/root`, `steam`, `bin64`, `sdk64`, `sdkarm64`, `binarm64`, `sdk32`.
- يدعم الإصلاح التلقائي عند فشل بدء العميل مرتين، ويحذف الأجزاء التالفة.

**الدلالة:** هذا عميل Valve رسمي، وليس إعادة بناء. هذا يفسر لماذا تعمل Big Picture وSteam Input وVAC (مع عميل x86-64) بشكل قريب من Steam Deck.

### 5.4 Proton ARM64 وFEX

`steam-compatibility` [كود] يسجّل:

- `droiddeck-proton-arm64` → Proton Experimental (ARM64) / Proton 11.0 (ARM64)، app id `4427310`.
- `droiddeck-fex` → FEX app id `3127680`، مع `SteamLinuxRuntime_sniper` (1628350) و`SteamLinuxRuntime_4` (4183110).
- يكتب `compatibilitytool.vdf` و`toolmanifest.vdf` في `~/.local/share/droiddeck-compat`.
- يختار الأداة من `config.vdf` (CompatToolMapping) أو الافتراضي.

`droiddeck-fex` [كود] يجهّز:

- FEX rootfs (Ubuntu 24.04 x86، ~525MB تنزيلًا، ~1.5GB بعد الفك).
- thunks لـGL/Vulkan.
- preloads (`libblsession.so`, `libfakeinput.so`).
- يوقف fastpath عند تشغيل برنامج 32-بت لأن `libblfastpath` يكسر FEX 32-bit.
- يخفي `/dev/dri` عن برامج x86 لتجنب دوران Turnip على KGSL.
- يدير `FEXServer` الذي يملك rootfs.

### 5.5 Wine/ARM64EC وDXVK/VKD3D

- Proton ARM64 هو Wine ARM64 مع دعم ARM64EC.
- FEX يُحمَّل داخل عملية Wine كـDLL، فيترجم كود x86-64 الخاص باللعبة فقط.
- DXVK 2.7.1 يترجم D3D9/10/11 إلى Vulkan.
- VKD3D-Proton يترجم D3D12 إلى Vulkan.
- Vulkan يذهب إلى Turnip داخل الضيف، ثم KGSL، ثم GPU.

**النتيجة:** لا تُحاكى تعليمات GPU ولا syscalls ولا Steam. المحاكاة/الترجمة محصورة في تعليمات x86-64.

### 5.6 العرض

```text
اللعبة (Vulkan/DXVK)
   ↓
gamescope (Wayland backend)
   ↓ zwp_linux_dmabuf_v1
compositor Wayland مخصص (C/Vulkan)
   ↓ vk_present.c: import dma-buf → VkImage
   ↓ blit إلى swapchain
Android Surface (VkSurface)
   ↓
SurfaceFlinger → الشاشة
```

[وثيقة/كود]

- `compositor.c` (~3,306 سطرًا) و`vk_present.c` (~2,139 سطرًا).
- كل إطار يُستورد كـ`VkImage` ثم يُنسخ إلى swapchain — **نسخة كاملة لكل إطار**.
- المسار صفري النسخ موصوف في `ZERO_COPY_SPIKE.md`: تحويل كل نافذة إلى `ASurfaceControl` + `AHardwareBuffer`، لكنه يحتاج تعديل WSI في Turnip (~300 سطر) وبروتوكول Wayland خاص، وهو نموذج مُقيّد بـ`DROIDDECK_WAYLAND_ZERO_COPY=1`.
- قياس spike: فجوة ~20% مقابل X11 على AIO Adreno 750: 596 مقابل 752 (Vulkan/D3D12)، و336 مقابل 430. هذا قياس بحثي وليس وعدًا لإصدار.

**لماذا لا يُستخدم X11 فقط؟** لأن Wayland يسمح بدمج أفضل مع SurfaceFlinger وHDR وتعدد النوافذ، لكنه يفرض نسخة إضافية حاليًا. المشروع يحتفظ بمسار X11 كبديل/مقارنة.

### 5.7 الصوت

- PulseAudio 13 داخل الضيف.
- مخرج مخصص `DirectAudio` (LGPL-2.1+) عبر `libdirectaudiorelay.so`، بمقبس Unix وحلقة mmap، مع تقليل حجم الطلب لتجنب تأخير 100ms.
- ميكروفون عبر FIFO.
- إعداد ALSA في الضيف يوجّه إلى PulseAudio.
- النتيجة: صوت منخفض الكمون نسبيًا، لكنه يعتمد على relay إضافي.

### 5.8 الإدخال

[كود]

- `FakeInputWriter`/`FakeInputRingPaths`: حلقة إدخال مشتركة مع الضيف.
- `EvdevKeys.java`: تحويل مفاتيح Android إلى evdev.
- `PadBridge.java`/`PadMouse.kt`: جسور gamepad/فأرة.
- `OnScreenControls.kt`/`DeckControls.kt`: أزرار على الشاشة، grips، trackpads.
- `GestureKeys.kt`: إيماءات.
- `SteamTouchControls`/Steam Input: يعمل عميل Steam كأنه على Deck، فيرى الأزرار الافتراضية.

**القيد:** الإدخال يمر بعدة طبقات (Android → ring → evdev/uinput → Wine/Steam)، لذا كمون الفأرة الحقيقي قد يكون أعلى من native. issue #1247 يشتكي من «lag horrendous» مع فأرة خارجية.

### 5.9 التخزين

- مكتبات الألعاب على `/mnt/droiddeck` و`/mnt/droiddeck-sd` (aliases تاريخية).
- يعتمد `MANAGE_EXTERNAL_STORAGE` للوصول الواسع.
- دراسة `thor-sd-storage-investigation.md`: القراءة عبر FUSE أبطأ بكثير من exFAT الأصلي:
  - قراءة دليل 154,920 عنصرًا: ~2.3ms native exFAT مقابل ~501ms عبر FUSE.
  - عملية أخرى: 9.25ms مقابل 5.56s.
- هذا يفسر بطء تثبيت/تحميل بعض الألعاب على بطاقات SD.

### 5.10 سطح المكتب KDE

- `tools/desktop-kde`: KDE Plasma + `kwin` + `plasmashell` + Valve Vapor theme.
- يظهر كوضع `desktop` مستقل عن Steam.
- README: **تركيب سطح المكتب يستخدم software rendering**. أي أن سطح المكتب ليس GPU-accelerated، بينما الألعاب عبر gamescope/compositor هي GPU.
- التطبيقات: Flatpak/Flathub (ARM64)، AppImage، حزم من المستودع، متصفحات، محاكيات.

---

## 6. لماذا ليست «محاكاة ثقيلة»؟

### 6.1 تصنيف الطبقات

| الطبقة | التقنية | هل هي محاكاة؟ | التكلفة |
|---|---|---|---|
| نظام كامل | QEMU/KVM | نعم، ثقيلة جدًا | عالية |
| تعليمات CPU | FEX/Box64 | ترجمة x86-64 | متوسطة، تعتمد على JIT |
| Windows API | Wine/Proton | تنفيذ APIs | منخفضة-متوسطة |
| Direct3D | DXVK/VKD3D | ترجمة إلى Vulkan | منخفضة-متوسطة |
| GPU | Turnip/Mesa | أصلية | منخفضة |
| مسارات الملفات | proot | ترجمة مسارات | 15–60µs لكل syscall |
| Steam | عميل ARM64 رسمي | أصلي | منخفضة |

### 6.2 ما يُترجم فعلًا

- **ألعاب Windows x86-64:** كل تعليمات x86-64 في وحدات اللعبة/مكتباتها تُترجم عبر FEX. Wine نفسه ARM64EC، لكن أجزاءً منه قد تعمل x86-64 إذا جاءت من اللعبة أو DRM.
- **ألعاب Linux x86-64:** العملية كلها تحت FEX (لأنها ELF x86-64).
- **ألعاب Linux ARM64:** أصلية 100%.
- **Steam ARM64:** أصلي.
- **الرسوميات:** Vulkan native؛ لا ترجمة GPU.

### 6.3 لماذا هذا أسرع من Winlator/Mobox؟

[استنتاج]

- Winlator/Mobox عادةً يشغّلان Wine x86 عبر Box64/FEX + XServer، مع مسار GPU قد يكون Turnip/VirGL، لكن بدون عميل Steam ARM64 رسمي ولا gamescope ولا Proton ARM64.
- DroidDeck يقلل الطبقات: Steam أصلي، Proton أصلي، gamescope أصلي، والإدخال/الصوت/العرض مدمجون.
- في المقابل، FEX يظل مكلفًا في الألعاب كثيفة x86، وproot يضيف تكلفة في path syscalls.

### 6.4 ما لا يعنيه «بدون محاكاة ثقيلة»

- **لا يعني أن الألعاب x86 تعمل بسرعة native.** FEX JIT يعيد ترجمة التعليمات، وقد يكون أبطأ 20–60% حسب اللعبة [استنتاج].
- **لا يعني توافقًا كاملًا.** anti-cheat على مستوى النواة لا يعمل؛ EAC/BattlEye يعملان فقط إذا فعّلهما المطوّر لوضع Proton.
- **لا يعني أن كل الألعاب D3D12 تعمل.** Adreno 6xx قد تت crash في D3D12 [README].
- **لا يعني غياب proot.** proot يظل طبقة أساسية، لكنه ليس محاكي CPU.

---

## 7. القدرات

### 7.1 الألعاب والمنصات

- مكتبة Steam كاملة (Big Picture، مكتبة، تنزيلات، cloud saves حسب اللعبة).
- ألعاب Windows عبر Proton ARM64.
- ألعاب Linux x86-64 عبر FEX.
- ألعاب Linux ARM64 أصلية.
- VAC في بعض الألعاب عبر عميل Steam x86-64 الاختياري.
- GOG، Epic Games (عميل Epic Linux عبر FEX)، Amazon Games.
- متجر Flatpak/Flathub وAppImage.
- محاكيات (مذكورة في حزمة Desktop & apps).

### 7.2 العرض والإدخال

- gamescope مع HDR (حسب الجهاز).
- compositor مخصص يدعم تعدد النوافذ.
- fps limit، VRR/refresh rate، frame generation (LSFG، يتطلب Lossless Scaling).
- Steam Input، أزرار على الشاشة، trackpads، grips.
- فأرة/لوحة/يد خارجية.
- Joy-Con وSteamVR controllers (0.5.0).

### 7.3 VR/XR

- SteamVR على Meta Quest 3/3S (OpenXR native activity).
- Quest 2 تجريبي.
- VITURE/RayNeo/XREAL، وضع Cardboard، تتبع يد بالكاميرا.
- هذه قدرات متقدمة لكنها خارج نطاق «تشغيل الألعاب» الأساسي.

### 7.4 سطح المكتب

- KDE Plasma كامل.
- متصفحات، أدوات Linux، تطبيقات Flatpak.
- clipboard، bookmarks، نافذة قواعد، عودة إلى Gaming.

### 7.5 الإدارة

- تثبيت/تحديث runtime من داخل التطبيق.
- إصلاح Steam تلقائيًا.
- إعدادات لكل لعبة: Proton، FEX preset، DXVK/VKD3D components، متغيرات بيئة.
- سجلات مشروحة (scrubbed) لآخر 30 جلسة.

---

## 8. المزايا

### 8.1 معمارية

1. **استخدام Valve الرسمي:** أكبر ميزة. لا Wine fork، لا Box64، لا XServer تقليدي.
2. **فصل واضح:** Android للعرض/الصوت/الإدخال، Linux للتشغيل.
3. **تحديثات مستقلة:** runtime/Steam/Proton يمكن تحديثها دون APK.
4. **قابلية التوسع:** متاجر، Flatpak، AppImage، سطح مكتب، VR.

### 8.2 تكامل

- تجربة «turnkey»: تثبيت APK → runtime → Steam → لعب.
- لا Termux ولا أوامر shell للمستخدم العادي.
- إدارة تلقائية لـProton/FEX/components.
- إدخال Steam Input جاهز.

### 8.3 أداء

- GPU native عبر Turnip.
- proot شبه مجاني أثناء اللعب بعد fastpath.
- gamescope يقلل overhead compositing مقارنة بسطح مكتب كامل.
- إمكانية تخصيص الأنوية (`taskset`) وFPS.

### 8.4 هندسة

- توثيق داخلي ممتاز.
- اختبارات/CI.
- تثبيت runtime بـSHA-256.
- Steam components بـSHA-256.
- إصلاح ذاتي للأعطال الشائعة.

### 8.5 مجتمع

- نمو سريع.
- مساهمون من مشاريع WinNative/Bannerlator.
- releases متكررة.

---

## 9. العيوب والقيود

### 9.1 قيود الأجهزة

- **Adreno فقط:** Mali، Xclipse، PowerVR، Adreno 710 غير مدعومة.
- **Adreno 6xx تجريبي:** D3D12 قد crash، D3D11 عبر DXVK 2.
- **الذاكرة/التخزين:** ~3GB runtime + ~1.1GB desktop + ألعاب. الأجهزة بذاكرة 8GB قد تعاني.
- **الحرارة:** FEX + GPU + proot = حرارة عالية [استنتاج].

### 9.2 قيود Android

- **Android 14+:** يجب تفعيل «Disable child process restrictions» في Developer options. هذا ليس root لكنه إعداد مطوّر.
- **Android 12/13:** زر «Fix it for me».
- **`targetSdk 28`:** يخالف سياسات Play الحديثة، ويمنع النشر عبر Play، ويقلل تطبيق قيود الأمان.
- **الخدمة الأمامية `dataSync`:** قد تتعرض لقيود زمنية على Android 14+ [استنتاج/كود].
- **الصلاحيات الواسعة:** `MANAGE_EXTERNAL_STORAGE`، كاميرا، ميكروفون، تثبيت حزم.

### 9.3 قيود الأداء

- **نسخة إطار كاملة في compositor:** حتى إصلاح zero-copy.
- **proot trap:** 15–60µs لكل path syscall؛ 75–95k traps/s سقف.
- **FUSE:** بطء شديد في بطاقات SD/التخزين المشترك.
- **FEX:** ألعاب x86 كثيفة CPU قد تكون أبطأ بكثير.
- **سطح المكتب software rendering:** ليس GPU-accelerated.
- **الصوت relay:** كمون إضافي.

### 9.4 قيود التوافق

- anti-cheat kernel-level غير مدعوم.
- بعض الألعاب D3D12/UE5 قد تفشل.
- بعض مثبتات/DRM لا تعمل.
- عملاء المتاجر (Epic/Rockstar) قد يتعثرون تحت FEX.
- ألعاب 32-bit x86 قد تحتاج مسارًا خاصًا.
- VAC يتطلب عميل Steam x86-64 (تجريبي).

### 9.5 قيود أمنية

- proot ليس sandbox.
- نفس UID لكل الجلسة.
- Steam/الألعاب/المتصفحات غير موثوقة.
- `targetSdk 28`.
- صلاحيات واسعة.
- runtime من GitHub بلا توقيع (SHA-256 فقط).
- شيفرة أصلية مسبقة البناء.

### 9.6 قيود المنتج

- beta سريع التغير.
- 376 عنصرًا مفتوحًا.
- أعطال جهازية.
- bus factor منخفض.
- الاعتماد على Valve (قد تتغير ARM64 packages).
- ليست على Google Play.

### 9.7 قيود قانونية/ترخيص

- GPL-3.0 يفرض نشر المصدر للتعديلات على المكوّنات المشمولة.
- Steam/Proton ملك Valve، وليست مضمّنة في runtime.
- DirectAudio LGPL-2.1+.
- LSFG يتطلب نسخة قانونية من Lossless Scaling.
- إعادة توزيع APK معدّل قد تتعارض مع علامات Valve.

---

## 10. الأداء: الأرقام والاستدلال

### 10.1 أرقام المشروع

| القياس | القيمة | المصدر |
|---|---|---|
| تكلفة path syscall تحت proot | 15–60µs مقابل ~0.5µs native | `proot-performance.md` |
| سقف traps | 75–95k traps/s | نفسه |
| `stat` بعد fastpath | 25µs → 0.6µs | نفسه |
| `open` بعد fastpath | 25µs → 0.9µs | نفسه |
| missing-file lookup | 23µs → 1.6µs | نفسه |
| 8 threads stat | 89k/s → 3.6M/s | نفسه |
| فجوة zero-copy spike | ~20% مقابل X11 | `ZERO_COPY_SPIKE.md` |
| FPS spike (Adreno 750) | 596 vs 752، 336 vs 430 | نفسه |
| FUSE dir read | ~501ms مقابل ~2.3ms native | `thor-sd-storage-investigation.md` |

### 10.2 قراءة الأرقام

- **أثناء اللعب:** proot ليس العنق الرئيسي؛ GPU submits وfutex وread/write وmmap تعمل native.
- **أثناء التحميل/البدء:** path syscalls وexec وthread creation هي المشكلة؛ fastpath يخففها لكن لا يلغيها.
- **العرض:** نسخة كل إطار تكلفة ثابتة تزداد مع الدقة/معدل الإطارات. zero-copy قد يعيد ~20% في سيناريوهات معينة.
- **التخزين:** أكبر خطر على تجربة المستخدم عند استخدام SD/FUSE.
- **FEX:** التكلفة تعتمد على نسبة كود x86-64 الذي يُنفَّذ؛ ألعاب UE5 قد تكون ثقيلة.

### 10.3 ما لا تقوله الأرقام

- لا توجد قياسات مستقلة على أجهزة متعددة.
- لا توجد مقارنة منهجية مع Winlator/Steam Deck.
- لا توجد أرقام حرارة/بطارية.
- الأرقام مأخوذة من docs داخلية للمشروع، وليست مراجعة خارجية.

---

## 11. الأمان ونموذج الثقة

### 11.1 نموذج التهديد

التطبيق يشغّل:

- عميل Steam (شيفرة Valve).
- Proton/Wine (شيفرة Valve/Wine).
- ألعابًا تجارية وغير موثوقة.
- متصفحات وFlatpak apps.
- سكربتات من مستودعات/متاجر.
- runtime من GitHub.

### 11.2 نقاط قوة

- لا root.
- runtime يُتحقق بـSHA-256.
- Steam components تُتحقق بـSHA-256.
- FEX/Proton تُنزَّل من Steam/مصادر pinned.
- سجلات مشروحة قبل المشاركة.
- `targetSdk 28` ليس مثاليًا لكنه يقلل بعض قيود exec.

### 11.3 مخاطر

1. **proot ليس حدًا أمنيًا:** يترجم المسارات ويجيب syscalls، لكنه لا يعزل PID/mount/network.
2. **نفس UID:** أي عملية في الجلسة يمكنها التأثير على ملفات الجلسة.
3. **`MANAGE_EXTERNAL_STORAGE`:** وصول واسع لملفات المستخدم.
4. **`REQUEST_INSTALL_PACKAGES`:** sideload.
5. **الكاميرا/الميكروفون:** لتتبع اليد/الصوت؛ سطح هجوم إضافي.
6. **`targetSdk 28`:** لا يطبق سياسات Android الحديثة بالكامل.
7. **runtime غير موقّع:** SHA-256 يحمي من التلف وليس من استبدال الكتالوج.
8. **شيفرة أصلية مسبقة البناء:** يصعب تدقيقها.
9. **anti-cheat/DRM:** قد ترفض البيئة أو تطلب صلاحيات إضافية.

### 11.4 مقارنة مع chroot في DroidDesk

| البُعد | DroidDeck (proot) | DroidDesk chroot |
|---|---|---|
| root | لا | نعم |
| عزل | لا | لا (بلا namespaces) |
| ترجمة مسارات | نعم | لا |
| syscall filtering | نعم | لا |
| مخاطر الجهاز | متوسطة | عالية (root) |
| توافق | أعلى | أعلى داخل chroot |

**الخلاصة:** DroidDeck أقل خطرًا من chroot بلا namespaces، لكنه ليس بيئة عزل. لا تشغّله على جهاز يحتوي بيانات حساسة دون وعي.

---

## 12. الترخيص والامتثال

- **DroidDeck:** GPL-3.0.
- **Linux runtime:** GPL-3.0 (مبني على WinNative/Bannerlator).
- **Proton/Wine:** LGPL-2.1+ (Wine) مع مكوّنات Valve.
- **DirectAudio:** LGPL-2.1+.
- **FEX:** MIT.
- **Mesa/Turnip:** MIT.
- **gamescope:** BSD-2-Clause (Valve).
- **Steam/Proton:** ملك Valve، غير مضمّنة.
- **LSFG:** يتطلب Lossless Scaling.

**مخاطر الامتثال:**

- إذا عدّلت DroidDeck ووزّعت APK، يجب نشر المصدر المقابل.
- إذا أعدت توزيع runtime مع مكوّنات GPL، يجب الالتزام.
- استخدام علامة Steam/Valve قد يخضع لسياسات العلامات.
- شيفرة أصلية مسبقة البناء قد تكون بلا مصدر مطابق؛ يحتاج تدقيقًا مستقلًا.

---

## 13. الأدلة الميدانية

### 13.1 الإصدارات

- 0.5.0 هي الأولى التي تدعم VR/XR وعميل Steam x86-64 وEpic Linux.
- 83k+ تحميل في 3 أيام = ضغط كبير على المشروع.
- أربع نسخ APK (قياسي/antutu/ludashi/pubg) توحي بتحسينات/تجاوزات خاصة بكل تطبيق.

### 13.2 عينات issues

| # | العنوان | الدلالة |
|---|---|---|
| 1248 | إعدادات التوافق تُستبدل | إدارة إعدادات لكل لعبة هشة |
| 1247 | تأخر هائل للفأرة الخارجية | كمون الإدخال |
| 1246 | تنبيه أمني من Google بسبب Device ID | بصمة/سلوك متصفح داخلي |
| 1244 | عدم توافق Xiaomi Pad Mini | دعم أجهزة |
| 1242 | shader cache/تخزين L4D2 | تخزين/ذاكرة |
| 1241 | SCP:SL عالق على شاشة البداية | توافق Proton |
| 1240 | HKCU markers في redists | تثبيت متطلبات |
| 1239 | Rockstar Launcher يستهلك Quest | أداء/VR |
| 1233 | تشويه QAM على Z Fold 5 | عرض/compositor |
| 1232 | Borderlands 2 Linux/FEX لا يرى يد Xbox | إدخال/FEX |
| 218 | دعم PanVK Mali | طلب مجتمعي كبير (81 تعليقًا) |
| 165 | Bionic Vulkan داخل proot | طلب دعم GPU غير Adreno |

### 13.3 قراءة الأعطال

- **العرض:** تشويه QAM على جهاز معين = مشكلة compositor/scale.
- **الإدخال:** lag الفأرة = طبقات متعددة.
- **التخزين:** shader cache = ضغط على التخزين/FUSE.
- **التوافق:** SCP:SL/Rockstar = Wine/Proton/FEX.
- **الأجهزة:** Xiaomi Pad Mini/Xclipse = قيود دعم.

### 13.4 حدود الأدلة

- issues ليست عينة ممثلة؛ الشكاوى تظهر أكثر من النجاحات.
- لا أرقام أداء موحدة.
- بعض issues قديمة/مكررة.

---

## 14. المقارنة مع الأنظمة الأخرى

### 14.1 جدول المقارنة

| البُعد | DroidDeck | Winlator | Mobox/Termux-Box | Termux:X11 + proot | DroidDesk | Steam Deck | Android native |
|---|---|---|---|---|---|---|---|
| الهدف | SteamOS على Android | Wine على Android | Wine في Termux | سطح مكتب Linux | سطح مكتب Linux | جهاز ألعاب x86 | ألعاب Android |
| Steam client | ARM64 رسمي | لا (اختياري) | لا | لا | لا | native | لا |
| Proton | ARM64 رسمي | Wine fork/Box64 (وبعض النسخ FEX) | Wine/Box64 | لا | لا | native | لا |
| FEX | نعم (x86 Linux/Windows) | Box64/FEX | Box64/FEX | حسب الحزمة | لا | لا | لا |
| GPU | Turnip KGSL | Turnip/VirGL | Turnip/VirGL | Turnip/Zink | Turnip/Zink/llvmpipe | AMD native | native |
| Display | Wayland compositor + gamescope | XServer | X11 | X11 | X11/Flutter | gamescope | SurfaceFlinger |
| root | لا | لا | لا | لا | اختياري | N/A | N/A |
| عزل | لا | لا | لا | لا | لا/root | N/A | sandbox |
| سطح مكتب | KDE (software) | محدود | XFCE | كامل | كامل | KDE | لا |
| التوافق | عالي لألعاب Steam | متوسط | متوسط | Linux apps | Linux apps | ممتاز | Android فقط |
| الأداء | جيد على Adreno | متوسط | متوسط | منخفض-متوسط | منخفض | ممتاز | ممتاز |
| الأجهزة | Adreno 730+ | أوسع | أوسع | أوسع | أوسع | Steam Deck | Android |
| النضج | beta | ناضج نسبيًا | ناضج | ناضج | beta | منتج | منتج |

### 14.2 مقابل Winlator

- **DroidDeck:** Steam/Proton رسمي، تكامل أفضل، لكن Adreno فقط وbeta.
- **Winlator:** مرونة أوسع، Wine fork، لا Steam Big Picture رسمي، إعداد يدوي أكثر.
- **الحكم:** للألعاب Steam على Adreno، DroidDeck أقوى. للأجهزة غير المدعومة/التخصيص، Winlator أflexible.

### 14.3 مقابل Mobox

- Mobox يعتمد Termux + Box64/Wine + X11؛ أقرب إلى «حل شعبي» من «منتج».
- DroidDeck يخفي التعقيد ويوفر Steam/Proton رسمي.
- Mobox قد يكون أخف على أجهزة ضعيفة/غير Adreno.

### 14.4 مقابل Termux:X11 + proot

- Termux:X11 سطح مكتب Linux كامل، لكنه لا يوفر Steam/Proton/gamescope.
- DroidDeck أفضل للألعاب، وأسوأ للحرية وسطح المكتب (software rendering).
- DroidDesk/Termux:X11 مناسب لمن يريد بيئة Linux عامة.

### 14.5 مقابل DroidDesk

- DroidDesk يركز على سطح المكتب (XFCE) وثلاثة أوضاع (native/chroot/proot).
- chroot في DroidDesk يفرض llvmpipe؛ DroidDeck يستخدم GPU native.
- DroidDeck لا يهدف إلى سطح مكتب إنتاجي؛ DroidDesk لا يهدف إلى ألعاب Steam.
- **لا يستبدل أحدهما الآخر.**

### 14.6 مقابل Steam Deck

- Steam Deck: x86 native، Proton native، دعم رسمي، أداء/بطارية أفضل.
- DroidDeck: أجهزة Android، ARM64، FEX، توافق أقل.
- **الحكم:** Steam Deck أفضل لتشغيل ألعاب PC. DroidDeck أفضل إن كانت لديك يد Android قوية ولا تريد جهازًا إضافيًا.

### 14.7 مقابل Android native/cloud

- ألعاب Android الأصلية أسرع وأخف.
- cloud gaming يتفوق في الألعاب الثقيلة إن توفرت الشبكة.
- DroidDeck يقدم مكتبة PC محلية؛ مناسب للطائرة/بدون شبكة.

---

## 15. المقارنة مع البيئة الحالية

### 15.1 ما رصدناه

```text
Linux 5.4.0-faked ... andronix@fakeandroid
Ubuntu 22.04.5 LTS
aarch64
Display: :1
/dev/kgsl-3d0: crw-rw-rw- kaggle kaggle 478,0
/dev/dri: card0 / renderD128 (Permission denied)
steam: not installed
wine: not installed
vulkaninfo: not found
glxinfo: not found
```

[استنتاج]

- البيئة تبدو proot/حاوية فوق نواة Android (مع `/proc/version` مُموّه)، وليست Android framework كاملًا.
- وجود `/dev/kgsl-3d0` يشير إلى إمكانية الوصول لـGPU، لكن `/dev/dri` ممنوع، ولا توجد أدوات Vulkan للتحقق.
- لا Steam/Wine، لذا لا مسار ألعاب PC جاهز.

### 15.2 هل يمكن تشغيل DroidDeck هنا؟

**لا، كما هو.** الأسباب:

1. DroidDeck APK يحتاج Android framework (`Activity`, `Service`, `SurfaceView`, `SurfaceControl`, `AudioTrack`).
2. يحتاج SurfaceFlinger/Android display pipeline، وليس X11/Wayland host فقط.
3. يحتاج UID تطبيق وصلاحيات Android (storage، foreground service).
4. يحتاج Adreno KGSL + Turnip host، و`/dev/dri` هنا ممنوع.
5. يحتاج Android 14+ developer setting.

### 15.3 ماذا يعني ذلك؟

- DroidDeck ليس بديلًا قابلًا للتثبيت على هذه البيئة.
- لكن **معماره قابل للاستنساخ** على Linux ARM64: rootfs + Proton ARM64 + FEX + Turnip + gamescope/compositor.
- إذا كان الهدف تحويل هذه البيئة إلى «deck»، فالمسار الأقرب هو:
  1. التحقق من وصول KGSL/Vulkan.
  2. تثبيت Turnip/Mesa ARM64.
  3. تثبيت Steam ARM64 + Proton ARM64 (إن أمكن قانونيًا/عمليًا).
  4. تشغيل gamescope/Wayland compositor.
  5. إدارة الإدخال/الصوت.
- هذا مشروع بحد ذاته، وليس تشغيل APK.

### 15.4 الفرق الجوهري

| البُعد | DroidDeck | البيئة الحالية |
|---|---|---|
| المضيف | Android app | Ubuntu proot/حاوية |
| العرض | SurfaceFlinger + compositor | X11/Wayland (:1) |
| GPU | KGSL/Turnip | غير متاح/غير مؤكد |
| Steam | ARM64 رسمي | غير مثبت |
| الألعاب | Proton/FEX | لا مسار |
| العزل | UID تطبيق | proot/حاوية |
| الجاهزية | turnkey | يدوي بالكامل |

---

## 16. متى تختاره؟

### 16.1 اختر DroidDeck إذا

- لديك Adreno 730+ أو 8xx.
- تريد مكتبة Steam وBig Picture بأقل إعداد.
- تقبل beta وأعطالًا جهازية.
- لا تحتاج عزلًا أمنيًا.
- تريد سطح مكتب Linux أحيانًا.
- تريد VR/XR/نظارات.

### 16.2 لا تختره إذا

- لديك Mali/Xclipse/PowerVR/Adreno 710.
- تحتاج استقرارًا إنتاجيًا.
- تحتاج عزلًا/أمانًا.
- تلعب ألعابًا competitive بـanti-cheat kernel-level.
- تريد أداء x86 native.
- تحتاج سطح مكتب GPU-accelerated.

### 16.3 القرار المختصر

| الحالة | الخيار |
|---|---|
| ألعاب Steam على Adreno حديث | DroidDeck |
| ألعاب Steam على Mali | Winlator/Mobox/cloud |
| سطح مكتب Linux عام | DroidDesk/Termux:X11 |
| أقصى أداء لألعاب PC | Steam Deck/PC |
| ألعاب Android | native |
| عزل أمني | VM/جهاز منفصل |

---

## 17. أسئلة مفتوحة واختبارات مقترحة

### 17.1 أداء

- FPS/frame time في 3 ألعاب: D3D11، D3D12، Vulkan.
- مقارنة مع Winlator/Steam Deck على نفس اللعبة.
- قياس traps/s تحت حمل لعبة.
- قياس zero-copy prototype مقابل blit.
- قياس الحرارة/البطارية بعد 30 دقيقة.

### 17.2 توافق

- نسبة نجاح top 100 Proton games.
- anti-cheat: EAC/BattlEye/VAC.
- D3D12 على Adreno 6xx/7xx/8xx.
- 32-bit x86.
- Epic/Rockstar launchers.

### 17.3 أمان

- مراجعة proot + preloads.
- صلاحيات Android الفعلية.
- سلوك المتصفح داخل 0.5.0.
- provenance للشيفرة الأصلية.
- مراجعة GPL.

### 17.4 منتج

- Android 14/15/16 FGS وchild restrictions.
- تحديثات Valve ARM64.
- دعم الأجهزة الجديدة.
- استقرار الإعدادات لكل لعبة.

---

## 18. الخلاصة

DroidDeck ليس «محاكيًا» بالمعنى الثقيل. هو **تغليف هندسي ذكي** لسلسلة Valve الرسمية:

```text
Steam ARM64 native
  + Proton ARM64 native
  + FEX فقط لتعليمات x86-64
  + Turnip GPU native
  + gamescope/compositor
  + proot للتوافق
  = تجربة SteamOS على Android
```

**أقوى ما فيه:**

- استخدام Valve الرسمي بدل Wine fork.
- GPU native.
- تكامل Steam Input/الصوت/العرض.
- توثيق هندسي ممتاز.
- تجربة turnkey.

**أضعف ما فيه:**

- Adreno فقط.
- beta وأعطال جهازية.
- نسخة إطار في compositor.
- proot/FUSE/FEX تكاليف.
- أمان بلا عزل.
- اعتماد على Valve.

**الحكم:**

- **للاعب على Adreno 730+/8xx:** أحد أفضل الخيارات المتاحة اليوم لتشغيل مكتبة Steam على Android.
- **للمهندس/الباحث:** مرجع معماري ممتاز لكيفية بناء طبقة ألعاب ARM64 فوق Android.
- **للإنتاج/الأمان:** ليس جاهزًا.
- **للبيئة الحالية:** غير قابل للتشغيل كما هو؛ لكنه blueprint قابل للاستنساخ.

---

## Appendix A — خريطة الملفات

| المسار | الدور |
|---|---|
| `app/src/main/java/com/droiddeck/launcher/session/SessionService.kt` | قلب الجلسة (1,891 سطرًا) |
| `.../session/SessionPrefs.kt` | إعدادات لكل وضع/لعبة |
| `.../session/ComponentsManager.kt` | إدارة FEX/DXVK/VKD3D |
| `.../runtime/LinuxRuntime.java` | بناء أمر proot |
| `.../runtime/LinuxRuntimeInstaller.java` | تنزيل/فك runtime |
| `.../runtime/ProotFastPath.kt` | fastpath |
| `.../gpu/LinuxVulkanDriver.java` | ICD للضيف |
| `.../gpu/TurnipDriver.java` | Turnip |
| `.../wayland/WaylandCompositor.java` | JNI compositor |
| `.../wayland/CompositorHost.kt` | دورة حياة السطح |
| `app/src/main/cpp/waylandcomp/src/compositor.c` | compositor (3,306 سطرًا) |
| `app/src/main/cpp/waylandcomp/src/vk_present.c` | عرض Vulkan (2,139 سطرًا) |
| `app/src/main/cpp/waylandcomp/src/ahb_swapchain.c` | AHB swapchain |
| `app/src/main/cpp/adrenotools/` | تحميل تعريفات Adreno |
| `.../audio/PulseAudioComponent.java` | PulseAudio |
| `.../audio/DirectAudioRelayComponent.java` | relay الصوت |
| `.../input/*` | الإدخال/اللمس/اليد |
| `tools/linuxfs/overlay/usr/local/bin/droiddeck-session` | سكربت الجلسة (1,259 سطرًا) |
| `.../droiddeck-steam-install` | تنزيل Steam ARM64 |
| `.../steam-compatibility` | تسجيل Proton/FEX |
| `.../droiddeck-proton-run` | تشغيل لعبة عبر Proton |
| `.../droiddeck-fex` | تجهيز FEX (846 سطرًا) |
| `.../droiddeck-game-env` | متغيرات اللعبة |
| `.../droiddeck-steam-x64` | عميل x86-64 |
| `tools/linuxfs/preload/*.c` | shims |
| `tools/proot/fastpath/` | fastpath prototype |
| `tools/desktop-kde/` | KDE Plasma |
| `docs/development/x86-64-steam-client.md` | أدق وثيقة تقنية |
| `docs/development/proot-performance.md` | أرقام proot |
| `docs/development/ZERO_COPY_SPIKE.md` | zero-copy |
| `docs/development/thor-sd-storage-investigation.md` | FUSE/التخزين |

## Appendix B — أوامر التحقق

```bash
# بيانات المستودع
curl -s https://api.github.com/repos/Droid-Deck/DroidDeck | jq '{stars:.stargazers_count,forks:.forks_count,issues:.open_issues_count}'

# كتالوج runtime
curl -s https://raw.githubusercontent.com/Droid-Deck/DroidDeck-Components/main/linuxfs.json | jq '{version,url,sha256,size}'

# التحقق من runtime بعد التنزيل
sha256sum linuxfs.tar.zst

# فحص APK
aapt dump badging DroidDeck-0.5.0.apk | head -20

# فحص الصلاحيات
aapt dump permissions DroidDeck-0.5.0.apk

# عدّ الشيفرة
find app/src/main/java -name '*.kt' | wc -l
find app/src/main/cpp -type f | wc -l

# بحث سريع (بدون rg)
grep -rn "BL_VK_DRIVER\|FEX\|proot" app/src/main/java | head
```

## Appendix C — معجم

| المصطلح | المعنى |
|---|---|
| ARM64EC | ABI من Microsoft يسمح بخلط كود ARM64 مع x86-64 في نفس العملية |
| FEX | محاكي/مترجم تعليمات x86-64 إلى ARM64 في مساحة المستخدم |
| DXVK | ترجمة Direct3D 9/10/11 إلى Vulkan |
| VKD3D-Proton | ترجمة Direct3D 12 إلى Vulkan |
| Turnip | تعريف Vulkan مفتوح لـAdreno من Mesa |
| KGSL | تعريف GPU من Qualcomm في نواة Android |
| proot | أداة تغيير rootfs وترجمة مسارات بدون root |
| gamescope | compositor ألعاب من Valve |
| dma-buf | آلية مشاركة ذاكرة GPU بين العمليات |
| AHB | `AHardwareBuffer` في Android |
| proot fastpath | معالجة syscalls داخل العملية بدل trap إلى proot |
| pressure-vessel | حاوية Steam Linux Runtime |
| VAC | Valve Anti-Cheat |
| EAC | Easy Anti-Cheat |
| LSFG | Lossless Scaling Frame Generation |

---

**نهاية التقرير.**
