# DroidDesk — تقرير معمّق للبنية والقدرات والمخاطر (المرحلة 2)

> **المشروع:** `orailnoor/DroidDesk`
> **تاريخ التحليل:** 2026-10-21
> **نوع التحليل:** تحليل ساكن (static analysis) للكود المصدري، README، ملفات الامتثال، إصدارات GitHub، وissues العامة.
> **حدود مهمة:** لم يتم تشغيل التطبيق على جهاز Android فعلي، ولم تُجرَ قياسات أداء مستقلة. كل الأحكام الأدائية أو السلوكية المبنية على تقارير المستخدمين موسومة بذلك.

---

## 1. الملخص التنفيذي

`DroidDesk` مشروع طموح يحاول تحويل Android إلى محطة عمل Linux كاملة، بواجهة Flutter حديثة، وثلاثة مسارات تشغيل:

1. **وضع native بدون root:** يستخدم حزمة Termux مُعاد ترحيلها إلى prefix التطبيق، مع `LD_PRELOAD` hook لإعادة كتابة المسارات، وخادم X11 مضمّن.
2. **وضع chroot مع root:** ينزّل Ubuntu 24.04 base، يستخرجه، يثبّت XFCE، ثم يشغّل جلسة XFCE داخل `chroot` حقيقي مع X11 مضمّن.
3. **Debian اختياري عبر PRoot:** يظهر كتطبيق إضافي `proot_debian` في وضع native فقط.

الفكرة المعمارية قوية، لكن التنفيذ الحالي **ليس بيئة إنتاج ولا بيئة أمنية**:

- وضع chroot **يفرض llvmpipe** (رسم برمجي) رغم ادعاء README أن الوضعين يستخدمان Turnip/Zink على Adreno. هذا تعارض جوهري بين الادعاء والكود.
- حوار root يصف chroot بأنه «best performance»، وهو ادعاء غير صحيح للرسوميات على الأقل؛ chroot أفضل للتوافق وroot، وnative أفضل لمسار GPU على Adreno.
- تنزيل rootfs **بلا SHA-256 أو توقيع**، والاستخراج عبر `tar` النظامي بدون فحص صريح لمسارات الأرشيف. هذه مخاطرة supply chain مباشرة، خصوصًا أن الناتج يعمل لاحقًا بصلاحيات root.
- chroot يربط `/dev` و`/proc` و`/sys` كاملة، ويعمل كـroot، بلا mount/PID namespaces؛ أي أنه **ليس حدًا أمنيًا**.
- `targetSdk 28` مقصود لتفادي قيود `exec/W^X`، لكنه يمنع النشر عبر Play ويجعل التحديث sideload.
- الامتثال لـGPL-3.0 غير مكتمل: `COMPLIANCE.md` يعترف بعدم توفر exact source/build provenance لبعض المكتبات المسبقة البناء.
- المشروع سريع التطور لكن bus factor منخفض (مساهمان أساسيان)، و84 issue مفتوحة، وأكثرها أعطال تركيب/شاشة سوداء/أداء.

**الحكم العملي:** مناسب للمتحمسين والباحثين الذين يقبلون المخاطر ويجيدون التعامل مع Linux/Android، وليس مناسبًا كبيئة عمل إنتاجية أو أمنية أو كبديل موثوق لـVM.

---

## 2. نطاق التحليل والمنهجية

### 2.1 المصادر

- المستودع العام `orailnoor/DroidDesk`، فرع `main`.
- الكود المصدري الكامل بعد فك `tarball` في `/tmp/opencode/research/DroidDesk-main`.
- `README.md`, `COMPLIANCE.md`, `THIRD_PARTY_NOTICES.md`, `NOTICE.md`, `APP_TROUBLESHOOTING.md`.
- `AndroidManifest.xml`, `build.gradle.kts`, `pubspec.yaml`.
- GitHub Releases وIssues وCommits عبر GitHub API.
- تقرير المرحلة 1: `/root/Desktop/deck/termux-x11-architecture.md`.

### 2.2 ما تم التحقق منه بالكود

- مسار X11 من `X11ServerService` إلى `X11ServiceClient` إلى `LorieView`.
- تشغيل الخادم في عملية `:x11`.
- bootstrap native وإعادة كتابة المسارات و`socket_hook`.
- إعداد `chroot` و`bind mounts` و`llvmpipe`.
- تنزيل rootfs واستخراجه.
- إعداد الواجهة وحوار root واختيار DE.
- تحديث APK والتحقق من التوقيع.
- Wayland placeholder.

### 2.3 ما لم يتم التحقق منه ديناميكيًا

- الأداء الفعلي على Adreno/Mali/Samsung.
- وجود `pgrep` داخل rootfs بعد التثبيت.
- دعم `tar` في toybox لـ`.tar.xz` (Kali).
- سلوك `socket_hook` مع تطبيقات static/Go/Rust.
- إصلاحات المتصفحات داخل chroot.
- سياسة SELinux على أجهزة Samsung تحديدًا.
- جودة الصوت في chroot.

---

## 3. هوية المشروع ونضجه

| البند | القيمة |
|---|---|
| المستودع | `orailnoor/DroidDesk` |
| الترخيص | GPL-3.0-only |
| اللغة الأساسية | Kotlin + Dart/Flutter + C/C++ |
| الفرع | `main` |
| النجوم | نحو 4,337 |
| Fork | نحو 403 |
| Issues مفتوحة | 84 |
| Contributors أساسيون | 2 (`techjarves`, `orailnoor`) |
| الإصدارات | 6 فقط |
| أحدث إصدار | v1.3.1 — 2026-10-14 — APK 40.4MB — 3,807 تحميلات |
| v1.0.0 | APKان بحجم 106.3MB — 28,906 تحميلات |
| إنشاء المستودع | 2026-04-20 |
| آخر push | 2026-10-14 |

### قراءة النضج

- المشروع حقيقي وليس mockup: يوجد APK، إصدارات، مشكلات ميدانية، وكود معقد.
- لكن سرعة الإصدارات وعدد الـissues يشيران إلى **تطور تفاعلي سريع** أكثر من كونه منتجًا مستقرًا.
- وجود مساهمين أساسيين اثنين يعني bus factor منخفضًا.
- الادعاءات التسويقية في README تسبق أحيانًا مستوى التنفيذ.

---

## 4. البنية العامة

```text
┌─────────────────────────────────────────────────────────────┐
│                        Flutter UI                           │
│  setup wizard / home / app catalog / terminal / settings   │
└───────────────────────────┬─────────────────────────────────┘
                            │ MethodChannel
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                    MainActivity (Kotlin)                    │
│  root detection / setup / status / app integration / OTA   │
└───────────────┬───────────────────────────────┬─────────────┘
                │                               │
                ▼                               ▼
┌──────────────────────────────┐   ┌──────────────────────────┐
│ LinuxRuntime                 │   │ ChrootRuntime            │
│ native Termux prefix         │   │ Ubuntu rootfs + su       │
│ bootstrap + socket_hook      │   │ bind mounts + chroot     │
└──────────────┬───────────────┘   └────────────┬─────────────┘
               │                                │
               │ DISPLAY=:0                     │ DISPLAY=:0
               ▼                                ▼
┌─────────────────────────────────────────────────────────────┐
│ X11ServerService — process :x11                             │
│ CmdEntryPoint + libXlorie + X server :0                     │
└───────────────────────────┬─────────────────────────────────┘
                            │ Binder ParcelFileDescriptor
                            ▼
┌─────────────────────────────────────────────────────────────┐
│ DesktopActivity                                             │
│ LorieView (native surface/input)                            │
└─────────────────────────────────────────────────────────────┘
```

### المكونات الرئيسية

- **Flutter UI:** `app/lib`.
- **Runtime Kotlin:** `app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime`.
- **Termux:X11 المعدّل:** `app/android/app/src/main/java/com/termux/x11`.
- **X11 native:** `libXlorie.so` داخل `jniLibs/arm64-v8a`.
- **bootstrap:** `bootstrap-aarch64.zip` (~30MB) وعمليات relocation.
- **rootfs:** تنزيل خارجي، ليس مضمّنًا في APK.
- **Android app bridge:** `LocalServerSocket` لفتح تطبيقات Android من سطح المكتب.
- **Wayland compositor:** هيكل غير مكتمل في `compositor_jni.cpp` — لا يوجد Wayland فعلي.

---

## 5. مسار X11 والعرض

### 5.1 الخادم في عملية منفصلة

`AndroidManifest.xml` يعلن خدمة X11 بعملية `:x11`:

```xml
android:process=":x11"
```

هذا قرار جيد: عزل خادم X عن عملية الواجهة، وتقليل تأثير crash الخادم على Flutter.

`X11ServerService` يشغّل:

```text
:0 -nolock
```

عبر `CmdEntryPoint`، ويحمّل `libXlorie` داخل العملية. لا يوجد `app_process` ولا `Loader` كما في Termux:X11 التقليدي.

