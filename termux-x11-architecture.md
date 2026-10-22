# Termux و Termux:X11 — تشريح معماريّ معمّق

**تاريخ الإعداد:** 21 أكتوبر 2026  
**نطاق البحث:** الشيفرة المصدرية على فرع `master` لمستودعي `termux/termux-app` و`termux/termux-x11`، إضافة إلى ملفات README والإصدارات الرسمية.  
**طبيعة المستند:** تحليل هندسي داخلي (Internal Architecture Review)، وليس دليل استخدام فقط.

> **تنبيه منهجي:** التحليل يعتمد على قراءة مباشرة للملفات المصدرية. الأرقام والإحصاءات (عدد الأسطر، الإصدارات) تم قياسها في تاريخ الإعداد وقد تتغير مع تحديثات لاحقة. مواضع الشيفرة المشار إليها هي مسارات نسبية داخل كل مستودع.

---

## 0. ملخص تنفيذي

### 0.1 بطاقة المشروعين

| البُعد | Termux (`termux-app`) | Termux:X11 (`termux-x11`) |
|---|---|---|
| الوظيفة | بيئة Linux كاملة داخل Android بدون root | خادم X11 أصلي (Native X Server) مع تطبيق عرض Android |
| اللغة السائدة | Java (Android) + C (JNI) | C/C++ (Xorg DDX) + Java (glue) |
| الترخيص | NOASSERTION (مكوّنات بتراخيص متعددة) | GPL-3.0 |
| أقل Android | API 21 (التطبيق)، لكن bootstrap الحديث يستهدف Android 7+ | `minSdk 24`، لكن التوثيق الرسمي **يشترط Android 8+ (API 26)**؛ لا دعم رسمي لـAndroid 7 |
| `targetSdkVersion` | 28 | 34 للنسخة المستقلة، و28 لنسخة `sharedUid` |
| حجم الشيفرة التقريبي | 201 ملف Java، نحو 47,500 سطر | 23 ملف Java + نحو 8,800 سطر C/C++ |
| الناتج | APK + bootstrap zip + حزم `.deb` | APK + حزمة `termux-x11-nightly` + loader APK |
| العلاقة | المضيف (Host) | عميل عرض يعتمد على Termux |

### 0.2 عشر نتائج رئيسية

1. **Termux ليس محاكي طرفية (Terminal Emulator) فحسب**، بل مدير عمليات Linux: يفتح PTY، يشغّل `login`/`bash`، ويزوّدها ببادئة (`$PREFIX`) ومستودع حزم مستقل مبني ضد bionic.
2. **كل جلسة طرفية هي عملية Linux حقيقية** مرتبطة بـ pseudo-terminal، وليست محاكاة داخل Java. محاكي الطرفية Java يعالج فقط العرض والمدخلات.
3. **`TermuxService` هو القلب التشغيلي**: خدمة أمامية (Foreground Service) تحتفظ بالجلسات وWakeLock، وتستقبل أوامر التنفيذ من التطبيق ومن التطبيقات الخارجية.
4. **`sharedUserId="com.termux"` ليس تفصيلًا قديمًا**: هو ما يسمح لتطبيقات Termux وملحقاتها بمشاركة UID والوصول إلى `$PREFIX`، وهو أيضًا أساس نسخة X11 السريعة.
5. **Termux:X11 ليس VNC ولا Wayland**: هو خادم X.Org حقيقي (DDX مخصص) يعمل داخل عملية `app_process` على Android، ويعرض الإطارات مباشرة عبر `AHardwareBuffer` وEGL.
6. **خادم X والعارض (Renderer) في عمليتين منفصلتين**: الخادم يملك الـ pixmap الجذري، والعارض يملك سياق EGL والسطح، وبينهما ذاكرة مشتركة ومقبس Unix.
7. **الجسر بين العالمين بسيط لكنه ذكي**: “طرق” TCP على المنفذ 7892، ثم قناة تحكم عبر `socketpair`، ثم تمرير واصفات الملفات بـ `SCM_RIGHTS`.
8. **الرسم شبه صفري النسخ**: الـ root pixmap يُخصّص كـ `AHardwareBuffer` (أو `memfd` في وضع `-legacy-drawing`)، ويُمرَّر مقبضه إلى عملية التطبيق، فيُستورد كـ `EGLImage` ويُرسم كنسيج (Texture) مباشرة.
9. **الإدخال يمر كأحداث JNI منظمة** (لمس، قلم، فأرة، لوحة مفاتيح، Unicode، حافظة)، مع تحويل إحداثيات Viewport/Zoom/Pan على جانب Java.
10. **`proot-distro` ليس جزءًا من Termux**، بل مدير حاويات يستخدم `proot` لتغيير جذر النظام (Rootfs) دون root، ويعتمد على Termux لتوفير النواة والحزم.

---

## 1. ما هو Termux؟

### 1.1 التعريف والفلسفة

Termux هو تطبيق Android يوفّر بيئة سطر أوامر Linux قابلة للاستخدام دون صلاحيات root ودون جهاز افتراضي. الفكرة الجوهرية:

- Android نفسه نواة Linux، لذلك لا حاجة لمحاكاة النواة.
- ما ينقص هو **مساحة مستخدم (User Space)**: shell، أدوات GNU، مدير حزم، مكتبات.
- Termux يوفّر هذه المساحة عبر **bootstrap** (أرشيف أولي) يُثبَّت داخل دليل خاص بالتطبيق، ثم يدير الحزم عبر `apt` أو `pkg`.

Termux **ليس**:

- جهازًا افتراضيًا (لا QEMU ولا KVM).
- حاوية معزولة أمنيًا (ليس sandbox أمنيًا؛ كل التطبيقات تشترك في UID التطبيق).
- توزيعة Linux كاملة النواة (لا نواة خاصة به، ولا systemd افتراضيًا).

### 1.2 لماذا لا تعمل حزم Linux العادية مباشرة؟

ثلاثة حواجز تقنية:

1. **بيئة C**: Android يستخدم `bionic` بدل `glibc`، و`linker` مختلف، ومسارات مختلفة.
2. **القيود الأمنية**: SELinux، و`noexec` على بعض المسارات، وسياسات Android التي تمنع تنفيذ ملفات من مساحات معينة.
3. **الغياب الإداري**: لا `systemd`، ولا `init` تقليدي، ولا صلاحيات mount.

لذلك تُبنى حزم Termux خصيصًا ضد bionic، وتُثبَّت في `$PREFIX`، مع تعديلات على المسارات (`/bin/sh`، `/etc`، `/tmp`) لتتوافق مع بيئة التطبيق.

### 1.3 لمحة تاريخية

- بدأ المشروع عام 2015 (Fredrik Fornwall)، وانتشر عبر F-Droid.
- مع سياسات Google Play المتغيرة، أصبح GitHub/F-Droid المصدرين الأساسيين.
- الإصدار 0.118.x أعاد هيكلة المشروع إلى وحدات Gradle منفصلة، وفصل `termux-shared` و`terminal-emulator` و`terminal-view`.
- نسخة Google Play (“termux-play-store”) مختلفة جذريًا: أُزيل `sharedUserId`، وتغيّرت صلاحيات كثيرة، وهي أقل قدرة من نسخة GitHub.

