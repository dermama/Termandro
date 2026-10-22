# خطة الدمج الشاملة — DeckOS

> **الحالة:** مرجع معماري — التنفيذ يتبع `lean-core-plan.md`.  
> **التاريخ:** 2026-10-21.  
> **النطاق:** دمج Termux + Termux:X11 + بيئة Ubuntu 22.04 الحالية + مزايا DroidDesk + DroidDeck + Shizuku في منتج واحد.  
> **اسم مؤقت:** DeckOS؛ الاسم النهائي قرار لاحق.

> **أولوية التنفيذ:** ابدأ من `lean-core-plan.md`: تطبيق Core خفيف على Termux Native + X11 + طرفية + Openbox، ثم أضف Ubuntu/DroidDeck/Shizuku كحزم. ترتيب المحركات أدناه مرجعي، وليس ترتيب البناء.

---

## 0. الملخص التنفيذي

الهدف ليس «دمج APK‏ات» — فهذا مستحيل عمليًا بسبب التوقيعات و`sharedUserId` واختلاف UID‏ات والمحركات. الهدف هو بناء **تطبيق واحد** بواجهة موحّدة، يشغّل **محركات منفصلة** خلف عقود واضحة:

1. **Desktop Engine — Termux Native (Core):** مسار DroidDesk الأصلي (bootstrap + X11 مضمّن + Turnip/Zink + AAudio) لسطح مكتب أخف وأسرع؛ هذا هو الأساس.
2. **Desktop Engine — Ubuntu Workspace (Pack):** بيئتك الحالية Ubuntu 22.04.5/XFCE، مع إصلاحات P0 وتقليم، للمتوافقية مع glibc؛ حزمة اختيارية لا أساس.
3. **Game Engine — DroidDeck:** Arch Linux ARM + proot + Steam + Proton/FEX + gamescope، لوضع الألعاب.
4. **Phone Bridge — Shizuku:** صلاحيات ADB shell اختيارية لتكامل الهاتف، دون root.
5. **Unified Shell:** واجهة Kotlin/Compose، مدير جلسات، طرفية، مشغّل تطبيقات، موجّه تشغيل ذكي، وإعدادات وأمان.

**القرارات الحاكمة:**

- **تطبيق واحد في v1**، لكن ليس APK واحدًا مدموجًا من مشاريع جاهزة؛ الدمج على مستوى المصدر والخدمات.
- **X11 لسطح المكتب، Wayland/gamescope للألعاب**، وجلسة ثقيلة واحدة في كل مرة.
- **لا root افتراضيًا**؛ Shizuku اختياري؛ chroot/root وضع متقدم صريح.
- **توقيع خاص بنا**، manifests موقّعة، SHA-256، ولا testkey.
- **لا ادعاءات أداء قبل القياس**؛ كل رقم في هذه الخطة هدف قابل للاختبار، لا وعد.

**النتيجة المستهدفة:** تطبيق واحد يفتح سطح مكتب Linux وطرفية معًا، يشغّل تطبيقاتك الحالية، يوجّه الألعاب والبرامج إلى المحرك الأنسب، ويتكامل مع الهاتف عبر Shizuku — بثقل أقل وأداء أعلى واستقرار يمكن قياسه.

---

## 1. الهدف والنطاق

### 1.1 الهدف

بناء تجربة سطح مكتب ذكي على Android، بمستوى منتج لا «نموذج تجريبي»:

- سطح مكتب Linux كامل مع طرفية في الوقت نفسه.
- تشغيل تطبيقات Linux الحالية (glibc) وتطبيقات Termux (bionic) وتطبيقات Windows/x86 عبر المحرك المناسب.
- مكتبة ألعاب Steam/Proton/FEX بوضع مخصص للأداء.
- تكامل هاتف آمن عبر Shizuku، دون كسر sandbox‏Android أو المطالبة بـroot.
- واجهة عربية/RTL أصلية، وإدارة موارد وطاقة وحرارة.
- تحديثات موقّعة، استرجاع عند الفشل، وشفافية في اختيار المحرك.

### 1.2 تجربة المستخدم المستهدفة

**عند الفتح:** شاشة رئيسية ببطاقات: سطح المكتب، الطرفية، الألعاب، تطبيقات Android، الهاتف، الإعدادات.

**عند فتح سطح المكتب:**  
`SurfaceView` لخادم X11 + لوحة طرفية قابلة للسحب/التقسيم. يمكنك فتح الطرفية فوق سطح المكتب أو بجانبه، مع تركيز لوحة مفاتيح واضح.

**عند فتح لعبة:**  
إيقاف جلسة سطح المكتب مؤقتًا، تفريغ الذاكرة، بدء جلسة DroidDeck، عرض اللعبة عبر gamescope، إدخال يد/لمس/فأرة، وشريط أداء. عند الخروج: استعادة سطح المكتب.

**عند الحاجة لصلاحية هاتف:**  
طلب صريح لكل قدرة (تثبيت حزمة، التقاط شاشة، إدخال، إعدادات)، مع تفسير وموافقة وسجل تدقيق. إن لم يكن Shizuku متاحًا: تعطيل القدرة، لا تصعيد صامت.

### 1.3 داخل النطاق

- تطبيق Android واحد بواجهة موحّدة.
- محرّك سطح مكتب Ubuntu 22.04 (البيئة الحالية).
- محرّك سطح مكتب Termux Native (DroidDesk-style).
- محرّك ألعاب DroidDeck.
- جسر Shizuku.
- موجّه تشغيل (Runtime Router) وملفات تعريف تطبيقات/ألعاب.
- عرض X11 مضمّن، طرفية، صوت، إدخال، حافظة، تخزين.
- تحديثات موقّعة، manifests، استرجاع.
- قياس أداء وأمان وتوافق.

### 1.4 خارج النطاق (v1)

- استبدال Android أو بناء ROM.
- تجاوز anti-cheat على مستوى kernel.
- تشغيل محركين ثقيلين في الوقت نفسه (Ubuntu + لعبة مثلًا).
- نشر على Google Play مع `targetSdk 28`.
- دعم كل معالجات GPU؛ Adreno أولًا.
- تضمين Steam أو Proton أو ألعاب داخل APK.
- محاكاة Windows كاملة أو VM.
- VR/XR في v1 (لاحقًا).

---

## 2. القيود الحاكمة