### 5.2 نقل الـframebuffer إلى الواجهة

```text
X11ServerService
  └─ startServer()
       ├─ serverPid
       └─ xConnection (ParcelFileDescriptor)
            │ Binder
            ▼
X11ServiceClient
  └─ LorieView.connect(int fd)
       └─ native surface/input/rendering
```

`LorieView` هو المسؤول عن:

- إنشاء surface.
- تمرير أبعاد النافذة.
- إرسال أحداث اللمس/الفأرة/لوحة المفاتيح.
- الاتصال بقناة X11 عبر `connect(fd)`.

### 5.3 توقيت بدء الجلسة

`DesktopActivity` لا يبدأ جلسة Linux إلا بعد اتصال X11 وإنشاء الـsurface. هذا يفسر لماذا لا يظهر سطح المكتب قبل أن يكون `LorieView` جاهزًا.

`LorieView` يرسل `window change` مبكرًا قبل اتصال `xConnection`، ويحتفظ native بالأبعاد؛ الهدف تجنّب framebuffer الافتراضي `1280x1024` والسماح للجلسة بالبدء بالدقة الصحيحة.

### 5.4 مقبس X11

- X11 server socket يقع في `files/tmp/.X11-unix` (تعليقات `LinuxRuntime` و`ChrootRuntime` تؤكد ذلك).
- في chroot يُربط هذا المجلد إلى `/tmp/.X11-unix` عبر `bind mount`.
- في native يعيد `socket_hook` توجيه:
  - `/tmp/.X11-unix/X0`
  - `/data/data/com.termux/files/usr/tmp/.X11-unix/X0`

إلى `$TMPDIR/.X11-unix/X0`.

### 5.5 الدقة وDPI

- chroot يكتب `~/.Xresources` بقيم ثابتة: `Xft.dpi: 140`, `Xcursor.size: 32`.
- chroot ينفّذ `xrandr --auto` بعد بدء XFCE.
- لا توجد واجهة واضحة لتغيير الدقة/DPI ضمن التطبيق.
- Issue #53 يؤكد أن الدقة/refresh/DPI تبدو مقفلة عند بعض المستخدمين.

### 5.6 Input

`X11InputController.configureDisplayScale()` يضبط مقياس الإدخال، ثم تُحقن الأحداث عبر `LorieView`. لا توجد طبقة `libinput` منفصلة؛ الإدخال يمر عبر جسر Termux:X11.

---

## 6. الوضع native (بدون root)

### 6.1 الفكرة

بدل تشغيل Termux كتطبيق منفصل، يستخرج DroidDesk حزمة bootstrap مبنية لـ`com.termux` إلى prefix خاص به، ثم يعيد كتابة المسارات والـshebangs وELF runpaths.

### 6.2 bootstrap والترحيل

- يستخرج `bootstrap-aarch64.zip` إلى `filesDir/usr`.
- يضع علامة `.bootstrap_extracted`.
- `patchShebangs()` يعيد كتابة `#!/data/data/com.termux/files/usr/bin/...` إلى prefix التطبيق.
- `patchElfRunpaths()` يعدّل `DT_RPATH`/`DT_RUNPATH` في ELF.
- `patchEmbeddedXfcePaths()` يصلح مسارات XFCE المضمّنة.
- `relocateAllScripts()` يمسح نطاقات واسعة، بما فيها `bin`, `libexec`, `share`, `etc`, `var/lib/dpkg/info`.

### 6.3 `socket_hook`

`libsocket_hook.so` هو heart of the native hack. وظيفته:

- اعتراض دوال libc التي تتعامل مع المسارات.
- إعادة كتابة:
  - `/data/data/com.termux/files/usr`
  - `data/data/com.termux/files/usr`
  - `/data/data/com.termux`
  - `/usr/`, `/bin/`, `/lib/`, `/etc/`, `/var/`, `/tmp/`
- توجيه اتصال X11 إلى `$TMPDIR/.X11-unix/X0`.
- اعتراض `dlopen`.

#### نقاط القوة

- يسمح بتشغيل حزم Termux دون إعادة بناء كل شيء لـprefix جديد.
- يعالج مشكلة X11 socket paths.
- يبني الـhook محليًا إن وُجد clang؛ وإلا يستخدم النسخة المسبقة.

#### نقاط الضعف

- **ليس shim ضيقًا:** إعادة كتابة `/usr/`, `/bin/`, `/etc/`, `/var/` عالميًا قد تكسر تطبيقات تتوقع FHS حقيقيًا.
- الثنائيات static أو التي تستدعي syscalls مباشرة تتجاوز الـhook.
- `connect()` يستخدم `static char path_buf[256]`، وهو غير thread-safe.
- `dlopen` hook قد يغيّر سلوك تحميل المكتبات بطرق غير متوقعة.
- أي خطأ في الـhook يظهر كأعطال غامضة في حزم تبدو سليمة.
- لا توجد suite اختبار واسعة تغطي Go/Rust/static binaries.

### 6.4 البيئة

`getTermuxEnv()` يضبط:

- `PREFIX`, `TMPDIR`, `HOME`, `PATH`, `SHELL`.
- `TERMUX_APP__PACKAGE_NAME`, `TERMUX__PREFIX`, `TERMUX__HOME` لدعم `proot-distro` 5.x.
- `XDG_CONFIG_DIRS` يتضمن حرفيًا:
  `/data/data/com.termux/files/usr/etc`
  كـfallback لـXFCE. هذا اعتراف ضمني بأن relocation غير كامل.

### 6.5 الرسوميات

- `hasAdrenoGpu()` يفحص فقط `/dev/kgsl-3d0`.
- إذا وُجد Adreno و`freedreno_icd.aarch64.json`:
  - `VK_ICD_FILENAMES=freedreno`
  - `MESA_LOADER_DRIVER_OVERRIDE=zink`
  - `GALLIUM_DRIVER=zink`
- غير ذلك:
  - `LIBGL_ALWAYS_SOFTWARE=true`
  - `MESA_LOADER_DRIVER_OVERRIDE=swrast`
  - `GALLIUM_DRIVER=llvmpipe`

`ensureGraphicsPackages()` يثبّت `mesa libc++`، ويضيف `mesa-vulkan-icd-freedreno` على Adreno، ويعالج تعارض `libc++` القديم مع Mesa.

**الخلاصة:** native هو المسار الوحيد الذي قد يحصل على Turnip/Zink فعليًا، وفقط على Adreno.

### 6.6 الجلسة والصوت

`startSession()`:

- يصلح الحزم نصف المثبتة.
- يثبّت `xclip` إن لزم.
- يثبّت `devilspie2` إن لزم.
- يثبّت XFCE mobile profile.
- يبدأ `dbus-daemon` بجلسة خاصة.
- يبدأ PulseAudio مع `module-aaudio-sink` على Android 8+.
- يشغّل `xfce4-session` مباشرة بدل `startxfce4` لتجنّب fallback إلى `/bin/sh`.

هذا أكثر نضجًا من مجرد `startxfce4`؛ لكنه أيضًا يعتمد على تفاصيل Termux وAndroid هشة.

### 6.7 التطبيقات الاختيارية في native

`getOptionalAppsStatus()` يدعم:

- `firefox`
- `code_oss`
- `nodejs`
- `imagemagick`
- `proot_debian`

`proot_debian` يثبّت `proot`, `proot-distro`, وينزّل Debian minimal، ثم ينشئ `start-debian`.

**لا توجد أزرار لـBlender أو Metasploit أو Local AI** في الكتالوج، رغم ذكرها في README. يمكن تثبيتها يدويًا عبر الطرفية إن توفرت الحزم.

---

## 7. وضع chroot (root)

### 7.1 الفكرة

`ChrootRuntime` ينزّل Ubuntu 24.04 base arm64، يستخرجه إلى `filesDir/rootfs`، يثبّت XFCE وحزم Mesa، ثم يشغّل:

```text
su -c "chroot <rootfs> /bin/bash -c <script>"
```

### 7.2 mounts

`ensureMounts()` يربط:

- `/dev` (bind)
- `/dev/pts` (devpts)
- `/dev/shm` (tmpfs)
- `/proc` (bind)
- `/sys` (bind)
- `/run` (tmpfs)
- `/tmp` (tmpfs)

ثم يصلح symlinks في `/dev`، وينشئ `/tmp/.X11-unix`, `/tmp/runtime-root`, `/root`.

`bindX11Socket()` يربط:

```text
files/tmp/.X11-unix -> rootfs/tmp/.X11-unix
```

### 7.3 تثبيت سطح المكتب

`installDesktopEnvironment()`:

- يثبّت `locales`, `ca-certificates`, `wget`, `curl`, `dbus-x11`, `xclip`, `x11-utils`.
- يثبّت `mesa-vulkan-drivers`, `mesa-opencl-icd`, `libgl1-mesa-dri`, `libglx-mesa0`, `libgl1`, `vulkan-tools`.
- يثبّت `xfce4`, `xfce4-terminal`, `xfce4-whiskermenu-plugin`, `thunar`, `mousepad`, `devilspie2`.
- يثبّت `papirus-icon-theme`, `adwaita-icon-theme-full`, `fonts-noto-core`.
- يثبّت `git`, `nano`, `htop`, `python3`, `python3-pip`, `openssh-client`.

### 7.4 التعارض الجوهري: llvmpipe

بعد تثبيت Mesa، `configureChrootRootfs()` يكتب:

```bash
export LIBGL_ALWAYS_SOFTWARE=true
export GALLIUM_DRIVER=llvmpipe
export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe
```

ثم `startSession()` ينفّذ:

```bash
. /etc/profile.d/droiddesk-ha.sh
```

النتيجة: **كل جلسة chroot تعمل برسم برمجي**، حتى لو كان الجهاز Adreno.

هذا يتعارض مع:

- README الذي يقول إن الوضعين يستخدمان Turnip/Zink على Adreno.
- حوار root الذي يعد بـ«best performance».
- وجود `mesa-vulkan-drivers` و`vulkan-tools` في التثبيت، إذ لا يتم استخدامها فعليًا.

### 7.5 تعارض ملفات profile

هناك ملفان متعارضان:

1. `RootfsManager.configureRootfs()` يكتب `/etc/profile.d/droiddesk.sh` بقيم:
   - `GALLIUM_DRIVER=zink`
   - `MESA_LOADER_DRIVER_OVERRIDE=zink`
   - `MESA_GL_VERSION_OVERRIDE=4.6`
   - `MESA_GLES_VERSION_OVERRIDE=3.2`
   - `PULSE_SERVER=127.0.0.1`

2. `ChrootRuntime.configureChrootRootfs()` يكتب `/etc/profile.d/droiddesk-ha.sh` بقيم llvmpipe.

`startSession()` يعتمد `droiddesk-ha.sh`. لكن أي shell آخر قد يقرأ `droiddesk.sh` ويحصل على zink بلا Vulkan فعلي، ما يسبب أخطاء OpenGL متفرقة.

هذا مثال واضح على **عدم وجود مصدر حقيقة واحد** لإعداد الرسوميات.

### 7.6 `close_range` shim

`close_range_compat.c` يعرّف:

```c
int close_range(unsigned int first, unsigned int last, int flags) {
    errno = ENOSYS;
    return -1;
}
```

ويُبنى بـgcc داخل chroot إلى:

```text
/usr/local/lib/libdroiddesk-close-range.so
```

ثم:

```bash
LD_PRELOAD=/usr/local/lib/libdroiddesk-close-range.so
```

السبب حسب تعليق الكود: بعض kernels المبنية على Android 5.4 ترفض `close_range(3, UINT_MAX, 0)` بـ`EINVAL`، وGLib تحتاج fallback. إرجاع `ENOSYS` يجبر GLib على المسار الآمن.

**تصحيح مهم:** هذا ليس نفس مشكلة seccomp SIGSYS المذكورة في تعليقات `installRepoPackages` بخصوص maintainer scripts. المشكلتان منفصلتان:
- `close_range`: kernel EINVAL → shim.
- postinst scripts: SIGSYS تحت seccomp → تعطيل/إصلاح scripts.

### 7.7 الصوت

`startSession()` في chroot **لا يبدأ PulseAudio**، ولا يثبّت `pulseaudio` صراحة. `RootfsManager` يضع `PULSE_SERVER=127.0.0.1` في `droiddesk.sh`، لكن لا يوجد خادم صوت يعمل.

النتيجة المتوقعة: صوت chroot غير موثوق أو غائب. Issue #39 (Motorola audio) متسقة مع هذا.

### 7.8 جاهزية سطح المكتب

`waitForDesktopReady()` يستخدم:

```text
pgrep -x xfdesktop
pgrep -x xfce4-panel
```

داخل chroot عبر `/usr/bin/pgrep`.

**مشكلة محتملة:** `pgrep` يأتي من `procps`، ولم يتم التأكد من تثبيته ضمن حزم XFCE/core. إذا لم يكن موجودًا، سينتظر التطبيق 45 ثانية كاملة ثم يعرض `revealDesktop(false)` رغم أن سطح المكتب قد يكون ظاهرًا.

هذا قد يفسر بعض تقارير «الشاشة السوداء» أو التأخير الطويل.

---

## 8. إدارة rootfs والتنزيل

### 8.1 المصادر

`RootfsManager.DISTRO_URLS`:

- Ubuntu 24.04.4 base arm64:
  `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz`
- Alpine 3.20 minirootfs
- Kali NetHunter minimal arm64 `.tar.xz`

### 8.2 الواجهة

رغم وجود ثلاثة مصادر:

- `_selectedDistro = 'ubuntu'` في `AppState`.
- لا توجد شاشة اختيار distro.
- `normalizedDesktop()` يفرض `xfce4`.
- `RootfsManager` يدعم LXQt/MATE/KDE، لكن `ChrootRuntime` والواجهة يثبّتان XFCE فقط.

**النتيجة:** Alpine وKali وLXQt/MATE/KDE كود غير مستخدم فعليًا من المسار الطبيعي.

### 8.3 غياب التحقق

لا يوجد:

- SHA-256 متوقع.
- توقيع GPG.
- مقارنة hash من GitHub release.
- فحص مصدر التنزيل بعد redirect.

هذا يعني أن أي:

- اختراق لـ`cdimage.ubuntu.com` أو DNS.
- proxy وسيط.
- تلاعب في ملف rootfs.

قد يؤدي إلى استخراج rootfs خبيث ثم تشغيله بصلاحيات root.

### 8.4 التنزيل والاستئناف

- يدعم `Range` للاستئناف.
- لا يستخدم `If-Range` أو ETag.
- إذا تغيّر الملف على الخادم بين الجلسات، قد يُلحق بايتات ملف جديد بملف قديم.
- يتعامل مع 301/302/303 فقط، وليس 307/308.
- عند 416 يفترض أن الملف مكتمل ويكتب `configFile` دون تحقق.

### 8.5 الاستخراج

يستخدم:

```text
tar zxf <tarball> -C rootfs
tar Jxf <tarball> -C rootfs   # Kali
```

- لا يوجد `--no-same-owner` أو `--numeric-owner` صريح.
- لا يوجد فحص صريح لمسارات الأرشيف (path traversal).
- يتجاهل فشل `chown` إذا نجح استخراج `bin`.
- يعتمد على `tar` الخاص بـtoybox.
- دعم `xz` في toybox غير مضمون؛ Kali قد يفشل على أجهزة معينة.

### 8.6 تعارض التوثيق

تعليق `RootfsManager` يقول:

```text
User selects distro in Flutter wizard
Download tarball (~300-500 MB compressed)
```

لكن:

- لا يوجد اختيار distro في الواجهة.
- Ubuntu base الفعلي ~30MB مضغوط، وليس 300–500MB.

التوثيق الداخلي قديم ولا يطابق السلوك.

### 8.7 كود ميت

`RootfsManager.installDesktopEnvironment()` موجود لكنه **لا يُستدعى من أي مكان**. كما أنه يستخدم `runtime.executeCommand()` الخاص بـnative Termux، وليس chroot؛ لو استُدعي فسيكون مسارًا خاطئًا. هذا بقايا معمارية قديمة.

---

## 9. الإعداد والواجهة

### 9.1 Wizard

`SetupProgressScreen`:

1. يفحص التخزين؛ إذا أقل من 2048MB يعرض تحذيرًا لكنه يسمح بالمتابعة.
2. يفحص root.
3. إذا وُجد root يعرض حوارًا:
   > Your device is rooted. Continue with the rooted chroot runtime for the best performance and full Linux support?
4. إذا اختار المستخدم `Cancel`، **يتوقف الإعداد بالكامل** ولا يتحول إلى native.
5. إذا اختار `Continue with root`، يشغّل chroot.

### 9.2 مشكلة الحوار

- المستخدم الجذري لا يستطيع اختيار native من الواجهة.
- `Cancel` يعني «لا تثبّت شيئًا»، وليس «استخدم native».
- الوصف «best performance» مضلل بسبب llvmpipe.
- لا يوجد شرح للفرق بين:
  - native: GPU أفضل، بلا root.
  - chroot: root/apt، رسم برمجي.

### 9.3 اختيار DE

`DEInstallScreen` يبدأ التثبيت تلقائيًا، بلا اختيار DE. `AppState._selectedDE` ثابت `xfce4`. هذا قرار مقصود لتبسيط المنتج، لكنه يجعل فروع LXQt/MATE/KDE في الكود غير قابلة للوصول.

### 9.4 التطبيقات

`AppCatalogScreen` يعرض:

- Firefox
- Code OSS
- Node.js
- ImageMagick
- Debian Compatibility (`proot_debian`) — فقط في native وليس root.

`home_screen` يعرض «Debian shell» فقط إذا كان `proot_debian` مثبتًا و`!hasRoot`.

### 9.5 تجربة المستخدم

نقاط قوة:

- واجهة حديثة، داكنة، متسقة.
- Wizard واضح.
- شاشة تقدم مع سجل.
- كتالوج تطبيقات.
- طرفية مدمجة.
- إعدادات سطح مكتب.
- تكامل تطبيقات Android.

نقاط ضعف:

- رسائل الخطأ قد تكون عامة.
- لا يوجد تشخيص مدمج للـGPU/OpenGL.
- لا يوجد خيار «native على جهاز root» من الواجهة.
- لا يوجد تحذير أمني واضح عند تفعيل chroot.

---

## 10. المزايا الوظيفية

- خادم X11 مضمّن، بلا VNC، في عملية منفصلة.
- Surface rendering عبر `LorieView` مع إدخال لمس/فأرة/لوحة مفاتيح.
- native بدون root عبر bootstrap وrelocation.
- chroot حقيقي مع root، suitable لـapt وroot-only tools.
- Debian اختياري عبر PRoot.
- دعم Adreno Turnip/Zink في native.
- دعم llvmpipe كـfallback.
- PulseAudio + AAudio في native.
- Clipboard sync.
- XFCE mobile profile + devilspie2 maximize.
- تكامل تطبيقات Android في سطح المكتب.
- تحديث APK داخل التطبيق مع تحقق توقيع.
- GPL-3.0 ومحاولة توثيق الامتثال.

---

## 11. الأداء

### 11.1 native

- على Adreno: Turnip + Zink، قد يكون أفضل مسار رسومي.
- على غير Adreno: llvmpipe، برمجي.
- `LD_PRELOAD` يضيف overhead لكل استدعاء مسار، لكنه عمومًا أقل من تشغيل VM.
- PulseAudio/AAudio يضيف تعقيدًا لكنه ضروري للصوت.

### 11.2 chroot

- llvmpipe دائمًا → بطء في المتصفحات، Blender، compositing، الألعاب.
- `chroot` نفسه لا يضيف virtualisation overhead.
- `bind mounts` و`su` بسيطة.
- لكن `XFCE` + `llvmpipe` + X11 على هاتف = تجربة ثقيلة.
- Issue #85 «super slow» و#98 «takes forever» متسقة مع هذا.

### 11.3 ادعاءات README

- «Blender runs but is laggy» — متوقع جدًا مع llvmpipe.
- «Local AI 5+ tokens/sec» — غير مُتحقق، ويعتمد على model/quantization/CPU.
- «best performance» في حوار root — غير صحيح للرسوميات.
- «direct kernel access» — مضلل: التطبيق يبقى داخل Android sandbox/SELinux، وحتى chroot ليس kernel access.

### 11.4 غياب القياس

لا توجد benchmarks في المستودع تقارن:

- native vs chroot.
- Adreno vs Mali.
- llvmpipe vs Zink.
- X11 عبر LorieView vs VNC.

أي أرقام في README يجب التعامل معها كادعاءات تسويقية غير مُثبتة.

---

## 12. الأمان

### 12.1 نموذج التهديد

المشروع يشغّل:

- تطبيقات Linux غير موثوقة.
- حزمًا من مستودعات خارجية.
- rootfs من الإنترنت.
- جلسة سطح مكتب كاملة.

### 12.2 نقاط قوة

- `AppUpdater` يتحقق من:
  - `packageName`.
  - signing certificate intersect مع التطبيق المثبت.
  - حجم الملف المتوقع.
- هذا يمنع تثبيت APK موقّع بمفتاح مختلف، وهو أفضل من كثير من التطبيقات.
- native يعمل بصلاحيات UID التطبيق، وليس root.
- X11 server في عملية منفصلة.
- rootfs في private storage.

### 12.3 مخاطر عالية

1. **rootfs بلا checksum/توقيع** → supply chain.
2. **chroot يعمل كroot** → أي تطبيق داخل الجلسة يملك root.
3. **`/dev`, `/proc`, `/sys` كاملة** → وصول لأجهزة/معلومات المضيف.
4. **لا namespaces** → لا عزل PID/mount/network.
5. **المتصفحات كroot** → sandbox ضعيف أو معطل.
6. **`targetSdk 28`** → قيود أمان Android الحديثة غير مطبقة بالكامل.
7. **`MANAGE_EXTERNAL_STORAGE`** → وصول واسع للتخزين.
8. **`REQUEST_INSTALL_PACKAGES`** → تحديثات sideload.
9. **`usesCleartextTraffic=true`** → يسمح HTTP غير مشفر إن استُخدم.
10. **`socket_hook` واسع** → قد يخفي سلوك ملفات غير متوقع.

### 12.4 chroot ليس حاوية أمنية

`chroot` يغيّر root directory فقط. لا يمنع:

- الوصول إلى `/proc` المضيف.
- mount operations.
- ptrace حسب SELinux.
- أجهزة `/dev`.
- الشبكة.

مع root، قد يستطيع المستخدم داخل الجلسة التأثير على ما يستطيع `su` الوصول إليه. هذا ليس نموذج عزل مثل VM أو حتى namespace container.

### 12.5 `AppUpdater`

نقاط قوة:

- التحقق من التوقيع قبل `ACTION_VIEW`.
- رفض package name مختلف.
- التحقق من الحجم.

نقاط ناقصة:

- لا SHA-256 منفصل (التوقيع يكفي عمليًا لسلامة APK).
- لا certificate pinning للـAPI.
- لا تحقق من `Content-Type`.
- يعتمد على GitHub API/redirects.

### 12.6 الصلاحيات

من `AndroidManifest.xml`:

- `MANAGE_EXTERNAL_STORAGE`
- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE`
- `REQUEST_INSTALL_PACKAGES`
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- `FOREGROUND_SERVICE`
- `WAKE_LOCK`
- `POST_NOTIFICATIONS`
- `usesCleartextTraffic=true`

هذه صلاحيات ثقيلة، لكنها متسقة مع منتج يشغّل Linux desktop. المشكلة أنها تحتاج شرحًا واضحًا للمستخدم.

---

## 13. الترخيص والامتثال

- التطبيق GPL-3.0-only.
- يستخدم كود Termux:X11 المعدّل (GPL-3.0).
- `NOTICE.md` يوضح عدم الانتماء إلى Termux/Google/إلخ.
- `THIRD_PARTY_NOTICES.md` يذكر المكونات.
- `COMPLIANCE.md` يسرد SHA-256 لبعض المكتبات.

### المشكلة

`COMPLIANCE.md` يعترف بأنه لا يحدد بدقة:

- exact upstream commit.
- patches المحلية.
- build commands.
- reproducible build.
- corresponding source للمكتبات المسبقة مثل `libXlorie.so`.

توزيع APK يحتوي `libXlorie.so` وغيرها دون corresponding source كامل قد يخالف GPL. وجود ملف compliance لا يكفي إذا لم يكن المصدر الفعلي متاحًا.

### المخاطر

- مطالبة compliance من upstream.
- إزالة التطبيق من منصات التوزيع.
- مشاكل قانونية للموزعين.
- عدم قدرة المجتمع على إعادة البناء أو التدقيق.

---

## 14. الادعاءات مقابل الواقع

| الادعاء | الواقع |
|---|---|
| «direct kernel access» | لا. التطبيق داخل Android sandbox/SELinux؛ chroot ليس kernel access. |
| «both modes use Adreno Turnip/Zink» | native فقط، وفقط على Adreno. chroot يفرض llvmpipe. |
| «rooted chroot = best performance» | غير صحيح للرسوميات؛ أفضل للتوافق/root. |
| «does not use VNC» | صحيح داخل التطبيق. VNC موجود فقط في سكربت host/Pi. |
| «Blender laggy» | متوقع، خصوصًا مع llvmpipe. |
| «Local AI 5+ tokens/sec» | غير مُتحقق؛ يعتمد على model/device. |
| «VS Code, Blender, Metasploit, local AI, all of it» | بعضها قابل للتثبيت يدويًا، لكنها ليست مزايا مدمجة. |
| «Ubuntu/Alpine/Kali selectable» | الكود يدعمها، لكن الواجهة تثبّت Ubuntu/XFCE فقط. |
| «modern desktop» | XFCE مع mobile profile؛ عملي لكنه ليس بالضرورة «modern». |

---

## 15. أدلة ميدانية من GitHub

### 15.1 Releases

- 6 إصدارات فقط.
- v1.3.1 (2026-10-14): 40.4MB APK، 3,807 تحميلات.
- v1.0.0: 106.3MB إجمالًا، 28,906 تحميلات.
- v1.1.0: non-root 53.7MB.
- الإصدارات تتالت بسرعة حول 2026-10-11/14، ما يعكس تطورًا مكثفًا.

### 15.2 Issues

| Issue | الموضوع | تعليقات |
|---|---|---|
| #35 | Black screen | 10 |
| #48 | Flatpak | 8 |
| #6 | Everything fails | 7 |
| #68 | SSH/WinSCP | 5 |
| #56 | Adaptation | 5 |
| #7 | Vulkan loader | 5 |
| #71 | LibreOffice | 4 |
| #66 | Samsung DeX conflict | 4 |
| #53 | UI/دقة/DPI مقفلة | 4 |
| #22 | Desktop sync | 3 |
| #97 | Internal storage | 2 |
| #70 | MATE faster | 2 |
| #46/#40 | Debian shell broken | 2 |
| #39 | Motorola audio | 2 |
| #21 | Vulkan loader | 2 |
| #17 | Keyboard/mouse | 2 |
| #15 | Proot menu sync | 2 |
| #98 | Takes forever | 1 |
| #94 | Samsung Android 9 | 1 |
| #93 | Cannot open installed apps | 1 |
| #86 | Install script broken (GFX/python pip) | 1 |
| #85 | Super slow | 1 |

### 15.3 قراءة الأعطال

- **#35:** حل مؤقت `apt install libgl1 -y`؛ المشكلة مرتبطة بـOpenGL drivers وليست مجرد UI.
- **#6:** خطأ `Unable to find package x` على OnePlus 13 وأجهزة أخرى؛ يشير إلى مشاكل مستودعات/حزم وليس جهازًا واحدًا.
- **#53:** يؤكد غياب تحكم الدقة/DPI.
- **#66:** تعارض Samsung DeX، مهم لهواتف Samsung.
- **#86:** سكربت التثبيت يترك النظام معطوبًا في بعض الحالات.
- **#97:** التخزين الداخلي؛ `ChrootRuntime` ينشئ `/mnt/sdcard` لكنه لا يربطه فعليًا.
- **#39:** الصوت، متسق مع غياب PulseAudio في chroot.
- **#85/#98:** الأداء، متسق مع llvmpipe.

### 15.4 حدود الأدلة

- Issues تعكس تجارب مستخدمين، وليست اختبارات مضبوطة.
- بعض المشكلات قد تكون أُصلحت في v1.3.1.
- لا توجد بيانات أداء منضبطة.

---

## 16. المقارنة مع Termux وTermux:X11 والبيئة الحالية

| البُعد | Termux | Termux:X11 | DroidDesk native | DroidDesk chroot |
|---|---|---|---|---|
| صلاحيات | UID التطبيق | UID التطبيق | UID التطبيق | root |
| X server | لا | `app_process` + Loader | `libXlorie` في `:x11` | نفسه |
| GPU | لا | Turnip/Zink حسب الجهاز | Turnip/Zink على Adreno، وإلا llvmpipe | llvmpipe دائمًا |
| التوزيعة | Termux | Termux | Termux prefix | Ubuntu 24.04 |
| العزل | Android sandbox | Android sandbox | sandbox + hook | root + mounts، بلا namespaces |
| التثبيت | APK + bootstrap | APK + Termux | APK مدمج | APK + rootfs |
| VNC | لا | اختياري | لا | لا |
| PRoot | اختياري | اختياري | Debian اختياري | لا |

### مقارنة مع تقرير المرحلة 1

- Termux يستخدم `sharedUserId=com.termux` ووحدات منفصلة؛ DroidDesk لا يستخدم sharedUid.
- Termux:X11 يستخدم `app_process`/`Loader` وتوقيع APK للتحقق؛ DroidDesk يحمّل `libXlorie` مباشرة في خدمة `:x11`.
- Termux:X11 يعتمد TCP knock (7892, `0xDEADBEEF`) و`socketpair` عبر Binder؛ DroidDesk يعتمد Binder PFD ومقبس Unix مباشر.
- DroidDesk يشبه Termux:X11 في فكرة `LorieView`/`LorieBuffer`، لكنه يدمج التجربة في تطبيق واحد.
- DroidDesk يضيف chroot rootfs، وهو ما لا يقدمه Termux:X11 افتراضيًا.

### البيئة الحالية

بيئة التحليل:

```text
Linux 6.1.128-android14 aarch64
Ubuntu 22.04.5 LTS
```

هذه تبدو proot/حاوية على Android kernel، وليست chrootًا حقيقيًا. لذلك لا يمكن استخدامها كدليل على أداء DroidDesk chroot.

---

## 17. نقاط القوة

1. **فكرة متماسكة:** تطبيق واحد، X11 مضمّن، سطح مكتب Linux، وواجهة Flutter.
2. **native بدون root:** bootstrap + relocation + hook، مع دعم Adreno Turnip/Zink.
3. **chroot حقيقي:** يتيح apt/root وأدوات لا تعمل في native.
4. **X11 في عملية منفصلة:** قرار صحيح للاستقرار.
5. **LorieView:** تكامل Surface/Input جيد، وإرسال الأبعاد مبكرًا ذكي.
6. **PulseAudio/AAudio في native:** معالجة صوتية مدروسة.
7. **XFCE mobile profile:** devilspie2 maximize، DPI 140، أيقونات Papirus.
8. **تحديث APK موقّع:** تحقق شهادة قوي.
9. **واجهة حديثة:** Wizard، كتالوج، طرفية، تكامل Android apps.
10. **توثيق امتثال بدأ:** `COMPLIANCE.md` و`NOTICE` موجودان.
11. **نشاط المشروع:** إصدارات وتحديثات مستمرة.

---

## 18. نقاط الضعف والمخاطر

### 18.1 معمارية

- مساران كبيران (native/chroot) بصيانة منفصلة.
- ازدواجية إعدادات الرسوميات (`droiddesk.sh` vs `droiddesk-ha.sh`).
- كود ميت (`RootfsManager.installDesktopEnvironment`).
- فروع DE غير قابلة للوصول.
- `socket_hook` واسع وهش.
- `LorieView` singleton مع detached parent، نمط هش.
- Wayland غير منفّذ.

### 18.2 أداء

- chroot llvmpipe.
- native llvmpipe على غير Adreno.
- DPI/دقة غير قابلة للضبط.
- 45s readiness timeout محتمل.
- XFCE + X11 على هاتف ثقيل.

### 18.3 أمان

- rootfs بلا checksum.
- chroot root + `/dev` كامل.
- لا namespaces.
- متصفحات كroot.
- `targetSdk 28`.
- صلاحيات واسعة.
- `socket_hook` يغير مسارات حساسة.
- `installRepoPackages` يستبدل postinst بـno-op، ما قد يتجاوز إعدادات مستودع مستقبلية.

### 18.4 جودة

- 84 issue مفتوحة.
- أعطال شاشة سوداء/تركيب/صوت/تخزين.
- bus factor 2.
- ادعاءات README مبالغ فيها.
- لا اختبارات أداء/تكامل شاملة.
- GPL compliance ناقص.

### 18.5 توثيق

- `RootfsManager` comments قديمة.
- README يخلط بين التطبيق وسكربت host.
- «direct kernel access» و«best performance» مضللان.
- لا توثيق واضح لقيود chroot (llvmpipe/audio/storage).

---

## 19. التوصيات

### P0 — قبل أي استخدام جاد

1. **تأمين rootfs:**
   - أضف SHA-256 ثابتًا لكل إصدار.
   - تحقق قبل الاستخراج.
   - ارفض أي ملف لا يطابق hash.
   - استخدم `--no-same-owner` أو استخراج معزول ثم `chown` مضبوط.
   - افحص مسارات الأرشيف.

2. **إكمال GPL compliance:**
   - انشر exact source لـ`libXlorie.so` وكل المكتبات المسبقة.
   - وثّق commit/patches/build commands.
   - أضف corresponding source أو offer صريح.
   - أعد بناء المكتبات من المصدر في CI.

3. **تصحيح الادعاءات:**
   - غيّر «best performance» إلى وصف دقيق.
   - وضّح أن chroot = llvmpipe.
   - أزل «direct kernel access» أو اشرح حدوده.
   - وضّح أن Blender/Metasploit/Local AI ليست مدمجة.

4. **تقوية chroot:**
   - مستخدم غير root لسطح المكتب.
   - mount فقط الأجهزة اللازمة.
   - namespace للـPID/mount إن أمكن.
   - سياسة واضحة للمتصفحات.
   - تحذير أمني إلزامي قبل التفعيل.

### P1 — جودة واستقرار

5. **حل جاهزية سطح المكتب:**
   - ثبّت `procps` أو استخدم `xdpyinfo`/socket checks.
   - لا تنتظر 45s عند غياب `pgrep`.
   - أبلغ المستخدم بحالة X11/DE بدل شاشة سوداء.

6. **توحيد إعدادات الرسوميات:**
   - ملف profile واحد.
   - احذف `droiddesk.sh` المتعارض أو وحّده.
   - اجعل `getGraphicsMode()` يطابق البيئة الفعلية.

7. **الدقة/DPI:**
   - أضف إعدادات UI.
   - اختبر 1080p/1440p/DeX.
   - عالج #53.

8. **التخزين:**
   - اربط `/mnt/sdcard` بشكل مضبوط أو أزل الادعاء.
   - عالج #97.

9. **الصوت في chroot:**
   - ثبّت/ابدأ PulseAudio أو PipeWire.
   - اختبر #39.

10. **تقليل المخاطر:**
    - ضيّق `socket_hook` إلى المسارات اللازمة.
    - اجعل `connect` thread-safe.
    - اختبر static/Go/Rust binaries.
    - أضف fallback عند فشل الـhook.

11. **حذف الكود الميت:**
    - `RootfsManager.installDesktopEnvironment`.
    - فروع DE غير المستخدمة أو أضف اختيارًا حقيقيًا.
    - URLs غير المستخدمة.

### P2 — نضج المنتج

12. **targetSdk:**
    - وثّق سبب 28.
    - ابحث عن مسار للعمل مع targetSdk حديث دون كسر exec.
    - لا تحاول Play Store دون حل ذلك.

13. **الاختبارات:**
    - CI لبناء APK.
    - اختبارات smoke لـnative/chroot.
    - مصفوفة أجهزة Adreno/Mali/Samsung.
    - قياس أداء منشور.

14. **التشخيص:**
    - شاشة logs.
    - فحص GPU/OpenGL.
    - زر «copy diagnostics».

15. **تجربة المستخدم:**
    - خيار native على أجهزة root.
    - شرح الفرق بين المسارات.
    - تحذير low storage أدق.
    - معالجة DeX.

---

## 20. الخلاصة

`DroidDesk` ليس تطبيقًا بسيطًا؛ إنه محاولة جدية لبناء Linux desktop على Android بثلاث طبقات: native Termux، chroot root، وPRoot Debian. المعمارية ذكية، والواجهة ناضجة نسبيًا، وبعض الحلول التقنية (X11 في `:x11`، Binder PFD، LorieView، socket_hook، close_range shim) مبتكرة.

لكن الفجوة بين الادعاء والواقع كبيرة:

- chroot ليس GPU-accelerated؛ بل llvmpipe.
- rootfs غير موثوق.
- chroot ليس عزلًا أمنيًا.
- GPL compliance غير مكتمل.
- الأداء والاستقرار يعتمدان على الجهاز والحظ.
- المشروع صغير الفريق وكثير المشكلات.

**التوصية النهائية:**

- للاستخدام الشخصي/التجريبي على جهاز مخصص: ممكن مع أخذ الحيطة.
- كبيئة عمل يومية: غير مستقر بما يكفي.
- كبيئة أمنية أو إنتاجية: غير مناسب.
- كموضوع بحث/تطوير: ممتاز، ويستحق دراسة معمارية X11/relocation/chroot.

---

## Appendix A — خريطة الملفات

| المسار | الدور |
|---|---|
| `app/lib/` | Flutter UI: setup, home, app catalog, terminal, settings |
| `app/lib/state/app_state.dart` | حالة التطبيق، اختيار distro/DE، setup |
| `app/lib/screens/setup/setup_progress.dart` | wizard، حوار root، تحذير التخزين |
| `app/lib/screens/setup/de_install_screen.dart` | تثبيت DE تلقائي |
| `app/lib/screens/apps/app_catalog_screen.dart` | كتالوج التطبيقات و`proot_debian` |
| `app/android/.../MainActivity.kt` | MethodChannel، setup، status، OTA |
| `app/android/.../view/DesktopActivity.kt` | `LorieView`، بدء X11 والجلسة |
| `app/android/.../x11/X11ServerService.kt` | خدمة X11 في `:x11` |
| `app/android/.../x11/X11ServiceClient.kt` | Binder/PFD إلى `LorieView` |
| `app/android/.../x11/X11InputController.kt` | مقياس/حقن الإدخال |
| `app/android/.../runtime/LinuxRuntime.kt` | native bootstrap، relocation، GPU، session |
| `app/android/.../runtime/ChrootRuntime.kt` | chroot، mounts، X11 bind، llvmpipe |
| `app/android/.../runtime/RootfsManager.kt` | تنزيل/استخراج rootfs |
| `app/android/.../runtime/AppUpdater.kt` | تنزيل APK والتحقق من التوقيع |
| `app/android/.../runtime/AndroidAppBridge.kt` | تكامل تطبيقات Android |
| `app/android/.../runtime/XfceMobileProfile.kt` | ملف XFCE المحمول |
| `app/android/.../java/com/termux/x11/CmdEntryPoint.java` | native X server entry |
| `app/android/.../java/com/termux/x11/LorieView.java` | surface/input/native connect |
| `app/android/.../assets/socket_hook.c` | LD_PRELOAD path/socket hook |
| `app/android/.../assets/droiddesk/close_range_compat.c` | close_range shim |
| `app/android/.../cpp/compositor_jni.cpp` | Wayland placeholder |
| `app/android/.../jniLibs/arm64-v8a/` | `libXlorie`, `libproot`, `libsocket_hook`, ... |
| `app/assets/socket_hook.c` | مصدر hook |
| `app/android/.../assets/.../bootstrap-aarch64.zip` | bootstrap native |
| `README.md` | ادعاءات وطريقة عمل |
| `COMPLIANCE.md` | الترخيص وSHA-256 ونواقص المصدر |
| `THIRD_PARTY_NOTICES.md` | إشعارات الطرف الثالث |
| `NOTICE.md` | عدم الانتماء |
| `termux-linux-setup.sh` | سكربت host منفصل، ليس داخل التطبيق |

---

## Appendix B — أوامر التحقق

```bash
# releases
gh api repos/orailnoor/DroidDesk/releases