---

## 2. الخريطة العامة للمنظومة

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Android / Linux Kernel                      │
│  (SELinux, cgroups/cpuset, binder, ashmem/memfd, AHardwareBuffer)    │
└───────────────▲───────────────────────────────▲──────────────────────┘
                │ syscalls                      │ ioctls / mmap
┌───────────────┴───────────────┐   ┌───────────┴──────────────────────┐
│   Termux App Process          │   │   Termux:X11 Server Process      │
│  ┌─────────────────────────┐  │   │  (app_process + libXlorie.so)    │
│  │ TermuxActivity          │  │   │  ┌────────────────────────────┐  │
│  │  └ TerminalView         │  │   │  │ Xorg DDX (Lorie)           │  │
│  │      └ TerminalEmulator │  │   │  │  ├ fb + EXA                │  │
│  │          └ TerminalSession│ │   │  │  ├ DRI3 / Present          │  │
│  │              └ JNI PTY  │  │   │  │  ├ RandR                   │  │
│  ├─────────────────────────┤  │   │  │  └ Input (XKB, touch...)   │  │
│  │ TermuxService           │  │   │  └────────────────────────────┘  │
│  │  ├ sessions             │  │   │              ▲                   │
│  │  ├ wakelocks            │  │   │              │ X11 protocol      │
│  │  └ RUN_COMMAND          │  │   │       ┌──────┴───────┐           │
│  └─────────────────────────┘  │   │       │ X11 Clients  │           │
└───────────────▲───────────────┘   │       │ (xfce, etc.) │           │
                │                   │       └──────────────┘           │
                │ fork/exec         └───────────▲──────────────────────┘
┌───────────────┴───────────────────────────────┴──────────────────────┐
│  $PREFIX = /data/data/com.termux/files/usr                            │
│  bash, coreutils, apt/pkg, termux-x11, proot-distro, ...              │
└───────────────────────────────────────────────────────────────────────┘
                │
        ┌───────┴────────┐
        │  proot / chroot │  ← حاويات مستخدم (اختياري)
        │  Debian/Ubuntu  │
        └─────────────────┘
```

النقطة المهمة: **Termux:X11 ليس طبقة داخل تطبيق Termux**، بل عملية مستقلة تُشغَّل من shell، وتحتاج APK مثبتًا لتوفير `libXlorie.so` والموارد.

---

## 3. تشريح تطبيق Termux

### 3.1 الوحدات الأربع

| الوحدة | الدور | ملفات Java | أسطر Java (تقريبي) |
|---|---|---:|---:|
| `:app` | واجهة المستخدم، الجلسات، الخدمة، التثبيت | 35 | 7,021 |
| `:termux-shared` | ثوابت، مسارات، إعدادات، صلاحيات، أدوات مشتركة | 121 | 26,774 |
| `:terminal-emulator` | محاكي الطرفية، PTY، JNI، أنماط الطرفية | 37 | 10,780 |
| `:terminal-view` | `View` الرسم واللمس ولوحة المفاتيح الإضافية | 8 | 2,880 |

الفصل مقصود: `terminal-emulator` و`terminal-view` يمكن إعادة استخدامهما في تطبيقات أخرى (وهو ما يفعله عدد من المشاريع).

### 3.2 البناء والتوزيع

- `settings.gradle` يضم الوحدات الأربع.
- `app/build.gradle` يعرّف `packageVariant` (الافتراضي `apt-android-7`) ويولّد مهمة `downloadBootstraps` لتنزيل أرشيف bootstrap والتحقق من checksums.
- `compileSdkVersion=36`، `minSdkVersion=21`، `targetSdkVersion=28`، `ndkVersion=29.0.14206865`.
- يدعم `split APKs` حسب ABI: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- يستخدم `markwon` لعرض Markdown داخل التطبيق (ملفات المساعدة وسجل الأخطاء).
- التوقيع الافتراضي في نسخ GitHub هو **test key** مشترك مع المجتمع، وليس مفتاحًا رسميًا. تحقق 2026-10-21: بصمة الشهادة `b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1`، وهي مطابقة لتوقيع Termux GitHub.

لماذا `targetSdk 28`؟ لأن التطبيق يحتاج سلوكيات قديمة للسماح بتنفيذ ملفات من دليل التطبيق (`exec` من `files/`)، والوصول إلى مسارات معينة، والعمل مع `sharedUserId`. رفع `targetSdk` إلى 29+ يفرض قيودًا تكسر نموذج Termux الأساسي.

### 3.3 تثبيت الـ bootstrap و`$PREFIX`

`TermuxInstaller.setupBootstrapIfNeeded(Activity, Runnable)` يتبع سلسلة خطوات موثّقة في تعليق رأس الملف:

1. إذا كان `$PREFIX` موجودًا، افترض أنه صالح وانتهِ.
2. اعرض حوار تقدم “Installing...”.
3. نظّف `$STAGING_PREFIX` إن كان متبقيًا من تثبيت فاشل.
4. اقرأ أرشيف bootstrap من مكتبة مشتركة داخل APK (وليس من الشبكة في النسخ الحديثة).
5. استخرج كل مدخل إلى `$STAGING_PREFIX`، واضبط صلاحيات التنفيذ عند الحاجة.
6. عند رؤية `SYMLINKS.txt`، سجّل الروابط الرمزية ثم أنشئها.
7. انقل `$STAGING_PREFIX` إلى `$PREFIX` بشكل ذري (rename).
8. أعد إنشاء ملف البيئة عبر `TermuxShellEnvironment.writeEnvironmentToFile(...)`.

قيود إضافية في نفس الملف:

- يجب أن يكون المستخدم الحالي هو **primary user** (`PackageUtils.isCurrentUserThePrimaryUser`) وإلا تظهر رسالة `bootstrap_error_not_primary_user_message`.
- يجب أن يكون دليل ملفات Termux قابلًا للوصول (`TermuxFileUtils.isTermuxFilesDirectoryAccessible`).
- التعامل مع التثبيت على external storage له مسار خاص (`PackageUtils.isAppInstalledOnExternalStorage`).
- أي فشل ينظّف `$PREFIX` و`$STAGING_PREFIX` ويعرض حوار خطأ ويرسل إشعارًا.

البنية الناتجة:

```
/data/data/com.termux/
├── files/
│   ├── home/                 # $HOME
│   └── usr/                  # $PREFIX
│       ├── bin/              # bash, apt, pkg, ...
│       ├── etc/
│       ├── lib/
│       ├── libexec/
│       ├── share/
│       ├── tmp/              # $TMPDIR الافتراضي
│       └── var/
└── cache/
```

### 3.4 PTY: من Java إلى النواة

المسار الكامل:

```
TerminalView (input)
   → TerminalEmulator (screen buffer)
      → TerminalSession (Java)
         → JNI.createSubprocess(...)
            → termux.c: create_subprocess()
               → open("/dev/ptmx")
               → grantpt / unlockpt / ptsname_r
               → fork()
                  → setsid(); ioctl(TIOCSCTTY)
                  → dup2(slave, 0/1/2)
                  → execvp(cmd, argv)
```

مقتطف من `terminal-emulator/src/main/jni/termux.c`:

```c
int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);