| القيد | الأثر | القرار المعماري |
|---|---|---|
| Android sandbox + `targetSdk 28` | تنفيذ binaries من بيانات التطبيق يتطلب target قديم؛ Play غير ممكن | sideload أولًا؛ هدف `minSdk 28`/`targetSdk 28`؛ لا Play في v1 |
| توقيعات و`sharedUserId` مختلفة | لا يمكن دمج APK‏ات Termux/X11/DroidDesk/DroidDeck كما هي | دمج مصدر/خدمات؛ توقيع واحد؛ لا testkey |
| GPL-3.0 لمعظم المشاريع | أي توزيع يتطلب مصدرًا مقابلًا وتراخيص | بوابة compliance في CI؛ نشر مصدر التعديلات |
| DroidDeck: Adreno فقط | Mali/Xclipse غير مدعومة في المحرك | Adreno 730/750 أولًا؛ fallback واضح لغير المدعوم |
| Termux 4 KB libs | قد يفشل على أجهزة 16 KB/Android 16 | إعادة بناء `libtermux`/bootstrap بـ16 KB في Phase 2 |
| ذاكرة/حرارة محدودتان | FEX + GPU + proot + XFCE = ضغط | محرك ثقيل واحد؛ preflight؛ حدود؛ تبريد/طاقة |
| proot/chroot ليسا sandbox | كود غير موثوق قد يصل إلى بيانات/أجهزة | لا `/data` افتراضيًا؛ تحذير؛ صلاحيات دنيا |
| Shizuku ليس root | لا يمنح كل الصلاحيات | قدرات محددة بموافقة؛ fallback؛ لا اعتماد كلي |
| لا قياسات أداء بعد | لا يمكن الجزم بأفضلية محرك | Phase 0 تبني خط أساس؛ بوابات قياس |
| Android 14+ child process restrictions | قد تُقتل عمليات proot | إعداد واضح؛ foreground service؛ اختبار Android 14/15/16 |

---

## 3. القرارات المعمارية (ADRs)

### ADR-001 — منتج واحد، محركات منفصلة
**القرار:** تطبيق واحد بواجهة موحّدة، لكن المحركات تعمل في عمليات/خدمات منفصلة خلف عقود `RuntimeProvider`.
**لماذا:** عزل الأعطال، استبدال محرك دون كسر الواجهة، وتجنّب مستنقع دمج الشيفرة.
**الأثر:** AIDL/واجهات Kotlin، حدود واضحة، اختبارات عقود.

### ADR-002 — Kotlin/Compose للواجهة
**القرار:** واجهة أصلية Kotlin + Jetpack Compose؛ لا ندمج Flutter runtime في v1.
**لماذا:** تقليل الوزن والذاكرة، تكامل مباشر مع `SurfaceView`/`SurfaceControl`، وقت بدء أقل.
**الأثر:** لا نعيد استخدام واجهة DroidDesk Flutter؛ نعيد استخدام خدماته ومحركه فقط.

### ADR-003 — محرّكا سطح مكتب
**القرار:** Ubuntu 22.04 proot للمتوافقية مع glibc والبيئة الحالية، وTermux Native (bionic) للأداء/الخفة. الموجّه يختار حسب ABI والاعتماديات.
**لماذا:** لا يمكن لمحرك واحد أن يغطي glibc وbionic وGPU وبيئتك الحالية دون مقايضات.
**الأثر:** ملفات تعريف واضحة، رسالة للمستخدم تفسر الاختيار.

### ADR-004 — X11 أولًا، Wayland للألعاب
**القرار:** خادم X11 مضمّن لسطح المكتب؛ Wayland/gamescope لمحرك الألعاب؛ جلسة عرض واحدة نشطة.
**لماذا:** X11 أنضج لتطبيقات سطح المكتب الحالية؛ gamescope أفضل للألعاب. تشغيل compositorين معًا يسبب تعارض Surface/إدخال/صوت.
**الأثر:** `DisplayRouter` + تبديل جلسة؛ تقارب لاحق نحو Wayland/Xwayland.

### ADR-005 — Shizuku جسر صلاحيات، لا محرك
**القرار:** Shizuku اختياري عبر `UserService` وقدرات محددة؛ لا يُستخدم لتشغيل Linux.
**لماذا:** Shizuku يعمل كـshell UID 2000، لا root، ولا يملك وصولًا لبيانات التطبيق.
**الأثر:** واجهة موافقات، سجل تدقيق، fail-closed.

### ADR-006 — توقيع وسلسلة توريد خاصة
**القرار:** مفتاح إصدار خاص، manifests موقّعة، SHA-256 لكل أصل، rollback، لا testkey.
**لماذا:** DroidDeck/DroidDesk/Termux تعتمد testkeys أو checksums ناقصة؛ لا نكرر ذلك.
**الأثر:** تحديثات موقّعة، رفض الحزم غير الموثوقة.

### ADR-007 — root/chroot اختياري
**القرار:** الوضع الافتراضي non-root. chroot/root وضع متقدم صريح، بتحذير، وبدون `/data`.
**لماذا:** chroot ليس حدًا أمنيًا، وroot داخل الجلسة يزيد سطح الهجوم.
**الأثر:** مساران في Desktop Engine؛ اختبارات أمان منفصلة.

### ADR-008 — إعادة استخدام X11/Terminal
**القرار:** نعيد استخدام `libXlorie`/`LorieView` (DroidDesk) أو مكافئ Termux:X11، و`terminal-emulator`/`terminal-view` من Termux، مع إسناد GPL.
**لماذا:** بناء خادم X وطرفية من الصفر مضيعة وقت ومخاطر.
**الأثر:** دمج مصدر، لا APK؛ اختبار توافق الإصدارات.

### ADR-009 — DroidDeck محرك ألعاب
**القرار:** نعيد استخدام runtime/Steam/Proton/FEX/compositor من DroidDeck، لا نعيد كتابتها.
**لماذا:** هذه أصعب طبقة وأكثرها تخصصًا.
**الأثر:** fork متعقّب، تحديثات pinned، patches واضحة، compliance.

### ADR-010 — موجّه تشغيل قائم على الملفات والتعريفات
**القرار:** تصنيف binary + ABI + اعتماديات + ملف تعريف + فحص موارد، ثم اختيار محرك مع تفسير.
**لماذا:** «ذكي» يعني قابل للتفسير والتصحيح، لا تخمينًا غامضًا.
**الأثر:** `AppProfile`، `RuntimeProfile`، سجل نتائج، إمكانية override.

### ADR-011 — محرّكات في عمليات منفصلة
**القرار:** `:desktop`, `:x11`, `:game`, `:phone`، مع `kill-on-exit` ومجموعات عمليات.
**لماذا:** crash محرك لا يقتل الواجهة؛ تنظيف موارد مضمون.
**الأثر:** AIDL، health checks، إعادة تشغيل تلقائية.

### ADR-012 — تخزين منفصل + مساحة مشتركة
**القرار:** rootfs لكل محرك، `/workspace` مشتركة، وصول `/sdcard` عبر SAF/صلاحية صريحة؛ لا `/data`.
**لماذا:** منع تسرب بيانات التطبيقات، وتسهيل النسخ الاحتياطي.
**الأثر:** `StorageBroker`، مسارات موحّدة، ترحيل البيانات.