# open issues
gh api 'repos/orailnoor/DroidDesk/issues?state=open&per_page=100'

# latest commits
gh api 'repos/orailnoor/DroidDesk/commits?per_page=30'

# key code locations
grep -RIn "LIBGL_ALWAYS_SOFTWARE\|GALLIUM_DRIVER\|MESA_LOADER_DRIVER_OVERRIDE" \
  app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime

grep -RIn "close_range\|LD_PRELOAD" \
  app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime \
  app/android/app/src/main/assets

grep -RIn "signature_mismatch\|fingerprints\|downloadUpdate" \
  app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/AppUpdater.kt

# rootfs
grep -n "DISTRO_URLS\|SHA\|MessageDigest\|tar" \
  app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/RootfsManager.kt
```

---

## Appendix C — أسئلة مفتوحة

1. هل `procps`/`pgrep` مثبت ضمن rootfs بعد تثبيت XFCE؟
2. هل `tar` في toybox يدعم `xz` على أجهزة Android المدعومة؟
3. ما exact upstream commit/patches لـ`libXlorie.so`؟
4. هل `installRepoPackages` يتجاوز إعدادات مستقبلية لـx11-repo/tur-repo؟
5. هل `socket_hook` يعمل مع تطبيقات Go/Rust/static؟
6. هل chroot ينتج صوتًا على أجهزة مختلفة؟
7. هل يمكن ربط `/mnt/sdcard` بأمان دون `MANAGE_EXTERNAL_STORAGE`؟
8. هل يمكن تفعيل GPU في chroot عبر vendor libs، أم أن llvmpipe قرار دائم؟
9. هل `DroidDeskService` يبدأ عند boot، وما تأثيره على البطارية؟
10. هل توجد خطة للانتقال إلى `targetSdk` حديث؟

---

## Appendix D — ملحق التدقيق الإضافي (2026-10-21)

> هذا الملحق يوثّق نتائج التدقيق الساكن الإضافية التي لم تُدرج في الأقسام السابقة. لا يحتوي على أي تعديل لكود DroidDesk؛ هو توثيق للسياق والأدلة فقط. المصدر: `/tmp/opencode/research/DroidDesk-main`.

### D.1 سلامة rootfs والتنزيل

**الأدلة:**

- `RootfsManager.kt:35-36` يعرّف روابط Ubuntu 24.04.4 base arm64 وAlpine 3.20 aarch64 وKali NetHunter minimal arm64.
- `RootfsManager.kt:119-167` مسار التنزيل/الاستئناف.
- `RootfsManager.kt:132-137` عند HTTP `416` يفترض أن الملف مكتمل ويكتب `configFile` دون تحقق.
- `RootfsManager.kt:182-185` قد يعتبر `Content-Length=0` نجاحًا.
- `RootfsManager.kt:242-269` استخراج `tar`.
- لا يوجد `MessageDigest`/SHA-256 متوقع، ولا توقيع GPG، ولا مقارنة hash من GitHub release.
- لا يوجد تحقق تطبيقي صريح من مسارات الأرشيف (path traversal)؛ يعتمد على `tar` النظامي/toybox.
- فشل `tar` لا يوقف المسار إذا وُجد `bin` في rootfs.
- لا `--no-same-owner`/`--numeric-owner` صريح، ودعم `xz` في toybox غير مضمون لـKali.

**الأثر:** أي تلاعب في `cdimage.ubuntu.com` أو DNS/proxy أو ملف rootfs قد يصل إلى بيئة تعمل لاحقًا بصلاحيات root. هذه أخطر نقطة supply chain في المشروع.

**ملاحظة إيجابية:** `AppUpdater` ليس بنفس الضعف؛ يتحقق من اسم الحزمة وتوقيع APK. انظر D.5.

### D.2 عزل chroot وآثاره الجانبية

**ما يفعله `ChrootRuntime`:**

- يربط `/dev` و`/proc` و`/sys` كاملة.
- ينشئ `devpts` و`tmpfs` لـ`/dev/shm` و`/run` و`/tmp`.
- لا يستخدم mount/PID/network/UTS namespaces.
- لا يطبق capability drop أو seccomp خاصًا بالجلسة.
- يعمل كـroot، لذا أي تطبيق داخل الجلسة يملك root.

**مشكلة ترتيب فك التركيب:**

`unmountAll()` يعكس قائمة targets التي تبدأ بـ`tmp/.X11-unix` و`dev/pts` و`dev/shm` و`dev`. النتيجة الفعلية:

```text
/tmp
/run
/sys
/proc
/dev          ← قبل /dev/shm و/dev/pts
/dev/shm
/dev/pts
/tmp/.X11-unix
```

هذا ترتيب خاطئ: `/tmp` يُفك قبل `/tmp/.X11-unix`، و`/dev` يُفك قبل mount points الأبناء. قد تبقى mounts معلقة أو تفشل عمليات لاحقة.

**خطر جانبي على `/dev` المضيف:**

بعد `mount --bind /dev <rootfs>/dev`، ينفّذ `ensureMounts()` ما يشبه:

```bash
ln -snf /proc/self/fd "$devPath/fd"
ln -snf /proc/self/fd/0 "$devPath/stdin"
ln -snf /proc/self/fd/1 "$devPath/stdout"
ln -snf /proc/self/fd/2 "$devPath/stderr"
```

بما أن `<rootfs>/dev` هو bind لـ`/dev`، فإن `ln -snf` قد يعدّل symlinks في `/dev` المضيف. يحتاج هذا إلى تأكيد ديناميكي، لكنه خطر واضح من الكود.

**التخزين:**

- `ChrootRuntime.kt:111` ينشئ `mnt/android` و`mnt/sdcard` كمجرد directories.
- لا يوجد bind لهما إلى التخزين الفعلي.
- Issue #97 يتسق مع هذا: سطح المكتب لا يرى التخزين الداخلي كما يتوقع المستخدم.

**الصوت:**

- `startSession()` في chroot لا يبدأ PulseAudio ولا يثبّت `pulseaudio` صراحة.
- لا تهيئة لـ`/dev/snd` أو ALSA.
- `RootfsManager` يكتب `PULSE_SERVER=127.0.0.1` في `droiddesk.sh`، لكن لا خادم يستمع.
- النتيجة: صوت chroot غائب أو غير موثوق.

**خلاصة:** chroot هنا ليس حاوية أمنية، بل تغيير root directory مع root ومounts واسعة. يجب توثيقه بهذا الوصف، لا كـ"عزل".

### D.3 تعارض الرسوميات وprofile

**المصدران المتعارضان:**

- `RootfsManager.kt:340-341` يكتب `/etc/profile.d/droiddesk.sh` بقيم zink:
  - `GALLIUM_DRIVER=zink`
  - `MESA_LOADER_DRIVER_OVERRIDE=zink`
  - `MESA_GL_VERSION_OVERRIDE=4.6`
  - `MESA_GLES_VERSION_OVERRIDE=3.2`
  - `PULSE_SERVER=127.0.0.1`
- `ChrootRuntime.kt:131-133` يكتب `/etc/profile.d/droiddesk-ha.sh` بقيم llvmpipe:
  - `LIBGL_ALWAYS_SOFTWARE=true`
  - `GALLIUM_DRIVER=llvmpipe`
  - `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe`

`ChrootRuntime.kt:430` يستدعي `droiddesk-ha.sh` فقط. لكن أي login shell أو profile.d آخر قد يقرأ `droiddesk.sh` ويعيد zink بلا Vulkan فعلي. هذا يفسر أخطاء OpenGL متفرقة وعدم وجود "مصدر حقيقة واحد".

**تعارض إضافي:** مسار native يذكر/يستخدم `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe` بينما التعليق/السلوك المتوقع قد يشير إلى `swrast`. لم نتحقق ديناميكيًا من أي driver يُحمّل فعليًا. لا تعتمد على قيمة واحدة دون اختبار `glxinfo`/`vulkaninfo`.

**ادعاء README:** "both modes use Adreno Turnip/Zink" غير صحيح لـchroot. chroot يفرض llvmpipe حتى على Adreno. حوار root الذي يقول "best performance" مضلل للرسوميات.

### D.4 AndroidManifest وtargetSdk

**targetSdk 28:**

`build.gradle.kts:42-43` يحتوي تعليقات صريحة بأن الإعداد مقصود لتفادي W^X و"completely disables Android 10+ execve block". هذا قرار وظيفي مفهوم لتشغيل binaries، لكنه:

- يمنع النشر عبر Play.
- يعطل طبقات أمان Android الحديثة.
- يجعل التحديث sideload معتمدًا على `REQUEST_INSTALL_PACKAGES`.

**الصلاحيات:**

- `QUERY_ALL_PACKAGES` — يستخدمه `AndroidAppBridge`/`AndroidAppSync` لعرض تطبيقات Android.
- `MANAGE_EXTERNAL_STORAGE` — معلن، ولم نجد استخدامًا مباشرًا في Kotlin/Java ضمن البحث الحالي.
- `REQUEST_INSTALL_PACKAGES` — لتحديث APK.
- `usesCleartextTraffic=true` — لا وجدنا endpoint HTTP فعليًا في الكود غير DTD، لكنه يوسّع السطح إن أُضيف لاحقًا.
- `requestLegacyExternalStorage=true`.
- `READ/WRITE_EXTERNAL_STORAGE`.

**عناصر manifest أخرى:**

- تصنيف `android.intent.category.HOME` مكرر في السطور 54 و59.
- `FileProvider` غير مصدّر (`exported=false`) — جيد.
- `X11ServerService` غير مصدّر — جيد.
- `MainActivity`/launcher مصدّر كما هو متوقع.

### D.5 تحديث APK مقابل rootfs

`AppUpdater.kt:147-166` يتحقق من:

- `packageName` المتوقع.
- تقاطع signing certificate مع التطبيق المثبت.
- حجم الملف المتوقع (قد يكون شرطًا اختياريًا/مشروطًا، وليس الضمان الأساسي).

هذا يمنع تثبيت APK موقّع بمفتاح مختلف. لكنه لا يحمي rootfs، الذي يُنزّل من URL بلا hash. التباين مهم: APK محمي بالتوقيع، rootfs غير محمي.

### D.6 X11: access control وTCP

`X11ServerService.kt:89` يشغّل:

```text
:0 -nolock
```

- `-nolock` يعطل lock file فقط، ولا يعطل access control.
- لا يوجد `-nolisten tcp`.
- `libXlorie.so` يحتوي رموزًا مثل `tcp6` و`NoListenAll` و`SocketINETCreateListener`، ما يشير إلى أن TCP موجود في المكتبة، لكنه لا يثبت أنه يستمع افتراضيًا.
- لم يتم التحقق من `ss`/`netstat` على جهاز فعلي؛ البيئة المحلية لا تدعم AF INET عبر netstat.
- الخدمة غير مصدّرة، والاتصال الأساسي عبر Binder وParcelFileDescriptor، وليس عبر منفذ TCP في كود التطبيق.

**الخلاصة:** لا يمكن الجزم بأن X11 مفتوح على الشبكة. يلزم اختبار على Android: `ss -ltnp` أو `netstat` داخل الجهاز، مع فحص `libXlorie` args.

### D.7 الخصوصية وتسجيل IME/clipboard

**`LorieView.java`:**

- `LorieView.java:129,163,327` تسجل نصوص IME/المدخلات عبر `Log.d`.
- `LorieView.java:908` تسجل محتوى الحافظة كاملًا.
- هذه سلوكيات موروثة من مكوّن Termux:X11، لكنها خطيرة في تطبيق desktop.

**الحافظة:**

- `Prefs.java:38` يجعل `clipboardEnable` مفعّلًا افتراضيًا.
- `ClipboardSync` يزامن الحافظة كل 800ms تقريبًا، بلا حد واضح للحجم.
- `ChrootRuntime.readLinuxClipboard()`/`writeLinuxClipboard()` تستخدمان `xclip` وbase64.

**تسجيل الأوامر:**

- `RootShell.kt:114` يسجل `su exec: $command` لكل أمر.
- `RootShell.kt:125` يسجل `su stream: $command`.
- أوامر الحافظة في chroot تحتوي base64 لمحتوى الحافظة، لذا تُسجل الحافظة كاملة في logcat.
- `LinuxRuntime.kt:2228` يسجل الأمر، و`:2247` يسجل كل `CHUNK` من المخرجات.
- `ChrootRuntime` يسجل مخرجات `execChroot` عبر `Log.d`.
- النتيجة: على جهاز root أو عبر ADB/بناء debug، يمكن تسريب أوامر ومخرجات وبيانات مستخدم.

**الإدخال:**

- `X11InputController` يمرر الأحداث إلى `TouchInputHandler` ثم `InputEventSender`/`LorieView`.
- لا توجد طبقة libinput منفصلة ولا مستمع شبكي جديد في هذا المسار.
- الخطر هنا تسجيل النصوص، وليس حقنًا شبكيًا.

### D.8 shell quoting وحقن الأوامر

**`RootShell.execInChroot`:**

```kotlin
fun execInChroot(rootfs: File, command: String): String {
    return exec("chroot ${rootfs.absolutePath} /bin/bash -c '$command'")
}
```

- لا يهرّب `'` داخل `command`.
- البحث الحالي يجد تعريفه فقط، ولا يوجد caller في كود DroidDesk.
- لذلك هو API كامن غير محصّن: إذا استُخدم لاحقًا بأمر غير موثوق، فهو command injection.
- التوصية: حذفه أو استخدام `shellQuote` نفسها.