if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
    return throw_runtime_exception(env, "Cannot grantpt()/unlockpt()/ptsname_r() on /dev/ptmx");
}

struct termios tios;
tcgetattr(ptm, &tios);
tios.c_iflag |= IUTF8;
tios.c_iflag &= ~(IXON | IXOFF);
tcsetattr(ptm, TCSANOW, &tios);

pid_t pid = fork();
if (pid == 0) {
    setsid();
    ioctl(slave, TIOCSCTTY, 0);
    dup2(slave, 0); dup2(slave, 1); dup2(slave, 2);
    execvp(cmd, argv);
}
```

تفاصيل مهمة:

- `IUTF8` يضمن أن معالجة المحرر (backspace) تفهم المحارف متعددة البايت.
- تعطيل `IXON/IXOFF` يمنع `Ctrl+S` من تعليق العرض.
- `TIOCSCTTY` يجعل PTY هو الطرفية المتحكمة للجلسة.
- حجم النافذة يُضبط بـ `TIOCSWINSZ` عند تغيير حجم `TerminalView`.
- `TerminalSession` يملك مؤشر الجلسة (file descriptor) ويقرأ منه في خيط منفصل، ثم يمرّر البايتات إلى `TerminalEmulator`.

### 3.5 `TermuxService`: العمر والجلسات

`TermuxService` خدمة أمامية (Foreground Service) وتملك:

- قائمة الجلسات (`TerminalSession`) و`TerminalSessionClient`.
- WakeLock وWifiLock (اختياري) لمنع النوم أثناء تنفيذ طويل.
- إشعارًا دائمًا مع زر إيقاف.
- `Binder` للتواصل مع `TermuxActivity`.

دورة الحياة المبسطة:

```
onCreate()
  → إنشاء قنوات الإشعارات
  → startForeground(...)
onStartCommand(intent)
  → ACTION_STOP_SERVICE      : إيقاف كل الجلسات والخدمة
  → ACTION_WAKE_LOCK         : احتفاظ بـ WakeLock
  → ACTION_WAKE_UNLOCK       : تحرير WakeLock
  → ACTION_SERVICE_EXECUTE   : تنفيذ أمر في جلسة جديدة/قائمة
  → return START_NOT_STICKY
onDestroy()
  → إغلاق الجلسات، تحرير الأقفال، إزالة الإشعار
```

لماذا `START_NOT_STICKY`؟ لأن إعادة التشغيل التلقائية بعد قتل النظام للخدمة قد تترك جلسات يتيمة أو تنفّذ أوامر مرتين؛ Termux يفضّل أن يبدأ المستخدم من جديد.

### 3.6 الواجهة: `TermuxActivity` و`TerminalView`

- `TermuxActivity` يدير الأدراج (drawer) وقائمة الجلسات والإعدادات وطلب الصلاحيات.
- `TerminalView` هو `View` مخصص يرسم شبكة المحارف على `Canvas`، ويتعامل مع اللمس، والتكبير، ولوحة المفاتيح الإضافية (Extra Keys).
- `TerminalEmulator` يحتفظ بمخزن الشاشة (Screen Buffer) وحالة المؤشر وأنماط ANSI.
- `TerminalRenderer` يحوّل المخزن إلى رسم فعلي، مع دعم الألوان والحروف العريضة والمؤشر الوامض.
- الإدخال يمر عبر `TerminalView` إلى `TerminalSession.write(...)` ثم إلى PTY.

### 3.7 `sharedUserId`: لماذا هو محوري؟

في `AndroidManifest.xml`:

```xml
android:sharedUserId="${TERMUX_PACKAGE_NAME}"
```

النتيجة:

- كل تطبيقات Termux (الأساسي و`:API` و`:Boot` و`:Float` و`:Styling` و`:Tasker` و`:Widget`) تعمل بنفس UID.
- تستطيع الوصول إلى نفس `$PREFIX` والملفات دون إذن Storage.
- تستطيع استدعاء خدمات بعضها، مثل `RUN_COMMAND`، دون حواجز UID.

ثمن ذلك:

- **التوقيع يجب أن يكون متطابقًا**. أي اختلاف في المفتاح يمنع التثبيت فوق الآخر.
- الأمان: أي تطبيق مشارك في UID يستطيع قراءة ملفات الآخر.
- Google Play أزال `sharedUserId`، لذلك نسخة Play Store مختلفة وغير متوافقة.

### 3.8 `RUN_COMMAND` وواجهات الأتمتة

`RunCommandService` يستقبل `Intent` يحمل:

- `com.termux.RUN_COMMAND_PATH` (أمر التنفيذ).
- `com.termux.RUN_COMMAND_ARGUMENTS` (مصفوفة معاملات).
- `com.termux.RUN_COMMAND_WORKDIR`.
- `com.termux.RUN_COMMAND_BACKGROUND`.
- `com.termux.RUN_COMMAND_SESSION_ACTION`.

الصلاحية المطلوبة: `com.termux.permission.RUN_COMMAND` (dangerous). يوجد أيضًا تحقق ثانوي عبر خاصية `allow-external-apps` في `termux.properties`.

هذه الواجهة هي أساس تكامل Tasker وTermux:Widget والتطبيقات الخارجية.

### 3.9 قيود Android التي تشكّل التصميم

| القيد | الأثر على Termux |
|---|---|
| `targetSdk 28` | يسمح بتنفيذ ملفات من دليل التطبيق وسلوكيات قديمة؛ الترقية تكسر النموذج |
| Phantom Process Killer (Android 12+) | يقتل العمليات الفرعية الزائدة (حد 32 افتراضيًا) بإشارة 9؛ يظهر في issue #2366 |
| Foreground Service | ضروري لبقاء الجلسات؛ الإشعار دائم |
| SELinux | يمنع بعض عمليات mount/exec؛ يتطلب حلولًا مثل `proot` |
| Storage Scoped | `$HOME` داخل دليل التطبيق؛ الوصول الخارجي عبر `termux-setup-storage` |
| Doze / cpuset | تقييد المعالجة للتطبيقات غير المرئية؛ لذلك توجد نسخة `sharedUid` لـ X11 |

### 3.10 شجرة الملفات والدلائل المهمة

| المسار | المعنى |
|---|---|
| `/data/data/com.termux/files/usr` | `$PREFIX` |
| `/data/data/com.termux/files/home` | `$HOME` |
| `$PREFIX/bin` | تنفيذيات الحزم |
| `$PREFIX/etc` | إعدادات |
| `$PREFIX/var/lib/dpkg` | قاعدة بيانات الحزم (في نسخ apt) |
| `$PREFIX/tmp` | `$TMPDIR` الافتراضي (وقد يكون `/tmp` داخل proot) |
| `~/storage` | روابط التخزين المشترك بعد `termux-setup-storage` |

---

## 4. Termux:X11

### 4.1 المشكلة التي يحلها

تطبيقات X11 تحتاج خادم عرض. الخيارات التقليدية:

- **VNC**: يشفّر الإطارات ويرسلها عبر الشبكة ثم يفكّها العميل → تأخير وجودة أقل.
- **Wayland داخل Android**: غير عملي بدون compositor متوافق.
- **X11 أصلي**: يتطلب تنفيذ بروتوكول X11 وخادم عرض حقيقي.

Termux:X11 يوفّر **X.Org Server** مبنيًا بـ NDK، مع طبقة عرض (DDX) مخصصة اسمها **Lorie**، تتحدث مباشرة مع `SurfaceView` في Android عبر EGL و`AHardwareBuffer`.

### 4.2 المكونات

| المكوّن | النوع | الدور |
|---|---|---|
| `lorie` | وحدة Android library | تطبيق العرض، `LorieView`، معالجة الإدخال، تحميل `libXlorie.so` |
| `lorie/src/main/cpp` | C/C++ | خادم Xorg + DDX + العارض + الجسر |
| `lorie-app` | تطبيق Android | `MainActivity`، الإعدادات، شاشات العرض، أيقونة التطبيق |
| `shell-loader` | Java + Gradle | `Loader` الذي يشغّل الخادم من سطر الأوامر، ويولّد حزمة `termux-x11-nightly` |
| `termux-x11` (script) | Bash | نقطة الدخول من Termux؛ تتحقق من Android ثم `exec app_process` |
| `termux-x11-preference` | Bash | تغيير تفضيلات التطبيق من سطر الأوامر عبر Broadcast |

إحصاءات تقريبية: 176 ملفًا، 23 ملف Java في `lorie` (~8,000 سطر)، وحوالي 8,800 سطر C/C++.

### 4.3 المعمارية: ثلاث عمليات

```
┌─────────────────────────────┐
│  Termux shell (bash)        │
│  $ termux-x11 :0 ...        │
└──────────────┬──────────────┘
               │ exec app_process
               ▼