---

## 4. المعمارية المستهدفة

### 4.1 المخطط العام

```text
┌─────────────────────────────────────────────────────────────┐
│                    Unified Shell (Compose)                  │
│  Home · Desktop · Terminal · Games · Phone · Settings       │
├─────────────────────────────────────────────────────────────┤
│                    Session Orchestrator                     │
│  Runtime Router · Resource Manager · Recovery · Profiles    │
├───────────────┬────────────────┬────────────────────────────┤
│ Desktop       │ Game           │ Phone Bridge               │
│ Engine        │ Engine         │ (Shizuku)                  │
│               │                │                            │
│ Ubuntu proot  │ DroidDeck      │ UserService + capabilities │
│ Termux native │ Arch proot     │ Android APIs               │
├───────────────┴────────────────┴────────────────────────────┤
│                    Shared Services                          │
│ DisplayRouter · AudioRouter · InputRouter · StorageBroker   │
│ Clipboard · Notifications · ManifestVerifier · Telemetry    │
├─────────────────────────────────────────────────────────────┤
│              Android Host · SELinux · Linux Kernel          │
│                    GPU: KGSL / Turnip                       │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 الطبقات

**1) Shell**  
واجهة Compose، شاشة رئيسية، مشغّل تطبيقات، مكتبة ألعاب، إعدادات، معالج أول تشغيل، لوحة طرفية، HUD أداء، إدارة صلاحيات.

**2) Orchestrator**  
آلة حالة للجلسات، قفل «محرك ثقيل واحد»، preflight للذاكرة/القرص/الحرارة، بدء/إيقاف/استعادة، معالجة OOM/crash، جدولة أولويات.

**3) Engines**  
- `UbuntuWorkspaceProvider`: glibc، XFCE، البيئة الحالية.
- `TermuxNativeProvider`: bionic، X11 مضمّن، Turnip/Zink، AAudio.
- `DroidDeckGameProvider`: Arch/proot/Steam/Proton/FEX/gamescope.
- `PhoneBridge`: Shizuku capabilities.

**4) Shared Services**  
العرض، الصوت، الإدخال، التخزين، الحافظة، الإشعارات، التحقق منManifests، القياس.

**5) Android/Guest**  
خدمات Android الأمامية، SELinux، kernel، KGSL، Turnip، proot.

### 4.3 نموذج العمليات

```text
com.deckos.app                 (main / UI)
 ├── :desktop                  (Ubuntu/Termux session supervisor)
 ├── :x11                      (X11 server / Lorie)
 ├── :game                     (DroidDeck session + compositor)
 └── :phone                    (Shizuku UserService bridge)
```

- المحرّكات child processes داخل proot، مع `--kill-on-exit`.
- `:x11` منفصل عن الواجهة لتقليل أثر crash الخادم.
- `:game` يملك compositor والإدخال والصوت الخاص باللعبة.
- أي محرك ثقيل يوقف الآخر قبل البدء؛ لا تداخل.

### 4.4 عقود المحركات

```kotlin
interface RuntimeProvider {
    suspend fun preflight(profile: RuntimeProfile): PreflightResult
    suspend fun prepare(profile: RuntimeProfile): RuntimeHandle
    suspend fun start(session: SessionRequest): SessionHandle
    suspend fun stop(sessionId: String, reason: StopReason)
    fun health(sessionId: String): Health
}

interface DisplayBackend {
    fun attach(surface: Surface, mode: DisplayMode): DisplayHandle
    fun resize(width: Int, height: Int, dpi: Int)
    fun detach()
}

interface AudioBackend { fun route(sessionId: String, sink: AudioSink); fun stop() }
interface InputRouter { fun attach(sessionId: String, mode: InputMode); fun detach() }
interface PrivilegeBroker { suspend fun request(capability: Capability): Grant; suspend fun revoke(id: String) }
interface StorageBroker { fun workspace(engine: EngineId): Path; fun import(uri: Uri): Path }
```

**قاعدة:** لا محرك يصل مباشرة إلى UI أو إلى محرك آخر؛ كل شيء عبر Orchestrator/Shared Services.

### 4.5 نموذج البيانات

```text
RuntimeProfile
  id, engine, rootfs, abi, display, gpu, audio, mounts, env, limits

AppProfile
  id, name, executable, runtimeId, args, env, displayMode,
  inputMode, gpuOverride, compatFlags, savesPath, icon

Session
  id, appId, runtimeId, state, pid, displayId, audioId,
  startedAt, metrics, lastError

Capability
  gpu, x86, windows, root, shizuku, controller, hdr, externalDisplay