**`ChrootRuntime.shellQuote`:**

```kotlin
return "'" + input.replace("'", "'\"'\"'") + "'"
```

هذا التهرّب صحيح. `executeCommand()` و`execChroot()` ومسارات الحافظة تستخدمه، ولا حقن مباشر في هذه المسارات.

**أسماء الحزم:**

- `LinuxRuntime.isSafePackageName()` يستخدم `Regex("[a-z0-9][a-z0-9+.-]{0,127}")`.
- `installStorePackage`/`removeStorePackage` يتحققان منها قبل تمريرها إلى `apt`.
- أوامر chroot للتطبيقات الاختيارية ثابتة (`firefox`, `code_oss`, `nodejs`, `imagemagick`) وليست من إدخال حر.

**الخلاصة:** لا حقن مؤكد في المسارات المستخدمة حاليًا؛ الخطر المحتمل هو `execInChroot` غير المستخدم.

### D.9 `termux-linux-setup.sh` وREADME

`termux-linux-setup.sh` مسار يدوي منفصل، وليس كود التطبيق. README فقط هو من يذكره؛ التطبيق لا يشغّله.

**مخاطر السكربت:**

- يستخدم `termux-x11 :0 -ac`، أي تعطيل X access control.
- يشغّل PulseAudio مع `module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1`، ما يسمح لأي تطبيق محلي بالاتصال بالصوت.
- يستخدم `pkill -9 -f` واسعًا قد يقتل عمليات غير مستهدفة.
- يمنح passwordless sudo للمستخدم.
- لا تحقق كامل من سلامة الحزم/المصادر.