┌─────────────────────────────┐        socketpair         ┌──────────────────────────────┐
│  X Server Process           │◄─────────────────────────►│  Android App Process          │
│  name: termux-x11           │   (SCM_RIGHTS + events)   │  com.termux.x11               │
│  libXlorie.so               │                           │  MainActivity + LorieView     │
│  ├ dix_main()               │                           │  ├ Renderer thread (EGL/GLES) │
│  ├ Lorie DDX                │                           │  ├ Input handlers             │
│  ├ root pixmap (LorieBuffer)                             │  └ Clipboard bridge           │
│  └ X11 clients via :0       │                           └──────────────▲───────────────┘
└──────────────▲──────────────┘                                          │
               │ X11 protocol                                            │ Surface
               │                                                         ▼
        ┌──────┴───────┐                                        ┌─────────────────┐
        │ X11 apps     │                                        │ SurfaceView     │
        │ xfce4, etc.  │                                        │ (ANativeWindow) │
        └──────────────┘                                        └─────────────────┘
```

لماذا عمليتان؟

- خادم X يحتاج حلقة أحداث وبيئة Xorg كاملة، وقد يتعطل أو يُعاد تشغيله.
- العارض يحتاج سياق EGL مرتبطًا بسطح Android، ويجب ألا يتجمد إذا تأخر الخادم.
- الفصل يسمح بإعادة الاتصال دون فقدان جلسة X (إلى حد ما).

### 4.4 مسار الإقلاع خطوة بخطوة

#### الخطوة 1: أمر `termux-x11`

سكربت `shell-loader/scripts/termux-x11.in`:

```bash
#!/data/data/@EMBEDDED_APPLICATION_ID@/files/usr/bin/bash
if [ ! -e /system/bin/getprop ] || [ ! -e /system/bin/app_process ]; then
  echo "Headless system detected..."
  exit 1
fi
if [ "$(getprop ro.build.version.sdk)" -lt "24" ]; then
  echo "Termux:X11 requires at least Android 7 or newer."
  exit 1
fi
[ -z "${LD_LIBRARY_PATH+x}" ] || export XSTARTUP_LD_LIBRARY_PATH="$LD_LIBRARY_PATH"
[ -z "${LD_PRELOAD+x}" ]    || export XSTARTUP_LD_PRELOAD="$LD_PRELOAD"
[ -z "${CLASSPATH+x}" ]     || export XSTARTUP_CLASSPATH="$CLASSPATH"
export CLASSPATH=/data/data/@EMBEDDED_APPLICATION_ID@/files/usr/libexec/termux-x11/loader.apk
unset LD_LIBRARY_PATH LD_PRELOAD
exec /system/bin/app_process -Xnoimage-dex2oat / --nice-name="termux-x11 @APPLICATION_ID@ $*" \
     com.termux.x11.Loader "$@"