```

### 4.6 دورة حياة الجلسة

```text
Idle → Preflight → Preparing → Starting → Ready → Paused → Stopping → Cleanup → Idle
```

- **Preflight:** ذاكرة متاحة، مساحة، حرارة، GPU، تعارض محرك.
- **Starting:** تحميل rootfs/المتغيرات، فتح العرض، الصوت، الإدخال.
- **Ready:** health check + أول إطار.
- **Paused:** عند تبديل التطبيق/اللعبة؛ تجميد العمليات إن أمكن.
- **Cleanup:** إنهاء مجموعة العمليات، فك mounts، حذف sockets، استعادة سطح المكتب.

---

## 5. محركات التشغيل

### 5.1 Desktop Engine — Ubuntu Workspace (Pack)

> **لا تبدأ هنا.** هذه حزمة اختيارية بعد Lean Core، وليست محرك v0.1.

**الهدف:** الحفاظ على بيئتك الحالية Ubuntu 22.04.5 + XFCE مع كل ما تحتاجه، لكن كصورة نظيفة قابلة للتحديث.

**المكوّنات:**

- rootfs Ubuntu 22.04.5 مع `proot` والأدوات الأساسية.
- XFCE + dbus + Mesa/llvmpipe (أو Turnip إن ثبت).
- Chromium محدّث، خطوط عربية، أدوات إدخال/حافظة.
- Python، Node واحد، code-server، opencode، ADB، أدوات الوكلاء.
- PulseAudio عبر TCP أو AAudio (يُحدد بعد الاختبار).
- supervisor للخدمات (لا systemd).

**الاستخدام:** التطبيقات التي تحتاج glibc، Electron، Chromium، أدوات التطوير، والبيئة الحالية.

**التحسينات الإلزامية قبل الدمج:**

- إزالة `virgl` من `proot-fixes.sh`؛ استخدام `llvmpipe` أو Turnip فقط بعد التحقق.
- `PULSE_SERVER=tcp:127.0.0.1:4713` أو جسر AAudio، لا `unix:/run/pulse/native`.
- إزالة `GTK_IM_MODULE=ibus` أو تثبيت IBus فعليًا.
- إصلاح `sshd` أو تعطيله؛ لا محاولات FATAL عند كل إقلاع.
- تحديث Chromium؛ تقليم الحزم المكررة؛ Node واحد.
- تقليل binds: لا `/data` كاملًا؛ `/sdcard` عند الحاجة فقط.
- قياس الإقلاع والذاكرة بعد كل تغيير.

**ملاحظة:** هذه البيئة هي «Workspace» وليست المحرك الافتراضي لكل شيء؛ الموجّه يفضّل المحرك الأخف عندما يكون متوافقًا.

### 5.2 Desktop Engine — Termux Native (Core)

> **Lean Core يبدأ هنا.** يستخدم Openbox افتراضيًا وبدون proot؛ ملف XFCE mobile profile يصبح Comfort Pack. راجع `lean-core-plan.md` للتفاصيل الملزمة.

**الهدف:** سطح مكتب أخف وأسرع، بنفس نهج DroidDesk native.

**المكوّنات:**

- bootstrap مخصص/مُعاد توطينه، X11 مضمّن (`libXlorie` + `LorieView`).
- Openbox + Tint2 + PCManFM في Core؛ XFCE mobile profile حزمة Comfort.
- Turnip/Zink على Adreno، وllvmpipe fallback.
- PulseAudio + AAudio sink.
- أدوات Termux: `pkg`, `termux-api`, `adb`؛ `proot-distro` للتطبيقات الاختيارية فقط.

**استراتيجية الترحيل:**

- **2A:** إعادة استخدام محرك DroidDesk native و`LinuxRuntime.kt` و`socket_hook` كما هي، مع مراجعة أمنية.
- **2B:** استبدال `socket_hook` العالمي تدريجيًا بـbootstrap مبني لبادئة التطبيق، أو مسار proot أخف. لا نجعل الـhook هو الأساس الدائم إن أمكن.
- **2C:** بناء 16 KB نظيف لـ`libtermux`/bootstrap، واختبار Android 16.

**القيد:** bionic ≠ glibc؛ بعض التطبيقات الحالية ستبقى على Ubuntu. هذا مقبول ومقصود.

### 5.3 Game Engine — DroidDeck

> **التكامل الذكي مع DroidDesk موثق في `lean-core-plan.md` §6.3:** DroidDesk هو Desktop Host، وDroidDeck هو Game Engine، وDeckOS Shell يدير الجلسة الواحدة. اللعبة حزمة اختيارية وليست داخل Core.

**الهدف:** تشغيل ألعاب Steam/Windows/x86 بأفضل مسار متاح، دون إعادة بناء Proton.

**المكوّنات المُعاد استخدامها:**

- `SessionService`، `LinuxRuntimeInstaller`، `ProotFastPath`.
- `TurnipDriver`/`AdrenoTools`، `wayland` compositor، `audio` relay، `input`.
- `droiddeck-session`، `steam-compatibility`، `droiddeck-fex`، `droiddeck-proton-run`.
- Steam ARM64 client + Proton ARM64 + FEX + DXVK/VKD3D (تُنزّل وقت التشغيل).
- **DroidDeck-Proton** المخصص: مكوّن إضافي يجب تتبعه أمنيًا وقانونيًا؛ لا نعتبره «Proton الرسمي فقط».

**التكامل:**

- `GameSession` AIDL في `:game`.
- إيقاف/تجميد Desktop Engine، تفريغ الذاكرة، ثم بدء اللعبة.
- `SurfaceView`/compositor للعبة، إدخال يد/لمس/فأرة، صوت منخفض الكمون.
- مكتبة ألعاب، ملف تعريف لكل لعبة (Proton/FEX/DXVK/الدقة/حد الإطارات).
- HUD أداء وحرارة، واستعادة سطح المكتب عند الخروج.

**القيود المعلنة:**

- Adreno فقط في v1.
- anti-cheat kernel-level غير مدعوم.
- بعض ألعاب UE5/D3D12 قد تفشل.
- FUSE/SD قد يكون بطيئًا؛ نفضّل التخزين الداخلي أو exFAT المباشر.
- Steam/Proton ليست مضمّنة؛ تُنزّل من Valve وفق شروطها.

### 5.4 Phone Bridge — Shizuku

**الهدف:** صلاحيات هاتف مفيدة دون root، وبموافقة واضحة.

**القدرات المقترحة (v1):**

- تثبيت/إلغاء/تعطيل حزم Android.
- منح/سحب App Ops محددة.
- التقاط الشاشة/التسجيل (بموافقة).
- حقن لمس/مفاتيح محدود.
- فتح الإعدادات/إدارة الشبكة.
- قراءة معلومات البطارية/الحرارة/التخزين.
- أوامر shell ضمن allowlist، لا shell عام.

**التصميم:**

- `Shizuku.UserService` + AIDL، `android:exported=false`.
- التحقق من توقيع المتصل، وربط الخدمة بالتطبيق.
- موافقة لكل قدرة، سجل تدقيق، انتهاء صلاحية، إلغاء تلقائي.
- `PrivilegeBroker` يخفي التعقيد عن الواجهة.
- إن لم يتوفر Shizuku: تعطيل القدرة وشرح البديل (ADB يدوي/إعدادات).

**ممنوع:** استخدام Shizuku لتشغيل Linux، أو منح shell عام، أو تجاوز موافقة المستخدم.

### 5.5 Runtime Router

**التصنيف:**

```text
Android APK / Intent        → Host
ELF ARM64 + glibc           → Ubuntu Workspace
ELF ARM64 + bionic/Termux   → Termux Native
ELF x86-64                  → DroidDeck (FEX)
PE .exe                     → DroidDeck (Proton)
AppImage / .desktop         → Ubuntu Workspace
Steam AppID                 → DroidDeck
Flatpak                     → DroidDeck (Arch) أو Ubuntu
CLI                         → Terminal (Ubuntu/Termux)
```

**الخوارزمية:**

1. فحص magic/ELF/PE/AppImage/Steam manifest.
2. تحديد ABI والاعتماديات (`glibc`/`bionic`, `x86_64`, `arm64`).
3. ترشيح المحركات حسب القدرات.
4. تقييم التوافق ثم الأداء ثم الموارد.
5. فحص preflight (ذاكرة/قرص/حرارة/GPU).
6. بدء المحرك الأفضل، مع fallback.
7. تسجيل النتيجة؛ تعلم من التشغيل التالي.
8. إظهار سبب الاختيار وإتاحة override.

**قاعدة ذهبية:** لا «سحر» بلا تفسير. كل اختيار يعرض: المحرك، السبب، البديل، وتكلفة الموارد المتوقعة.

---

## 6. العرض والطرفية وتجربة الاستخدام

### 6.1 سطح المكتب + الطرفية معًا

- `DesktopActivity` يحتوي `SurfaceView` لـX11.
- لوحة طرفية Compose فوقه/جانبه، قابلة للسحب والتقسيم.
- الطرفية تستخدم PTY في نفس rootfs/namespace، مع `cwd` وبيئة الجلسة.
- تبديل التركيز: لمس الطرفية يفتح IME؛ لمس السطح يعيد التركيز لـX11.
- أزرار: «افتح طرفية هنا»، «اقتل العملية»، «نسخ/لصق»، «ملء الشاشة».
- لا يتم تغيير حجم X11 بعنف عند فتح اللوحة؛ إرسال resize بعد استقرار التخطيط.

### 6.2 الطرفية

- تبويبات + تقسيم أفقي/عمودي.
- تكامل shell: `cd`, `git`, `opencode`, `code-server`.
- حافظة موحّدة مع X11 وAndroid.
- خط عربي واضح، دعم RTL في النص، وليس في الأوامر.
- سجل أوامر محلي، اختياري، دون تسريب أسرار.

### 6.3 سطح المكتب

- XFCE mobile profile: لوحة علوية/سفلية، أيقونات كبيرة، DPI مناسب.
- نافذة واحدة/تعدد نوافذ داخل السطح؛ multi-window Android لاحقًا.
- مدير ملفات، متصفح، إعدادات، لقطة شاشة، تحكم نافذة.
- دعم لوحة مفاتيح خارجية، فأرة، S Pen (لاحقًا)، شاشة خارجية (Phase 5).

### 6.4 وضع الألعاب

- شاشة ألعاب: مكتبة، Steam Big Picture، إعدادات لكل لعبة.
- HUD: FPS، frame time، GPU/CPU، حرارة، بطارية.
- إدخال: Steam Input، يد، لمس، لوحة مفاتيح/فأرة.
- إيقاف/استئناف، حفظ تلقائي للبيانات إن أمكن.
- خروج آمن يعيد سطح المكتب.

### 6.5 الهاتف

- لوحة صلاحيات: ما تم منحه، ما ينتهي، ما يحتاج Shizuku.
- تطبيقات Android، تنبيهات، ملفات، شاشة، إدخال.
- «أرسل ملفًا إلى سطح المكتب» و«افتح رابطًا على الهاتف».
- سجل عمليات حساسة.

### 6.6 نظام التصميم

- Material 3 مع هوية خاصة، dark-first، تباين عالٍ.
- مكونات أداء: لا blur ثقيل على SurfaceView؛ glass على طبقات محدودة فقط.
- RTL كامل: اتجاه، أيقونات، محاذاة، أرقام.
- إتاحة: تكبير الخط، تباين، تنقل لوحة مفاتيح، تسميات قارئ الشاشة.
- الحركة: 150–250ms، احترام «تقليل الحركة».

---

## 7. دمج البيئة الحالية

### 7.1 مبدأ

لا نعيد بناء بيئتك من الصفر في v1؛ نُجمّدها، نُنظّفها، نُصلح أعطالها، ثم نغلّفها كصورة موقّعة قابلة للاستيراد.

### 7.2 خطوات الترحيل

1. **جرد:** الحزم، الخدمات، السكربتات، متغيرات البيئة، الملفات المهمة، الأسرار (تُستبعد).
2. **تثبيت خط أساس:** إقلاع، ذاكرة، قرص، تطبيقات، صوت، إدخال.
3. **إصلاح P0** (الجدول أدناه).
4. **تقليم:** caches، Node مكرر، مدراء ملفات مكررون، حزم غير مستخدمة.
5. **تغليف:** `workspace-manifest.json` + rootfs مضغوط + سكربتات + توقيع.
6. **استيراد:** أول تشغيل يتحقق، يفك، يهيئ المسارات، ويختبر الصوت/العرض.
7. **ترحيل بيانات المستخدم:** `/root` → `/home/deck`، مع احترام الملكية والمسارات.
8. **اختبار قبول:** 10 تطبيقات أساسية + طرفية + متصفح + صوت + عربية.

### 7.3 إصلاحات P0

| العطل | الحل | التحقق |
|---|---|---|
| `virgl` مفروض | إزالته من `proot-fixes.sh`؛ `llvmpipe` أو Turnip بعد الاختبار | `glxinfo -B` يعمل؛ لا أخطاء GL |
| PulseAudio لا يعمل | `PULSE_SERVER=tcp:127.0.0.1:4713` أو AAudio | صوت في Chromium/طرفية |
| IBus غير مثبت | إزالة `GTK_IM_MODULE` أو تثبيت IBus | إدخال عربي يعمل |
| `sshd` FATAL | إصلاح `/run/sshd`/المنفذ أو تعطيله | لا FATAL عند الإقلاع |
| Chromium قديم | تحديث أو استبدال | إصدار حديث؛ لا تحذيرات أمنية |
| Node مكرر | واحد فقط (nvm أو apt) | `which node`، `node -v` |
| `/data` مربوط | تقليل إلى `/sdcard`/SAF | لا وصول لبيانات تطبيقات أخرى |

### 7.4 ما نحافظ عليه

- XFCE وسطح المكتب.
- Chromium/المتصفح.
- الصوت.
- الطرفية.
- Python/Node/code-server/opencode.
- الخطوط العربية.
- ADB/Termux API.
- supervisor.
- سكربتات الوكلاء التي تعتمد عليها.

### 7.5 ما نؤجله

- systemd (غير فعّال).
- VNC/Xvfb إن لم يُستخدما.
- cron إن لم توجد مهام.
- حزم Qt/PyQt إن لم يستخدمها تطبيق.
- Playwright browsers إن لم تُستخدم.
- snapd/apparmor إن لم تُستخدم.

---

## 8. الأمان والامتثال

### 8.1 حدود الثقة

| الحد | الثقة | الضابط |
|---|---|---|
| Shell/Orchestrator | موثوق | توقيع، مراجعة، أقل صلاحيات |
| Engine rootfs | غير موثوق | checksums، توقيع، لا `/data` |
| تطبيقات Linux | غير موثوقة | تحذير، ملف تعريف، لا root افتراضي |
| DroidDeck runtime | غير موثوق | SHA-256 + manifest، pinned |
| Shizuku | موثوق جزئيًا | UserService، توقيع، موافقة، allowlist |
| root/chroot | خطر | وضع صريح، تحذير، عزل شبكي اختياري |

### 8.2 الضوابط الإلزامية

- مفتاح إصدار خاص؛ لا testkey؛ التحقق من التوقيع قبل التحديث.
- manifests موقّعة (Ed25519) + SHA-256 لكل أصل.
- `android:exported=false` لكل الخدمات الحساسة.
- منع cleartext؛ network security config.
- أسرار في Android Keystore؛ لا تسجيل للحافظة/IME.
- تحقق من المدخلات في كل command builder؛ لا string concatenation للأوامر.
- لا `/data` افتراضيًا؛ `MANAGE_EXTERNAL_STORAGE` بموافقة وتفسير.
- فحص dependency/CVE في CI؛ SBOM.
- فحص ساكن + fuzzing لـmanifests/parsers/command builder.
- اختبار اختراق قبل beta.

### 8.3 Shizuku

- الخدمة غير مصدّرة؛ التحقق من UID/التوقيع.
- كل قدرة لها وصف وموافقة وسجل.
- لا shell عام؛ allowlist + rate limit.
- إلغاء تلقائي عند إزالة Shizuku/إعادة التشغيل.
- لا استخدام لتشغيل Linux أو تعديل أنظمة Android الحساسة دون تفسير.

### 8.4 root/chroot

- غير افتراضي.
- تحذير واضح: «هذه الجلسة تمنح root داخل الحاوية، وليست sandbox».
- mounts محدودة؛ لا `/data`.
- شبكة معزولة اختياريًا.
- سجل عمليات.
- تعطيل عند عدم الحاجة.

### 8.5 الامتثال

- GPL-3.0: نشر المصدر المقابل، patches، build scripts، notices.
- Shizuku: Apache-2.0، إشعارات.
- Steam/Proton: لا تضمين؛ تنزيل من Valve؛ احترام العلامات.
- DroidDeck-Proton: مراجعة ترخيص وسلسلة توريد.
- Termux: عدم استخدام العلامة/الانتماء؛ اسم ولون مختلفان.
- بوابة CI: license check + SBOM + source archive.

---

## 9. الأداء والقياس

### 9.1 خط الأساس قبل أي تحسين

يجب قياس:

- زمن إقلاع shell.
- زمن أول إطار لسطح المكتب.
- زمن فتح الطرفية.
- استهلاك الذاكرة في الخمول.
- زمن فتح Chromium/تطبيق.
- أداء لعبة مرجعية (FPS/frame time).
- الحرارة/البطارية بعد 30 دقيقة.
- سلاسة الإدخال/التمرير.

### 9.2 أهداف مبدئية (تُعدل بعد القياس)

| المقياس | الهدف |
|---|---|
| بدء Shell بارد | P50 < 2.0s، P95 < 3.0s |
| سطح المكتب جاهز | P50 < 10s (Native)، < 15s (Ubuntu) |
| فتح الطرفية | < 300ms |
| UI jank | < 1% إطارات > 16ms |
| ذاكرة host خاملة | < 350MB |
| ذاكرة جلسة سطح المكتب | < 2.5GB قبل التطبيقات |
| ألعاب مرجعية | ≥55 FPS متوسط، 1% low ≥45 على 720p/900p |
| فرق عن DroidDeck مستقل | ≤10% |
| استقرار | >99.5% جلسات بلا crash |
| حرارة | <42°C بعد 30 دقيقة، دون throttle >20% |

### 9.3 ترتيب التحسين

1. إصلاح P0 (GL/صوت/إدخال).
2. محرك Native افتراضي حيث يناسب.
3. Turnip/Zink بدل llvmpipe حيث يعمل.
4. AAudio بدل relay إن أمكن.
5. تقليل نسخ الإطارات (zero-copy لاحقًا).
6. تخزين مباشر بدل FUSE.
7. تقليم الذاكرة/الخدمات/الخطوط.
8. جدولة CPU/GPU وحرارة.
9. prewarm/lazy load.
10. تقارب compositor.

### 9.4 أدوات القياس

- `perfetto` traces.
- `dumpsys gfxinfo`/`SurfaceFlinger`.
- `logcat` مع وسوم مخصصة.
- `simpleperf` إن توفر.
- سكربتات `bench/` داخل التطبيق/ADB.
- قياس يدوي: stopwatch، إدخال، حرارة.

---

## 10. خارطة الطريق المتزامنة

### 10.1 المسارات

```text
الأسبوع:   0   4   8   12  16  20  24  28  32  36  40  44  48  52  56
التأسيس    ███
الواجهة        █████████
سطح المكتب        ███████████
الألعاب                    ███████████
الهاتف                 ███████
الجودة/الأمان      █████████████████████████
```

**المعنى:** المراحل ليست waterfall. كل «مرحلة» بوابة تكامل، لكن المسارات تعمل بالتوازي بعقود مشتركة. لا ننتظر انتهاء سطح المكتب لبدء تصميم محرك الألعاب، ولا ننتظر الألعاب لإغلاق أمان Shizuku.

### 10.2 المراحل

| المرحلة | الأسابيع | الهدف | المخرجات | بوابة الخروج |
|---|---:|---|---|---|
| **0 — التأسيس** | 0–6 | تقليل عدم اليقين | Spec، ADRs، threat model، legal checklist، خط أساس، CI، توقيع، نموذج X11+طرفية | نموذج موقّع يعمل؛ خط أساس موثّق؛ قرارات مفتوحة محسومة |
| **1 — سطح مكتب موحّد** | 4–16 | تطبيق واحد يفتح Ubuntu + طرفية | Shell، Orchestrator، طرفية، X11، استيراد Workspace، إصلاح P0، صوت/إدخال/حافظة، مشغّل تطبيقات | سطح مكتب + طرفية معًا؛ 10 تطبيقات أساسية؛ لا P0؛ قياس أولي |
| **2 — سطح مكتب سريع** | 12–28 | أخف وأسرع | محرك Termux Native، Turnip/Zink، AAudio، bootstrap، 16 KB، Router، root اختياري | Native أسرع من Ubuntu في السيناريوهات المحددة؛ 30 تطبيقًا؛ استرجاع |
| **3 — وضع الألعاب** | 24–42 | ألعاب Steam/Proton | دمج DroidDeck، Steam/Proton/FEX، gamescope، إدخال، مكتبة، ملفات تعريف | 20 لعبة؛ ≥55 FPS؛ استئناف/خروج آمن؛ compliance |
| **4 — جسر الهاتف** | 30–44 | Shizuku آمن | UserService، قدرات، موافقات، تدقيق، تكامل Android | 10 قدرات؛ fail-closed؛ مراجعة أمان |
| **5 — التقارب** | 40–56+ | تجربة واحدة ناضجة | compositor موحّد، zero-copy، multi-window، AI مساعد، OTA، beta | تحسين نسخ/كمون؛ استقرار؛ beta عام |

### 10.3 تفاصيل المرحلة 0

- تثبيت نطاق المنتج وغير الأهداف.
- ADRs النهائية.
- threat model + بيانات/أسرار.
- مراجعة GPL/Steam/Termux.
- مصفوفة أجهزة: S24 Ultra (Adreno 750، Android 14)، Android 15/16، 16 KB، Mali للـfallback.
- خط أساس: Ubuntu الحالي، DroidDesk Native، DroidDeck (إن أمكن).
- هيكل المستودع، CI، توقيع، SBOM.
- نظام تصميم/RTL.
- نموذج أولي: SurfaceView + X11 + طرفية + إدخال.

### 10.4 تفاصيل المرحلة 1

- Shell + التنقل.
- Session Orchestrator + state machine.
- Terminal panel + PTY.
- X11 surface + LorieView + input.
- استيراد Ubuntu Workspace.
- إصلاح P0.
- Audio/Clipboard/Notifications.
- App launcher + profiles.
- Router v0.
- Shizuku اختياري لملفات/تطبيقات.
- Beta داخلية + قياس + مراجعة أمان.

### 10.5 تفاصيل المرحلة 2

- Termux Native engine.
- Turnip/Zink + AAudio.
- bootstrap/relocation + تقليل hook.
- 16 KB rebuild.
- Runtime Router v1 + compat DB.
- root/chroot اختياري.
- أداء/ذاكرة/حرارة.
- ترحيل التطبيقات من Ubuntu حيث يناسب.

### 10.6 تفاصيل المرحلة 3

- fork DroidDeck pinned.
- GameSession AIDL.
- Steam/Proton/FEX/compositor.
- Input/audio/display.
- Library/profiles/saves.
- Performance HUD/thermal.
- Legal/compliance.
- 20 لعبة اختبار.

### 10.7 تفاصيل المرحلة 4

- Shizuku UserService.
- Capabilities + consent + audit.
- Android app integration.
- Screen capture/input.
- Package management.
- Security review.

### 10.8 الفريق والجهد

| الفريق | MVP (Phase 0–1) | كامل (حتى Phase 4) |
|---|---:|---:|
| 1 مطور | 6–9 أشهر | 24–30 شهرًا |
| 3 مطورين | 3–4 أشهر | 12–15 شهرًا |
| 5 مطورين | 2–3 أشهر | 9–12 شهرًا |

**التخصصات:** Android/Kotlin، Linux/runtime، graphics/compositor، security/legal، QA/performance، UX.

**إذا كان العمل فرديًا:** لا تبدأ بـfork كامل لـDroidDeck أو compositor مخصص. ابدأ بـPhase 0–1، واستخدم DroidDeck/DroidDesk كتطبيقات خارجية عبر Intent/Shell، ثم اندمج تدريجيًا.

---

## 11. بنية المستودع المقترحة

```text
deck/
  app/                       # Android host, Compose
  core/
    session/                 # orchestrator, state machine
    router/                  # runtime router, profiles
    display/                 # X11/Wayland/surface
    audio/                   # AAudio/Pulse
    input/                   # touch/mouse/keyboard/controller
    storage/                 # workspace, SAF, mounts
    security/                # manifests, signing, policy
    telemetry/               # local metrics
  engines/
    desktop-ubuntu/          # proot rootfs, scripts
    desktop-termux/          # native bootstrap, X11
    game-droiddeck/          # pinned fork, patches
  bridge/
    shizuku/                 # UserService, capabilities
  runtime/
    manifests/               # signed catalogs
    scripts/                 # install/update/rollback
  bench/                     # benchmarks, traces
  docs/
    adr/
    threat-model/
    compliance/
  tasks/
    todo.md