**تعارض README:**

- يقول إن التطبيق المستقل لا يحتاج Termux منفصلًا.
- لكن قسم التثبيت يطلب Termux/Termux-X11 وتشغيل `termux-linux-setup.sh`.
- هذا يخلق مسارين مختلفين للمستخدم: تطبيق مضمّن vs إعداد Termux يدوي.

### D.10 ادعاءات تحتاج تصحيحًا

| الادعاء | الواقع |
|---|---|
| "both modes use Adreno Turnip/Zink" | native فقط، وفقط على Adreno؛ chroot يفرض llvmpipe. |
| chroot "best performance" | غير صحيح للرسوميات؛ native أفضل على Adreno. |
| "standalone app needs no Termux" | يتعارض مع تعليمات التثبيت اليدوية. |
| rootfs "~300–500 MB" | Ubuntu base الفعلي ~30MB مضغوط. |
| "secure/isolated chroot" | لا namespaces، root، وmounts واسعة؛ ليس عزلًا أمنيًا. |

### D.11 ما لم يتم التحقق منه

- لم يُختبر التطبيق على جهاز Android فعلي.
- لم يُثبت أن `libXlorie` يستمع على TCP أو لا.
- لم يُحدد driver Mesa الفعلي في zink/llvmpipe على أجهزة مختلفة.
- `termux-linux-setup.sh` لم يُنفّذ؛ التحليل ساكن.
- لم تُختبر `tar`/toybox مع Kali `.tar.xz`.
- لم تُختبر `socket_hook` مع تطبيقات Go/Rust/static.
- لم يُختبر الصوت في chroot على أجهزة متعددة.
- بيئة التحليل المحلية proot لا تدعم `/proc` و`ps`/`pgrep` و`ss`/AF INET، لذا بعض الاختبارات غير ممكنة محليًا.

### D.12 توصيات هذا الملحق

**P0 — قبل استخدام جاد:**

1. أضف SHA-256 ثابتًا لكل rootfs، وارفض `416`/`Content-Length=0` إذا لم يطابق hash.
2. افحص مسارات tar أو استخدم extractor يرفض `..`/absolute paths.
3. أصلح ترتيب `unmountAll()`: `tmp/.X11-unix` قبل `/tmp`، و`dev/pts`/`dev/shm` قبل `/dev`.
4. لا تنفّذ `ln -snf` على `/dev` بعد bind، أو استخدم mount خاصًا/Namespaced dev.
5. أضف namespaces أو وثّق بوضوح أن chroot ليس حدًا أمنيًا.
6. عطّل تسجيل IME/clipboard افتراضيًا، أو اربطه بـdebug build فقط.
7. أزل `execInChroot` غير المحصّن أو استخدم `shellQuote`.

**P1 — جودة واستقرار:**

8. وحّد profile الرسوميات: zink أو llvmpipe، لا ملفان متعارضان.
9. اختبر `MESA_LOADER_DRIVER_OVERRIDE` و`GALLIUM_DRIVER` فعليًا على Adreno/Mali.
10. أصلح الصوت أو وثّق غيابه؛ اربط `/dev/snd` أو شغّل PulseAudio.
11. اربط `mnt/android`/`mnt/sdcard` بشكل مضبوط أو أزل الادعاء.
12. حدّث README وأزل ادعاءات "Turnip/Zink" و"best performance" و"no Termux".

**P2 — نضج:**

13. راجع `targetSdk 28` وابحث عن مسار حديث لا يكسر exec.
14. أضف اختبارات ثابتة لـshellQuote/package validation/unmount order.
15. راجع `termux-linux-setup.sh`: أزل `-ac`، لا تستخدم anonymous PulseAudio، وضيّق `pkill`.
16. أضف logging redaction للمسارات الحساسة (clipboard/IME/commands).

### D.13 خريطة أدلة سريعة

| الملف/السطر | الدليل |
|---|---|
| `RootfsManager.kt:35-36` | روابط rootfs الرسمية. |
| `RootfsManager.kt:132-137` | 416 يُعامل كاكتمال. |
| `RootfsManager.kt:182-185` | Content-Length=0 قد يُعامل كنجاح. |
| `RootfsManager.kt:242-269` | tar بلا فحص مسارات صريح. |
| `RootfsManager.kt:340-341` | zink profile. |
| `ChrootRuntime.kt:111` | mnt/android وmnt/sdcard directories فقط. |
| `ChrootRuntime.kt:131-133` | llvmpipe profile. |
| `ChrootRuntime.kt:430` | يستدعي droiddesk-ha.sh فقط. |
| `ChrootRuntime.kt:679-691` | mounts وln -snf على /dev. |
| `ChrootRuntime.kt:799` | shellQuote صحيح. |
| `RootShell.kt:101-103` | execInChroot بلا escaping. |
| `RootShell.kt:114,125` | تسجيل أوامر su. |
| `LinuxRuntime.kt:1855-1856` | isSafePackageName. |
| `LinuxRuntime.kt:2228,2247` | تسجيل الأوامر والمخرجات. |
| `LorieView.java:129,163,327,908` | تسجيل IME/clipboard. |
| `Prefs.java:38` | clipboardEnable=true. |
| `X11ServerService.kt:89` | :0 -nolock. |
| `AppUpdater.kt:147-166` | التحقق من package/signature. |
| `build.gradle.kts:42-43` | targetSdk 28 وW^X/execve. |
| `AndroidManifest.xml:54,59` | تكرار HOME. |
| `README.md:46,98-99` | تعارض standalone/Termux. |

---

**نهاية التقرير.**