```

النقاط المهمة:

- يحفظ `LD_LIBRARY_PATH` و`LD_PRELOAD` الأصليين في متغيرات `XSTARTUP_*` لتمريرها لاحقًا إلى `-xstartup`.
- يزيل `LD_PRELOAD` قبل تشغيل `app_process` لتجنب كسر `libtermux-exec.so` أو linker.
- يستخدم `loader.apk` المضمّن في حزمة Termux، وليس APK التطبيق مباشرة.

#### الخطوة 2: `Loader`

`shell-loader/src/main/java/com/termux/x11/Loader.java`:

1. يقرأ `BuildConfig.CLASS_ID` أو `TERMUX_X11_LOADER_OVERRIDE_CMDENTRYPOINT_CLASS`.
2. يستعلم `PackageManager` عن APK الهدف (`com.termux.x11`).
3. يتحقق أن **بصمة التوقيع** (`BuildConfig.SIGNATURE`) تطابق توقيع APK المثبت.
4. يحمّل APK عبر `PathClassLoader` ويستدعي `main(String[])` للصف الهدف.

هذا يمنع استبدال APK بتطبيق خبيث بنفس الاسم، ويسمح بتحديث التطبيق دون تحديث loader.

#### الخطوة 3: `CmdEntryPoint.main`

`lorie/src/main/java/com/termux/x11/CmdEntryPoint.java`:

```java
public static void main(String[] args) {
    initEntryPoint();
    handler.post(() -> new CmdEntryPoint(args));
    Looper.loop();
}
```

- `initEntryPoint()` ينشئ `Context` ويحمّل `libXlorie.so` من مسار APK.
- `new CmdEntryPoint(args)` يستدعي `native start(args)`.
- `start` يحوّل `String[]` إلى `argv`، يضبط affinity (نصف الأنوية الأخيرة)، يضبط `TMPDIR` و`XKB_CONFIG_ROOT`، ثم ينشئ خيطًا يشغّل `dix_main()`.

#### الخطوة 4: تهيئة الخادم

`OsVendorInit()` في `InitOutput.c`:

- ينشئ `memfd` لحالة الخادم المشتركة (`lorie_shared_server_state`).
- يهيّئ mutex مشتركًا بين العمليات (`PTHREAD_PROCESS_SHARED`, `PTHREAD_MUTEX_RECURSIVE`).
- يفتح منفذ “الطرق” (Knock):
  - `PORT = 7892` للعرض الافتراضي.
  - `DOCUMENT_PORT_BASE + display = 7900 + N` عند استخدام `-tag`.
- يستدعي `lorieListenForKnocks(port, tag)`.

#### الخطوة 5: تهيئة الشاشة

`lorieScreenInit` يقوم بـ:

- `eventfd(EFD_CLOEXEC)` + `SetNotifyFd` لإيقاظ الخادم.
- `miSetZeroLineBias`.
- `vblank_interval = 1000000 / framerate`.
- `miSetVisualTypesAndMasks(24, TrueColor|DirectColor, 8, TrueColor, 0xFF0000, 0x00FF00, 0x0000FF)`.
- `fbScreenInit`.
- `lorieCreateScreenResources` (ينشئ الـ root pixmap من نوع Lorie).
- `dri3_screen_init` و`present_screen_init`.
- `fbPictureInit` و`exaDriverInit`.
- `lorieRandRInit`.
- `miPointerInitialize`.
- `fbCreateDefColormap`.
- `ShmRegisterFbFuncs` و`miSyncShmScreenInit`.

#### الخطوة 6: اتصال التطبيق

`MainActivity` يبدأ محاولة الاتصال الدورية (`tryConnect`) كل 250ms:

1. إذا لم يكن هناك `service`، يستدعي `LorieView.requestConnection()`.
2. `requestConnection()` (native) يفتح TCP إلى `127.0.0.1:7892`، ويضبط Non-blocking، ويكتب `MAGIC`.
3. الخادم يستقبل الاتصال، يتحقق من `MAGIC`، ثم يرسل Broadcast `ACTION_START` مع `Binder` إلى التطبيق.
4. التطبيق يستقبل الـ Binder في `onReceive` ويستدعي `connectToService(ibinder)`.
5. `connectToService` يحصل على `getLogcatOutput()` (اختياري) ثم `tryConnect()`.
6. `tryConnect()` يستدعي `service.getXConnection()`.
7. `getXConnection()` ينشئ `socketpair(AF_UNIX, SOCK_STREAM)`:
   - أحد الطرفين يُمرَّر إلى خيط الخادم عبر `QueueWorkProc` ويُسجَّل كجهاز إدخال (`InputThreadRegisterDev`).
   - الطرف الآخر يُعاد إلى التطبيق كـ `ParcelFileDescriptor`.
8. التطبيق يستدعي `LorieView.connect(fd)`، فيبدأ العارض باستقبال الأحداث والواصفات.

#### الخطوة 7: أول إطار

- `lorieCreateScreenResources` ينشئ `PixmapPtr` بصلاحية `CREATE_PIXMAP_USAGE_LORIEBUFFER_BACKED`.
- `lorieRegisterBuffer(LORIE_BUFFER_FROM_PIXMAP(...))` يرسل `EVENT_ADD_BUFFER` + مقبض `AHardwareBuffer` إلى التطبيق.
- العارض يستورد المقبض كـ `EGLImage` ثم كنسيج (Texture).
- عند حدوث تلف (Damage)، يُستدعى `lorieRedraw` ويرسل `EVENT_ADD_BUFFER`/تحديث إلى العارض.
- العارض يرسم النسيج على `ANativeWindow` عبر `eglSwapBuffers`.

### 4.5 بروتوكول التحكم

#### قناة “الطرق” (Knock)

- الخادم يستمع على `INADDR_ANY` (كل الواجهات)، والعميل يتصل عبر `127.0.0.1`. عمليًا يبقى الوصول محدودًا بصلاحيات الشبكة وسياسات Android، لكنه ليس مقيدًا بالـ loopback على مستوى الـ bind.
- رسالة `MAGIC` قصيرة (قيمة `0xDEADBEEF`).
- الغرض: إعلام التطبيق أن خادم X جاهز، وليس نقل بيانات.
- لا توجد مصادقة على هذه القناة، لكنها لا تمنح تحكمًا: القناة الحقيقية هي `socketpair` التي يملكها التطبيق عبر Binder.

#### أحداث القناة الرئيسية

| الحدث | الاتجاه | المعنى |
|---|---|---|
| `EVENT_SHARED_SERVER_STATE` | الخادم → العارض | تمرير `memfd` للحالة المشتركة |
| `EVENT_ADD_BUFFER` | الخادم → العارض | إضافة `LorieBuffer` (مع مقبض) |
| `EVENT_REMOVE_BUFFER` | الخادم → العارض | إزالة buffer |
| `EVENT_SCREEN_SIZE` | العارض → الخادم | تغيير حجم/اسم/معدل تحديث الشاشة |
| `EVENT_TOUCH` | العارض → الخادم | حدث لمس |
| `EVENT_MOUSE` | العارض → الخادم | حركة/أزرار/تمرير |
| `EVENT_KEY` | العارض → الخادم | ضغط/إفلات مفتاح |
| `EVENT_UNICODE` | العارض → الخادم | إدخال محرف Unicode |
| `EVENT_STYLUS` | العارض → الخادم | قلم/ممحاة مع ضغط وميل |
| `EVENT_CLIPBOARD_*` | ثنائي الاتجاه | مزامنة الحافظة |
| `EVENT_SYNC` / `EVENT_SYNC_REPLY` | ثنائي الاتجاه | حاجز تزامن |
| `EVENT_HEARTBEAT` | الخادم → العارض | نبض حياة |

### 4.6 الذاكرة المشتركة وصفر النسخ

#### `LorieBuffer`

ثلاثة أنواع:

```c
enum {
    LORIEBUFFER_UNKNOWN,
    LORIEBUFFER_REGULAR,          // ذاكرة عادية داخل العملية
    LORIEBUFFER_FD,               // memfd/ashmem
    LORIEBUFFER_AHARDWAREBUFFER,  // AHardwareBuffer
};
```

- `LorieBuffer_createRegion` يستخدم `memfd_create` مع `MFD_CLOEXEC | MFD_ALLOW_SEALING`، أو `ashmem` كبديل.
- `LorieBuffer_allocate` يختار النوع حسب المنصة والقدرات.
- `LorieBuffer_sendHandleToUnixSocket` يرسل المقبض بـ `SCM_RIGHTS` (أو `AHardwareBuffer_sendHandleToUnixSocket`).
- `LorieBuffer_recvHandleFromUnixSocket` يستقبل ويعيد التغليف.

#### الحالة المشتركة

`lorie_shared_server_state` في `lorie.h` تحتوي:

```c
pthread_mutex_t lock;          // يمنع الرسم أثناء كتابة الخادم
pid_t lockingPid;              // لاكتشاف العملية الميتة
struct {
    volatile uint32_t writeIndex;   // يكتبه الخادم فقط
    volatile uint32_t readIndex;    // يقرأه العارض فقط
    volatile uint64_t completedSerial;
    LorieGpuCopyEntry entries[8];
} gpuCopyQueue;

uint64_t rootWindowTextureID;
volatile uint8_t drawRequested;
volatile uint8_t surfaceAvailable;
volatile uint8_t waitForNextFrame;
volatile int renderedFrames;