```

**البناء:**

- Gradle modules، `dev`/`beta`/`stable`.
- توقيع release من keystore خارج المستودع.
- engine packs كأصول/وحدات قابلة للتحديث.
- CI: lint، unit، instrumented، NDK، SBOM، license، benchmark smoke.
- لا أسرار في Git.

---

## 12. معايير القبول

### 12.1 على مستوى المنتج

- [ ] تطبيق واحد بأيقونة واحدة يفتح Home.
- [ ] سطح مكتب + طرفية في الوقت نفسه دون تعارض إدخال.
- [ ] البيئة الحالية تعمل: XFCE، Chromium، صوت، عربية، Node/Python، opencode.
- [ ] Router يختار المحرك ويشرح السبب ويسمح بالتبديل.
- [ ] الألعاب تعمل عبر DroidDeck مع إدخال/صوت/أداء مستقر.
- [ ] Shizuku اختياري، بموافقات وسجل، ويفشل بأمان.
- [ ] لا testkey؛ manifests موقّعة؛ rollback يعمل.
- [ ] GPL/Steam/Shizuku compliance موثّق.
- [ ] أهداف الأداء المقاسة تتحقق أو تُوثّق الفجوة.
- [ ] RTL/عربية/إتاحة أساسية.
- [ ] لا P0 أمنية مفتوحة.

### 12.2 على مستوى المهمة

كل مهمة يجب أن تحتوي:

- وصفًا.
- acceptance criteria قابلة للاختبار.
- خطوة تحقق (tests/build/manual).
- dependencies.
- ملفات متوقعة.
- حجم S/M (لا XL).

---

## 13. المخاطر والمعالجة

| الخطر | الاحتمال | الأثر | المعالجة |
|---|---|---|---|
| `targetSdk 28` يمنع Play | مؤكد | متوسط | sideload؛ لا Play v1؛ مسار helper مستقبلي |
| GPL/Steam/Proton | متوسط | عالٍ | مراجعة قانونية؛ مصدر مقابل؛ لا تضمين |
| DroidDeck سريع التغير | عالٍ | متوسط | pin version؛ fork؛ contract tests |
| DroidDesk `socket_hook` هش | متوسط | متوسط | اختبارات واسعة؛ مسار bootstrap نظيف |
| Shizuku قديم/Android 16 | متوسط | متوسط | تجريد؛ fallback؛ لا اعتماد كلي |
| 16 KB يفشل Termux | متوسط | عالٍ | rebuild؛ اختبار جهاز 16 KB |
| حرارة/ذاكرة | عالٍ | متوسط | محرك واحد؛ حدود؛ HUD؛ تبريد |
| GPU غير مدعوم | متوسط | متوسط | Adreno أولًا؛ fallback واضح |
| anti-cheat | مؤكد لبعض الألعاب | متوسط | إفصاح؛ لا وعود |
| تسريب بيانات عبر `/data` | متوسط | عالٍ | منع bind؛ SAF؛ مراجعة |
| أداء غير محقق | متوسط | متوسط | بوابات قياس؛ لا ادعاءات |
| تعقيد Shell/UI | متوسط | متوسط | Compose؛ مكونات صغيرة؛ اختبارات |
| نقص فريق | عالٍ | عالٍ | نطاق MVP؛ reuse؛ مراحل |
| فشل تحديث | متوسط | عالٍ | توقيع؛ rollback؛ staging |

---

## 14. القرارات المفتوحة (تحتاج موافقتك)

1. **الأجهزة:** S24 Ultra فقط، أم Adreno 730+ عمومًا، أم دعم Mali لاحقًا؟  
   **مقترح:** Adreno 730/750 أولًا؛ Mali في Phase 5.
2. **root:** مسموح كوضع متقدم أم ممنوع كليًا؟  
   **مقترح:** اختياري، غير افتراضي.
3. **Shizuku:** إلزامي أم اختياري؟  
   **مقترح:** اختياري.
4. **التوزيع:** sideload فقط أم Play لاحقًا؟  
   **مقترح:** sideload v1؛ لا Play مع target 28.
5. **الواجهة:** Compose أم Flutter؟  
   **مقترح:** Compose.
6. **سطح المكتب:** Openbox أم XFCE أم KDE؟  
   **مقترح:** Openbox افتراضي في Lean Core؛ XFCE حزمة Comfort؛ KDE لاحقًا.
7. **الألعاب:** Steam فقط أم Epic/GOG/محاكيات؟  
   **مقترح:** Steam أولًا؛ الباقي لاحقًا.
8. **الحد الأدنى Android:** 12 أم 14؟  
   **مقترح:** `minSdk 28` تقنيًا، واختبار Android 12–16؛ توصية Android 14+.
9. **حجم التنزيل:** هل 3–5GB مقبول؟  
   **مقترح:** Core ≤1GB بعد التثبيت؛ Ubuntu/الألعاب 3–5GB كحزم اختيارية بتنزيل تدريجي.
10. **المصدر المفتوح:** هل توافق على GPL compliance ونشر التعديلات؟  
    **مقترح:** نعم، إلزامي.
11. **العلامة:** DeckOS أم اسم آخر؟  
    **مقترح:** DeckOS مؤقتًا.
12. **الذكاء الاصطناعي:** مساعد محلي في v1 أم لاحقًا؟  
    **مقترح:** لاحقًا؛ لا تعقّد MVP.

---

## 15. أول 90 يومًا

### الأسبوع 1–2 — تثبيت الأساس
- موافقة على القرارات المفتوحة.
- Spec مختصر + non-goals.
- Threat model أولي.
- مراجعة GPL/Steam/Termux.
- خط أساس: إقلاع Ubuntu، ذاكرة، صوت، تطبيقات.
- اختيار مفتاح التوقيع وهيكل CI.

### الأسبوع 3–4 — النموذج الأولي
- مشروع Android فارغ + Compose.
- `SurfaceView` + X11 server تجريبي.
- طرفية PTY بسيطة.
- إثبات: سطح + طرفية معًا.
- قياس أولي.

### الأسبوع 5–6 — Workspace
- استيراد Ubuntu 22.04.
- إصلاح P0.
- تشغيل XFCE + Chromium + صوت.
- ملف تعريف Workspace.

### الأسبوع 7–8 — الجلسة
- Session Orchestrator.
- إيقاف/بدء/استعادة.
- Audio/Input/Clipboard.
- مشغّل تطبيقات بسيط.

### الأسبوع 9–10 — التوجيه والصلاحيات
- Router v0.
- AppProfile/RuntimeProfile.
- Shizuku لملفات/تطبيقات فقط.
- شاشة صلاحيات.

### الأسبوع 11–12 — Beta
- 10 تطبيقات اختبار.
- قياس أداء/استقرار.
- مراجعة أمان.
- Beta داخلية + ملاحظات.

**بوابة 90 يومًا:** تطبيق واحد يفتح Ubuntu + طرفية، يشغّل 10 تطبيقات أساسية، صوت/إدخال/عربية تعمل، ولا P0، مع تقرير قياس.

---

## 16. المراجع

### تقارير المشروع
- `audit-termux-x11-shizuku-droiddesk.md` — التدقيق المرجعي، 422 سطرًا.
- `audit-droiddeck-droiddesk.md` — تصحيح DroidDeck/DroidDesk.
- `droiddesk-architecture.md` — بنية DroidDesk؛ الأقسام 5–7، 12–13.
- `droiddeck-architecture.md` — بنية DroidDeck؛ الأقسام 4–5، 7، 9، 11–12.
- `termux-x11-architecture.md` — Termux/X11؛ الأقسام 3–4، 7.
- `shizuku-assessment.md` — Shizuku.
- `termux-system-baseline-and-additions.md` — البيئة الحالية؛ الأقسام 2، 7–8.
- `termux-rebuild-from-zero.md` — إعادة البناء.

### شيفرة مرجعية
- DroidDesk: `LinuxRuntime.kt:151,176-190`، `X11ServerService`، `LorieView`، `X11ServiceClient`.
- DroidDeck: `session/SessionService.kt`، `runtime/LinuxRuntimeInstaller.java`، `runtime/LinuxRuntime.java`، `gpu/TurnipDriver.java`، `wayland/WaylandCompositor.java`، `audio/PulseAudioComponent.java`، `input/*`، `tools/linuxfs/overlay/usr/local/bin/droiddeck-session`.
- DroidDeck Proton: `ProtonExtras.kt:29-33`، `docs/development/x86-64-steam-client.md`، `Droid-Deck/DroidDeck-Proton`.
- Termux: `TermuxService`، `TerminalView`، `terminal-emulator`، `terminal-view`.
- Termux:X11: `CmdEntryPoint`، `Loader`، `LorieView`، `LorieBuffer`.

### مواقع
- `https://github.com/termux/termux-app`
- `https://github.com/termux/termux-x11`
- `https://github.com/orailnoor/DroidDesk`
- `https://github.com/Droid-Deck/DroidDeck`
- `https://github.com/RikkaApps/Shizuku`

---

## 17. الخلاصة

أقصر طريق إلى منتج قوي ليس «دمج كل شيء في APK»، بل:

1. **واجهة واحدة** تفهم الجلسات والمحركات.
2. **محرك سطح مكتب** يحافظ على بيئتك الحالية.
3. **محرك Native** للأداء.
4. **محرك ألعاب** يأخذ أفضل ما في DroidDeck.
5. **جسر Shizuku** آمن.
6. **توقيع وقياس وامتثال** منذ اليوم الأول.

المرحلة الأولى قابلة للتحقيق في 3–4 أشهر بفريق صغير. الرؤية الكاملة (ألعاب + Shizuku + تقارب العرض) تحتاج 12–18 شهرًا. الأهم: لا نعد بأداء لم نقِسه، ولا ندمج شيفرة لا نستطيع صيانتها أو ترخيصها.