struct {
    pthread_mutex_t lock;
    pid_t lockingPid;
    uint32_t x, y, xhot, yhot, width, height;
    uint32_t bits[512*512];    // 1MB للـ cursor
    volatile uint8_t updated, moved, visible;
} cursor;
```

هذه الحالة تُنشأ في `memfd` وتُمرَّر إلى العارض، فيقرأ المؤشر (Cursor) وأعلام الرسم بدون IPC لكل إطار.

#### لماذا mutex مع مهلة 33ms؟

تعليق `lorie_mutex_lock` يشرح المشكلة: bionic لا يوفّر robust mutex، فإذا ماتت إحدى العمليتين وهي تحمل القفل، يعلق الأخرى. الحل:

- محاولة قفل بمهلة 33ms.
- إذا انتهت المهلة وكانت العملية المالكة ميتة (`!lorieConnectionAlive()`), يُعاد تهيئة القفل في المكان.
- هذا حل عملي، لكنه يكشف أن التصميم يفترض وجود عمليتين فقط.

### 4.7 مسار الرسم الكامل

```
X11 client
  └─ Present / DRI3 / EXA
       ├─ إذا كان الـ pixmap من نوع Lorie → تمرير مباشر
       ├─ إذا كان FD عادي → استيراد كـ texture (نسخ CPU→GPU)
       └─ إذا كان AHardwareBuffer → استيراد كـ EGLImage (صفر نسخ)
            │
            ▼
   root pixmap (AHardwareBuffer أو memfd)
            │  SCM_RIGHTS
            ▼
   Renderer (app process)
     ├─ eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID)
     ├─ glBindTexture / shader
     ├─ viewport + zoom + pan
     ├─ cursor overlay
     └─ eglSwapBuffers(ANativeWindow)
```

#### طبقة Xorg

- **fb**: الرسم الأساسي في الذاكرة.
- **EXA**: تسريع الرسم (في هذه الحالة غالبًا برمجي/محدود).
- **DRI3**: يسمح للعملاء بتخصيص buffers ومشاركتها.
- **Present**: يزامن تقديم الإطارات مع VBlank.
- **GLX**: يُعلن لكن التعليق في `InitOutput.c` صريح: `/* There is no real GLX support, but anyways swrast reports it. */` — أي أن تطبيقات OpenGL تعمل غالبًا عبر **swrast/llvmpipe**، وليس بتسريع GLX كامل.

#### العارض

- ينشئ `EGLDisplay` و`EGLContext` (GLES2) و`EGLSurface` من `ANativeWindow_fromSurface`.
- يختبر القدرات عبر `Renderer::testCapabilities(int* legacy_drawing, int* gpu_present_disabled)`:
  - يحاول `AHardwareBuffer_allocate` بصلاحيات `CPU_WRITE_OFTEN | CPU_READ_OFTEN`.
  - يرسل المقبض عبر socket للتحقق من دعم dma-buf.
  - إذا فشل، يعطّل GPU present offload ويستخدم مسارًا بديلًا.
- `applyPendingGpuCopiesLocked` يعالج طابور نسخ GPU (FBO + `gpuCopyQueue`).
- `eglSwapInterval(1)` + `waitForNextFrame` لتجنب إهدار GPU على إطارات لن تُعرض.
- `filtering` (NEAREST/LINEAR) و`zoomPercent` و`viewport` قابلة للتغيير من Java.

### 4.8 مسار الإدخال

#### اللمس

- `LorieView`/`TouchInputHandler` يحوّلان إحداثيات الشاشة إلى إحداثيات X11 مع مراعاة `viewport` و`zoom` و`pan`.
- `sendTouchEvent(action, id, x, y)`.
- الخادم يقيّد الإحداثيات داخل أبعاد الشاشة، ويحوّلها إلى نطاق `0..0xFFFF` للـ valuators.
- يدعم أحداث `XI_TouchBegin/Update/End`، ويتعامل مع حالة وصول `Update` بدون `Begin`.

#### القلم (Stylus)

- `sendStylusEvent(...)` يرسل: `x, y, pressure, tilt_x, tilt_y, orientation, buttons, eraser, mouse`.
- الخادم يوجّه الحدث إلى `loriePen` أو `lorieEraser` أو `lorieMouse` حسب الحالة.
- أسماء الأجهزة: `TERMUX-X11 PEN` و`TERMUX-X11 ERASER`.

#### الفأرة

- وضعان: مطلق (Absolute) ونسبي (Relative/Trackpad).
- الأزرار 1/2/3، والتمرير يُرسل كـ valuator 2/3 بوحدات 120.

#### لوحة المفاتيح

- `sendKeyEvent(scan_code, key_code, down)`.
- الخادم يحوّل Android keycode إلى X keycode.
- المفاتيح الموسعة (خارج نطاق 255) تُعالج عبر `lorieExtendedKeys`.
- `EVENT_UNICODE` يستخدم `ucs2keysym` لإدخال محارف ليست على لوحة المفاتيح.

#### IME

- `LorieView.onCreateInputConnection` يعيد `InputConnection` مخصصًا.
- `commitText` و`setComposingText` يمران عبر `sendTextEvent`.
- `resetIme()` يُستدعى من native عند تغيير تركيز نافذة X، لإلغاء التوقع (Prediction) والتكوين المعلق.

#### حاجز التزامن

`sendSync(serial)` يرسل `EVENT_SYNC`، ولا يُنفَّذ الحدث التالي حتى يرد الخادم بـ `EVENT_SYNC_REPLY`. يُستخدم لضمان ترتيب الإدخال مع إعادة الرسم.

### 4.9 الحافظة (Clipboard)

- `EVENT_CLIPBOARD_ENABLE`: تفعيل/تعطيل المزامنة حسب تركيز النافذة.
- `EVENT_CLIPBOARD_ANNOUNCE`: إعلام الخادم بأن محتوى الحافظة تغيّر.
- `EVENT_CLIPBOARD_REQUEST`: طلب محتوى.
- `EVENT_CLIPBOARD_SEND`: إرسال النص (UTF-8) إلى الطرف الآخر.

في Java: `LorieView` يسجّل `ClipboardManager.OnPrimaryClipChangedListener`، ويقرأ/يكتب عبر `ClipboardManager`. في الخادم: يُربط بـ X Selection (عادة `CLIPBOARD` و`PRIMARY`).

### 4.10 الشاشة وRandR

- `LorieView.sendWindowChange(width, height, framerate, name)` يُستدعى عند تغيير السطح.
- `framerate` يأتي من `Display.getRefreshRate()`.
- الاسم: `builtin` أو `dex` أو `external`.
- الخادم يستدعي `lorieConfigureNotify` ثم `lorieRRScreenSetSize`:
  - يعيد تخصيص الـ root pixmap بالحجم الجديد.
  - ينسخ المحتوى القديم (CopyArea) بالقدر المتاح.
  - يعيد تسجيل Damage.
  - يستدعي `RRScreenSizeNotify` و`update_desktop_dimensions`.
- `monitorResolution` افتراضي 96 DPI، ويُحسب `mmWidth/mmHeight` منه.
- `-tag <tag>` يفتح كل عرض في نافذة Android مستقلة، ويستخدم منفذ `7900 + N`.

### 4.11 `sharedUid` والأداء

المشكلة: Android يعطي التطبيقات غير المرئية حصة معالجة أقل (cpuset/priority). عند فتح نافذة X11، يصبح Termux “غير مرئي”، فتبدأ تطبيقات سطح المكتب بالتباطؤ.

الحل: نسخة `sharedUid` من APK:

```xml
<!-- lorie-app/src/sharedUid/AndroidManifest.xml -->
<manifest android:sharedUserId="com.termux">
    <application android:process="com.termux" />
</manifest>
```

النتيجة: عملية X11 تُعتبر جزءًا من Termux نفسه، فتحصل على نفس الحصة.

قيود:

- تعمل فقط مع Termux المثبت من GitHub (نفس التوقيع).
- `targetSdkVersion=28` لهذه النسخة، لأن `sharedUserId` أُزيل في API 29+.
- هناك مهمة Gradle (`VerifySharedUidProcess`) تتحقق أن كل مكونات التطبيق تعمل في عملية `com.termux`.

### 4.12 الأمان والعزل

- **X11 ليس معزولًا**: أي عميل X يستطيع قراءة إدخال وحافظة عملاء آخرين (حسب اختيارات X). لا تشغّل تطبيقات غير موثوقة.
- **الخادم يعمل بصلاحيات Termux**: أي ثغرة في Xorg أو DDX تعني وصولًا إلى ملفات Termux.
- **Loader يتحقق من التوقيع**، لكنه لا يحمي من APK مثبت بتوقيع مختلف إذا عُدّل `BuildConfig.SIGNATURE` في build مخصص.
- **`setenforce 0`** المطلوب أحيانًا مع chroot يضعف SELinux بشكل كبير؛ يُفضّل proot.
- **الحافظة** قد تكشف كلمات مرور من مدير كلمات مرور على Android إلى تطبيقات X.

### 4.13 حدود معروفة

| الحد | التوضيح |
|---|---|
| لا تسريع GLX حقيقي | OpenGL عبر swrast غالبًا؛ التسريع في العرض/النسخ فقط |
| توافق EGL متباين | بعض الأجهزة تحتاج `-legacy-drawing` أو `-force-bgra` |
| Android 12+ phantom killer | قد يقتل الخادم أو تطبيقات X |
| نافذة واحدة افتراضيًا | تعدد العروض يحتاج `-tag` |
| لا إدارة نوافذ Android أصلية | النوافذ داخل لوحة X واحدة |
| الأداء | يعتمد على dma-buf وAHardwareBuffer؛ الأجهزة القديمة أبطأ |
| proot | يحتاج `--shared-tmp` أو `TMPDIR` صحيح |

---

## 5. المنظومة المحيطة

### 5.1 `termux-packages`

المستودع الذي يبني كل حزم Termux:

- `packages/`: حزم CLI/Main.
- `x11-packages/`: حزم X11 وسطح المكتب (نحو 974 حزمة).
- `root-packages/`: حزم تتطلب root (اختياري).
- `disabled-packages/`: حزم معطلة.
- `ndk-patches/`: تعديلات على NDK/bionic.
- `scripts/`: `build-package.sh` و`build-all.sh`.
- `repo.json`: بيانات المستودع.
- Docker image: `termux/package-builder`.

الحزم تُبنى في بيئة Docker مع cross-compilation، وتُنتج ملفات `.deb` موقعة.

### 5.2 `x11-repo`

مستودع منفصل لحزم X11 وسطح المكتب (XFCE، Openbox، وغيرها). يُضاف بـ:

```bash
pkg install x11-repo
```

سبب الفصل: حجم الحزم واعتمادياتها الكبيرة، وتجنب إثقال المستودع الأساسي.

### 5.3 `proot-distro`

مدير حاويات مستخدم يعتمد على `proot`:

- ينسخ/يثبّت توزيعة كاملة (Debian, Ubuntu, Alpine, Arch, ...) في `containers/<name>/`.
- لا يحتاج root؛ يستخدم `ptrace` لمحاكاة `chroot` و`mount`.
- يدعم OCI registries: Docker Hub، GHCR، Quay، وغيرها.
- يحل manifest lists حسب المعمارية أو `--architecture`.
- يتحقق من SHA-256 لكل layer.
- يطبّق whiteouts (`.wh..wh..opq`, `.wh.<name>`)، ويحوّل hardlinks إلى نسخ، ويتجاهل device nodes/FIFO.
- يصلح `/etc/resolv.conf` (8.8.8.8/8.8.4.4)، `/etc/hosts`، ويضيف مستخدم `aid_<name>`.
- يحفظ `containers/<name>/manifest.json`.
- يدعم التثبيت من tarball أو OCI archive محلي.

الأداء: `proot` يضيف overhead على syscalls، لكنه أبسط وأكثر أمانًا من chroot مع SELinux معطّل.

### 5.4 `termux-api` والملحقات

- `termux-api` يوفر أوامر للوصول إلى Android API (كاميرا، موقع، SMS، إشعارات، حافظة، مستشعرات) عبر تطبيق `Termux:API` المشارك في UID.
- `Termux:Boot`, `:Float`, `:Styling`, `:Tasker`, `:Widget` ملحقات تعتمد على نفس الآلية.
- `RUN_COMMAND` هو الجسر القياسي للأتمتة.

### 5.5 جدول العلاقات

| المكوّن | يعتمد على | يوفّر |
|---|---|---|
| Termux app | bootstrap, Android | shell, PTY, service, package manager |
| termux-packages | NDK, Docker | حزم `.deb` |
| x11-repo | termux-packages | حزم X11/desktop |
| Termux:X11 | Termux app + APK | X server + display |
| proot-distro | Termux + proot | توزيعات كاملة |
| termux-api | Termux app | وصول إلى Android APIs |

---

## 6. دليل عملي: تشغيل X11 مع proot-distro

### 6.1 المتطلبات

- Termux من GitHub (يُفضّل) أو F-Droid.
- تطبيق Termux:X11 (nightly).
- حزمة `termux-x11-nightly` من `x11-repo`.
- مساحة تخزين كافية (سطح مكتب كامل قد يتجاوز 2GB).

### 6.2 التثبيت

```bash
pkg update
pkg install x11-repo
pkg install termux-x11-nightly
```

ثبّت APK:

```bash
# من داخل Termux، بعد تنزيل APK إلى ~/storage/downloads
termux-open ~/storage/downloads/termux-x11-universal-debug.apk
```

أو استخدم نسخة `sharedUid` إذا كان Termux من GitHub:

```bash
termux-open ~/storage/downloads/termux-x11-universal-sharedUid-debug.apk
```

### 6.3 تشغيل مباشر

```bash
termux-x11 :0 -xstartup "xfce4-session"
```

أو:

```bash
termux-x11 :0 -- dbus-launch --exit-with-session xfce4-session
```

أو تشغيل الخادم في الخلفية:

```bash
termux-x11 :0 &
export DISPLAY=:0
xfce4-session
```

### 6.4 مع proot-distro

من Termux (خارج الحاوية):

```bash
pkg install proot-distro
proot-distro install debian
termux-x11 :0 &
proot-distro login debian --shared-tmp
```

داخل الحاوية:

```bash
apt update
apt install xfce4 dbus-x11
export DISPLAY=:0
dbus-launch --exit-with-session xfce4-session
```

`--shared-tmp` ضروري لمشاركة `$TMPDIR/.X11-unix` بين Termux والحاوية.

### 6.5 التشخيص

```bash
# سجلات الخادم
TERMUX_X11_DEBUG=1 termux-x11 :0 -xstartup "xfce4-session"

# التحقق من المنفذ
ss -ltnp | grep 7892

# التحقق من مقبس X
ls -l $TMPDIR/.X11-unix/

# اختبار عميل X بسيط
pkg install x11-utils
DISPLAY=:0 xdpyinfo
```

### 6.6 الإيقاف

```bash
# إيقاف الخادم
pkill -f termux-x11

# إغلاق واجهة Android فقط
am broadcast -a com.termux.x11.ACTION_STOP -p com.termux.x11

# فتح الواجهة
am start -n com.termux.x11/com.termux.x11.MainActivity
```

### 6.7 أخطاء شائعة

| العرض | السبب المحتمل | الحل |
|---|---|---|
| شاشة سوداء مع مؤشر | مسار EGL غير مدعوم | أضف `-legacy-drawing` |
| ألوان معكوسة | ترتيب BGRA/RGBA | أضف `-force-bgra` |
| `$TMPDIR is not set` | تشغيل من بيئة غير Termux | اضبط `TMPDIR` |
| `$XKB_CONFIG_ROOT is not set` | حاوية بلا xkb | اضبط `XKB_CONFIG_ROOT` أو ثبّت `xkeyboard-config` |
| `Cannot open display :0` | الخادم لم يبدأ أو المقبس مختلف | تحقق من `TMPDIR` و`DISPLAY` |
| تطبيقات سطح المكتب بطيئة | cpuset/phantom killer | استخدم `sharedUid` وقلّل العمليات |
| `bootstrap_error_not_primary_user_message` | مستخدم ثانوي | استخدم المستخدم الأساسي |
| فشل التثبيت فوق نسخة أخرى | توقيع مختلف | أزل النسخة القديمة أولًا |

---

## 7. مقارنات

### 7.1 Termux مقابل proot-distro مقابل chroot

| البُعد | Termux | proot-distro | chroot |
|---|---|---|---|
| root مطلوب | لا | لا | نعم غالبًا |
| عزل | لا (UID التطبيق) | جزئي (ptrace) | كامل نسبيًا |
| أداء | الأفضل | جيد مع overhead | الأفضل |
| توزيعة كاملة | لا | نعم | نعم |
| SELinux | متوافق | متوافق | غالبًا يحتاج تعطيل |
| الاستخدام | أدوات CLI | سطح مكتب/تطوير | خوادم/اختبار |

### 7.2 Termux:X11 مقابل VNC مقابل Wayland

| البُعد | Termux:X11 | VNC | Wayland |
|---|---|---|---|
| البروتوكول | X11 أصلي | RFB | Wayland |
| النسخ | شبه صفري | ترميز/فك ترميز | حسب compositor |
| التأخير | منخفض | أعلى | منخفض (نظريًا) |
| التوافق | تطبيقات X11 | أي عميل VNC | تطبيقات Wayland |
| التعقيد | عالٍ | متوسط | عالٍ |
| Android | مخصص | عام | غير عملي |

### 7.3 `targetSdk 28` مقابل `targetSdk 34`

| البُعد | 28 | 34 |
|---|---|---|
| `sharedUserId` | مدعوم | ممنوع |
| تنفيذ من دليل التطبيق | مسموح | مقيّد |
| Foreground Service | أسهل | قيود أنواع |
| Notification Permission | غير مطلوب | مطلوب (Android 13+) |
| التوافق | أعلى مع Termux | أعلى مع سياسات Google |

---

## 8. خريطة الكود

### 8.1 Termux

| الملف | الدور |
|---|---|
| `app/src/main/java/com/termux/app/TermuxActivity.java` | الواجهة الرئيسية |
| `app/src/main/java/com/termux/app/TermuxService.java` | الخدمة والجلسات |
| `app/src/main/java/com/termux/app/TermuxInstaller.java` | تثبيت bootstrap |
| `app/src/main/java/com/termux/app/RunCommandService.java` | تنفيذ خارجي |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java` | جلسة PTY |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` | مخزن الشاشة |
| `terminal-emulator/src/main/java/com/termux/terminal/JNI.java` | جسر JNI |
| `terminal-emulator/src/main/jni/termux.c` | إنشاء العملية و PTY |
| `terminal-view/src/main/java/com/termux/view/TerminalView.java` | العرض واللمس |
| `termux-shared/.../TermuxConstants.java` | الثوابت والمسارات |
| `termux-shared/.../TermuxShellEnvironment.java` | ملف البيئة |

### 8.2 Termux:X11

| الملف | الدور |
|---|---|
| `lorie/src/main/cpp/lorie/InitOutput.c` | تهيئة الشاشة، DRI3، Present، RandR |
| `lorie/src/main/cpp/lorie/InitInput.c` | أجهزة الإدخال |
| `lorie/src/main/cpp/lorie/renderer.cpp` | EGL/GLES، النسيج، المؤشر |
| `lorie/src/main/cpp/lorie/activity.cpp` | JNI للعرض والإدخال |
| `lorie/src/main/cpp/lorie/cmdentrypoint.cpp` | تشغيل الخادم، Knock، socketpair |
| `lorie/src/main/cpp/lorie/buffer.c` | LorieBuffer وSCM_RIGHTS |
| `lorie/src/main/cpp/lorie/lorie.h` | العقود والثوابت والأحداث |
| `lorie/src/main/java/com/termux/x11/LorieView.java` | SurfaceView والمدخلات |
| `lorie/src/main/java/com/termux/x11/MainActivity.java` | الاتصال والإعدادات |
| `lorie/src/main/java/com/termux/x11/CmdEntryPoint.java` | نقطة دخول CLI |
| `shell-loader/src/main/java/com/termux/x11/Loader.java` | تحميل APK والتحقق |
| `shell-loader/scripts/termux-x11.in` | سكربت التشغيل |

---

## 9. ملاحظات بحثية

1. **لم تُقرأ شيفرة Xorg الأساسية** لأن الـ submodule غير موجود في الأرشيف الذي تم تنزيله (`lorie/src/main/cpp/xserver` فارغ). التحليل يغطي طبقة DDX والجسر، وليس نواة Xorg.
2. **إصدار Xorg** من `recipes/xserver.cmake`: `VENDOR_RELEASE 12101099` أي 21.1.9 تقريبًا.
3. **أرقام الأسطر** تقريبية وتعتمد على `wc -l` للنسخة الحالية.
4. **`termux-app` يعلن 0.118.0 في Gradle**، لكن الإصدارات المنشورة تصل إلى 0.118.3؛ يُبنى الإصدار النهائي بتمرير `TERMUX_APP_VERSION_NAME`.
5. **نسخة Google Play** ليست نفس المشروع: أُزيل `sharedUserId` وتغيّرت السلوكيات، فلا تخلط بينها وبين نسخة GitHub.
6. **التحليل الأمني** ليس اختبار اختراق؛ هو مراجعة معمارية. أي استخدام إنتاجي يحتاج تدقيقًا مستقلًا.

---

## 10. المصادر

- Termux app: <https://github.com/termux/termux-app>
- Termux:X11: <https://github.com/termux/termux-x11>
- Termux packages: <https://github.com/termux/termux-packages>
- proot-distro: <https://github.com/termux/proot-distro>
- Termux wiki: <https://wiki.termux.com/>
- X.Org documentation: <https://www.x.org/releases/current/doc/>
- Android NDK `AHardwareBuffer`: <https://developer.android.com/ndk/reference/group/a-hardware-buffer>
- Android `SurfaceView` / `Surface`: <https://developer.android.com/reference/android/view/SurfaceView>
