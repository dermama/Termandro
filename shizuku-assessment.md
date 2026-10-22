# تقييم Shizuku: طبقة تحكم متقدمة ثانوية

**التاريخ:** 2026-10-21  
**النطاق:** فحص مشروع Shizuku، فهم دوره في بيئة Termux + Ubuntu الحالية، وتحديد هل يصلح كميزة متقدمة ثانوية دون أن يصبح "تفعيلًا أصيلًا" أو اعتمادًا أساسيًا.  
**الحالة:** تقرير فحص فقط. **لم يتم تثبيت أو تفعيل أو تعديل Shizuku**، ولم يتم تغيير أي إعداد على الهاتف أو Termux.

---

## 0. الحكم التنفيذي

**Shizuku ليس root، وليس بديلًا عن root، وليس تفعيلًا أصيلًا للنظام.** هو **جسر Binder** يمنح تطبيقات عادية وصولًا إلى واجهات Android بصلاحية **ADB shell (UID 2000)** أو root إن كان الجهاز مروّتًا. في حالتنا، الأقرب هو:

> **ADB-shell دائم نسبيًا، يُشغّل يدويًا بعد كل إعادة تشغيل، ويُستخدم لأوامر Android محدودة.**

لذلك مكانه الصحيح:

- ✅ **ميزة متقدمة ثانوية** لبعض عمليات Android التي لا توفرها Termux:API.
- ✅ **طبقة اختيارية** تعمل فوق النظام الحالي، ويتدهور النظام بأمان عند غيابها.
- ✅ **أداة تدقيق/تحكم** محدودة، وليست أساس تشغيل Ubuntu أو XFCE أو Chromium.
- ❌ ليس بديلًا عن root.
- ❌ ليس تفعيلًا دائمًا بعد reboot.
- ❌ ليس طريقة للوصول إلى `/data/data` لتطبيقات أخرى أو تركيب أنظمة ملفات.
- ❌ ليس شيئًا يعمل "داخل Ubuntu/proot" بشكل أصلي؛ يعمل على جانب Android/Termux.

**التوصية:** استخدامه كـ **طبقة ثانوية اختيارية**، مع wrapper محدود الأوامر، وبدون جعله شرطًا لأي وظيفة أساسية.

---

## 1. الحالة الفعلية للبيئة (ما هو مؤكد وما هو غير مؤكد)

| العنصر | الحالة | الدليل |
|---|---|---|
| مشروع Shizuku | موجود ونشط تاريخيًا | GitHub: `RikkaApps/Shizuku`، آخر commit في يونيو 2025 |
| أحدث إصدار | `v13.6.0` | GitHub Release، نُشر 2025-05-25 |
| حجم APK | 2,571,773 بايت | `shizuku-v13.6.0.r1086.2650830c-release.apk` |
| متطلبات Android | **Android 7.0+** (`minSdk 24`) | `build.gradle`؛ README الخاص بـShizuku-API يذكر 6.0+، لكن APK الرسمي `v13.6.0` لا يُثبّت على Android 6 |
| Wireless debugging | Android 11+ فقط | دليل Shizuku الرسمي |
| ADB عبر كمبيوتر | Android 10 وأقدم | دليل Shizuku الرسمي |
| الحاجة لإعادة التفعيل | نعم بعد كل reboot على non-root | دليل Shizuku الرسمي |
| تطبيق Shizuku على الهاتف | **غير متحقق** | `mesh_phone_exec` و`mesh_status` انتهيا بـ timeout |
| اتصال ADB الحالي | **لا يوجد جهاز** | `adb devices -l` أعاد قائمة فارغة |
| `rish` في Termux | **غير موجود** | لا يوجد في `$HOME` ولا `.local/bin` ولا `usr/bin` |
| إشارات Shizuku في مساحة العمل | **غير مكتمل** | `rg` غير مثبت (`rg: command not found`)؛ الفحص المباشر وجد `rish` غائبًا فقط |
| Phone bridge | **غير متاح** | `phone_bridge.py status` تجاوز 35 ثانية؛ `adb devices` فارغ؛ ping للعنوان المحفوظ فشل 100% |
| ADB في Termux | موجود | `/data/data/com.termux/files/usr/bin/adb` |
| Ubuntu/rootfs | 22.04.5، منفصل عن Android | تقرير البنية السابق |

**الخلاصة:** لا يمكن الجزم بأن Shizuku مثبت أو يعمل على الهاتف الآن. الفحص الحالي هو **فحص مشروع + تصميم تكامل**، وليس تأكيدًا لحالة التطبيق الحي. يلزم إعادة توصيل الهاتف لإكمال التحقق الميداني.

---

## 2. كيف يعمل Shizuku فعلًا

### 2.1 المشكلة التي يحلها

تطبيق عادي يريد `pm disable` أو `appops set` يحتاج صلاحية أعلى. الحلول التقليدية:

- `su -c` → يتطلب root، بطيء، يعتمد على تحليل نص الأوامر.
- ADB من كمبيوتر → يتطلب كمبيوتر، وغير عملي للاستخدام اليومي.
- Shizuku → يبدأ عملية Java (`app_process`) بصلاحية shell/root، ويجعل التطبيقات تتحدث معها عبر Binder.

### 2.2 المعمارية

```text
تطبيق عادي
   │  Binder / Shizuku API
   ▼
Shizuku server (app_process)
   │  UID 2000 (ADB) أو UID 0 (root)
   ▼
System Server (PackageManager, ActivityManager, AppOps...)
```

المكوّنات المهمة في المستودع:

- `manager/` — تطبيق الإدارة والواجهة والصلاحيات.
- `server/` — خدمة Shizuku التي تعمل بصلاحية ADB/root.
- `starter/` — تشغيل الخادم.
- `shell/` — `ShizukuShellLoader` ومسار تحميل dex؛ **ليس** سكربت `rish` نفسه.
- `manager/src/main/assets/rish` — سكربت `rish` الرسمي، ومعه `rish_shizuku.dex`.
- `api/` — Git submodule لمكتبة `Shizuku-API`.
- `common/` — كود مشترك.

### 2.3 الصلاحية ليست root

Shizuku يبدأ بواحدة من طريقتين:

| طريقة البدء | UID | ماذا تعني |
|---|---:|---|
| Root | 0 | صلاحيات root كاملة تقريبًا |
| ADB / Wireless debugging | 2000 (shell) | صلاحيات ADB shell فقط |

`Shizuku#getUid()` يعيد `0` أو `2000`. هذا فرق جوهري:

- ADB shell يستطيع `pm grant` و`appops` و`settings` و`am` و`cmd`.
- ADB shell **لا يستطيع** قراءة `/data/user/0/<package>` لتطبيق آخر.
- ADB shell **لا يستطيع** تركيب `/system` أو تعديل kernel أو تجاوز SELinux.
- ADB shell **لا يستطيع** منح صلاحيات signature/privileged.
- ADB shell **لا يستطيع** تجاوز قفل الشاشة أو FRP.
- ADB shell permissions تختلف بين إصدارات Android.

هذا هو سبب وصف Shizuku بأنه "تفعيل غير أصيل" في سياقنا: **هو امتداد لـ ADB، وليس تفعيلًا أصليًا للنظام**.

### 2.4 لماذا هو أسرع من `su` التقليدي

بدل إنشاء عملية لكل أمر وتحليل نصها، التطبيق يحصل على Binder إلى خدمة موجودة، ثم ينفذ استدعاءات مباشرة. هذا:

- يقلل إنشاء العمليات.
- يقلل تحليل النصوص.
- يسمح باستدعاء واجهات لا تغطيها أوامر shell.
- يظل محدودًا بالصلاحيات المتاحة.

### 2.5 `rish`

`rish` هو shell تفاعلي/غير تفاعلي يعمل بصلاحية Shizuku (عادة UID 2000). فائدته:

- تشغيل أوامر Android من Termux دون كمبيوتر.
- اختبار سريع قبل بناء تكامل.
- تنفيذ أوامر مثل `pm`, `settings`, `appops`, `cmd`.

قيوده:

- يعمل على **جانب Android/Termux**، وليس داخل Ubuntu/proot.
- يحتاج Shizuku server يعمل.
- ليس root.
- لا يصلح لاستدعاءات متكررة عالية التردد؛ الأفضل Binder API.
- أي عملية في Termux تستطيع استخدامه إذا كان في `PATH`؛ هذا مخاطرة أمنية.

**لا تعتمد على تنزيل `rish` من مواقع عشوائية.** استخدم النسخة الرسمية من مشروع Shizuku أو من تطبيق Shizuku نفسه، وتأكد من المصدر.

### 2.6 فحص APK الرسمي v13.6.0

تم تنزيل APK الرسمي من GitHub Releases وفحصه دون تثبيته:

| البند | القيمة |
|---|---|
| الملف | `shizuku-v13.6.0.r1086.2650830c-release.apk` |
| الحجم | 2,571,773 بايت (2.5MB) |
| SHA-256 للـAPK | `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f` |
| `assets/rish` | 882 بايت، SHA-256: `3b01b0cc72cfad779bff56efc29e6ad30aaa9b3969aa349cdf2aa5f876bf2066` |
| `assets/rish_shizuku.dex` | 59,672 بايت، SHA-256: `1953c1fd9708904f8fc1f67774843b4cc3d03e5f2a578ff4d654d0625456bc28` |
| المكتبات الأصلية | `libadb.so`, `librish.so`, `libshizuku.so` لمعماريات arm64-v8a وarmeabi-v7a وx86 وx86_64 |

ملاحظات من الفحص:

- التطبيق صغير جدًا مقارنة بـElectron/Chromium؛ لا يضيف عبئًا كبيرًا على التخزين.
- وجود `libadb.so` يؤكد أن ADB جزء من وظيفته، وليس مجرد واجهة.
- `assets/rish` يؤكد حرفيًا فحص Android 14+ للـdex القابل للكتابة.
- DEX يحتوي على `android.permission.WRITE_SECURE_SETTINGS` وصلاحيات مدير Shizuku (`API_V23`, `MANAGER`)، ما يوضح أن التطبيق نفسه يطلب صلاحيات إدارة، بينما التطبيقات الأخرى تحصل على صلاحية عبر Binder.
- هذا الفحص لا يثبت أن APK الموجود على الهاتف هو نفسه؛ الهاتف غير متصل حاليًا.

---

## 3. طرق التفعيل والقيود

### 3.1 Root

- الأنسب إذا كان الجهاز مروّتًا.
- يمنح UID 0.
- يظل عرضة لقيود الشركة المصنعة وإدارة الخلفية.
- **غير متاح في بيئتنا الحالية، وغير مطلوب.**

### 3.2 Wireless debugging (Android 11+)

هذه الطريقة الوحيدة العملية على جهاز غير مروّت بدون كمبيوتر:

1. تفعيل Developer options.
2. تفعيل USB debugging.
3. تفعيل Wireless debugging.
4. Pairing مرة واحدة عبر رمز الاقتران.
5. بدء Shizuku.
6. **بعد كل reboot: أعد الخطوات** (نظام Android يمنع بدء الخادم تلقائيًا بدون root).

قيود مهمة:

- يجب إبقاء Developer options وUSB debugging مفعّلين.
- بعض الشركات تقتل التطبيق في الخلفية.
- يجب استثناء Shizuku من تحسين البطارية.
- على Samsung/One UI، قد تحتاج ضبط "Auto Blocker" والسماح بالتثبيت من المصادر الموثوقة.
- خيار "Disable adb authorization timeout" مفيد على Android 11+.
- تغيير وضع USB إلى "Charge only" قد يقلل انقطاع الخدمة.
- إذا تغيّر منفذ Wireless debugging بعد reboot، تحتاج إعادة الاقتران/البدء.

### 3.3 ADB عبر كمبيوتر (Android 10 وأقدم)

- يتطلب كمبيوتر في كل مرة بعد reboot.
- غير مناسب للاستخدام اليومي على جهاز حديث.
- لا نوصي به كمسار أساسي.

### 3.4 Sui / Magisk

- يتطلب bootloader مفتوحًا وMagisk.
- ليس ضمن نطاقنا.
- لا تعتبره بديلًا سهلًا.

---

## 4. ما يمكن وما لا يمكن فعله في بيئتنا

### 4.1 يفيد كطبقة ثانوية

| المجال | أمثلة | قيمة |
|---|---|---|
| إدارة الحزم | `pm list packages`, `pm disable-user`, `pm enable`, `pm uninstall --user 0` | تنظيف تطبيقات النظام غير المرغوبة |
| الصلاحيات | `pm grant`, `pm revoke`, `appops set` | منح/سحب صلاحيات بدقة |
| الإعدادات | `settings get/put` | ضبط إعدادات Android وبعض إعدادات secure |
| التطبيقات | `am start`, `am force-stop`, `am broadcast` | تشغيل/إيقاف تطبيقات |
| الشبكة | `cmd wifi`, `svc data`, `cmd connectivity` | فحص/تحكم محدود |
| الإدخال | `input tap/text/keyevent` | أتمتة بسيطة |
| الشاشة | `screencap`, `screenrecord` | التقاط/تسجيل بصلاحية shell |
| التشخيص | `dumpsys`, `logcat`, `top` | فحص أعمق من Termux:API |

### 4.2 لا يعطيه

- قراءة بيانات تطبيقات أخرى (`/data/data/*`).
- تعديل `/system` أو `/vendor`.
- تثبيت Magisk modules.
- تجاوز SELinux أو kernel.
- منح صلاحيات signature/privileged.
- تجاوز قفل الشاشة أو FRP أو Knox.
- الوصول إلى أجهزة block أو mount.
- تشغيل خدمات system-level أصلية.
- البقاء بعد reboot بدون إعادة تفعيل.
- العمل "أصليًا" داخل Ubuntu/proot.

### 4.3 الفرق عن Termux:API

| الحاجة | Termux:API | Shizuku |
|---|---|---|
| اهتزاز، إشعارات، حافظة، بطارية | ✅ | غير ضروري |
| قراءة/كتابة `/sdcard` | ✅ | ✅ |
| `pm grant` / `appops` | ❌ غالبًا | ✅ |
| `settings put secure` | ❌ | ✅ |
| `dumpsys` كامل | ❌ | ✅ |
| `input` | محدود | ✅ |
| بيانات تطبيقات أخرى | ❌ | ❌ |
| استمرار بعد reboot | لا ينطبق | ❌ (non-root) |

---

## 5. التكامل مع Termux + Ubuntu

### 5.1 الحدود المعمارية

```text
Android
├── Shizuku app + server (UID 2000)
├── Termux (Android app)
│   └── rish → Shizuku server
└── Termux → proot → Ubuntu 22.04
    └── لا يوجد Binder مباشر إلى Shizuku
```

**القاعدة:** Shizuku يعمل في طبقة Android. Ubuntu داخل proot لا ترى Binder الخاص بـ Android ولا تستطيع استدعاء Shizuku مباشرة. أي تكامل يجب أن يمر عبر:

- **Termux host** → `rish` → Shizuku، أو
- تطبيق Android واعٍ بـShizuku، أو
- ADB من Termux إلى localhost (إن كان wireless debugging متاحًا، وهو ليس بديلًا نظيفًا عن `rish`).

### 5.2 لماذا لا نضعه داخل Ubuntu؟

- `rish` يعتمد على `app_process` وبيئة Android، لا على glibc/Ubuntu.
- تشغيله داخل proot غالبًا يفشل أو يعطي صلاحيات خاطئة.
- حتى لو نجح، سيكون هشًا وغير قابل للتشخيص.
- الفصل بين الطبقتين أنظف: Termux ينفذ أوامر Android، Ubuntu ينفذ أدوات Linux.

### 5.3 نمط التكامل المقترح

```text
طلب من Ubuntu
   ↓
سكربت في Ubuntu يكتب أمرًا مسموحًا إلى ملف/pipe
   ↓
Termux host يقرأ الأمر
   ↓
rish -c "<allowlisted command>"
   ↓
Shizuku (UID 2000)
```

لكن هذا معقد. الأفضل:

- استخدم Shizuku من **Termux مباشرة** عند الحاجة.
- لا تجعله جزءًا من دورة تشغيل Ubuntu.
- لا تمرر أوامر من Ubuntu إلى Android إلا عبر allowlist صريح.
- لا تشغّل أوامر عشوائية من واجهة Ubuntu.

### 5.4 تصميم wrapper آمن (مقترح لاحقًا)

```bash
# /data/data/com.termux/files/usr/local/bin/shizuku-exec
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

command -v rish >/dev/null 2>&1 || {
  echo "Shizuku/rish غير متاح" >&2
  exit 127
}

case "${1:-}" in
  packages)   rish -c 'pm list packages -3' ;;
  grant)      shift; [ "$#" -eq 2 ] || exit 2; rish -c "pm grant $1 $2" ;;
  revoke)     shift; [ "$#" -eq 2 ] || exit 2; rish -c "pm revoke $1 $2" ;;
  appops-get) shift; [ "$#" -eq 2 ] || exit 2; rish -c "appops get $1 $2" ;;
  settings-get) shift; [ "$#" -eq 2 ] || exit 2; rish -c "settings get $1 $2" ;;
  *) echo "أمر غير مسموح" >&2; exit 2 ;;
esac
```

هذا مجرد نموذج. لا تستخدم `eval` ولا تمرر نصًا حرًا من المستخدم إلى `rish -c`.

### 5.5 تركيب `rish` بدقة على Android 14+

هذا الجزء مهم وحسّاس. ملف `rish` الرسمي ليس binary، بل سكربت:

```sh
#!/system/bin/sh
BASEDIR=$(dirname "$0")
DEX="$BASEDIR"/rish_shizuku.dex
...
/system/bin/app_process -Djava.class.path="$DEX" /system/bin --nice-name=rish \
  rikka.shizuku.shell.ShizukuShellLoader "$@"
```

ويحتاج ملفين معًا:

- `rish`
- `rish_shizuku.dex`

تطبيق Shizuku يوفرهما من: **Use Shizuku in terminal apps**. لا تنزّلهما من مصدر عشوائي.

**مشكلة Android 14+:** `app_process` يرفض تحميل dex قابل للكتابة. إذا نسخت الملفين إلى `/sdcard`، فقد لا يعمل `chmod 400` لأن التخزين المشترك ليس POSIX كاملًا. الحل:

```bash
# داخل Termux، وليس داخل Ubuntu/proot
mkdir -p "$PREFIX/bin"
cp /sdcard/Shizuku/rish /sdcard/Shizuku/rish_shizuku.dex "$PREFIX/bin/"
chmod 700 "$PREFIX/bin/rish"
chmod 400 "$PREFIX/bin/rish_shizuku.dex"
export RISH_APPLICATION_ID=com.termux
```

ثم اختبر:

```bash
"$PREFIX/bin/rish" -c 'id'
```

المتوقع:

```text
uid=2000(shell) gid=2000(shell) ...
```

ملاحظات:

- إذا ظهر `Cannot find rish_shizuku.dex`، الملفان ليسا في نفس المجلد.
- إذا ظهر `On Android 14+, app_process cannot load writable dex`، انقل الملفين إلى مجلد Termux الخاص واجعل الdex للقراءة فقط.
- إذا ظهر `RISH_APPLICATION_ID is not set`، صدّر المتغير بالقيمة `com.termux`.
- لا تضع `rish` داخل Ubuntu؛ شغّله من Termux.
- `rish -c '...'` موثق رسميًا، وينفّذ `/system/bin/sh -c '...'` على Android، وليس داخل Ubuntu.
- إذا كان Shizuku يعمل تحت ADB، فإن `RISH_PRESERVE_ENV` افتراضيًا `0`: لا تفترض أن `PATH` أو `LD_PRELOAD` الخاص بـTermux متاح داخل الأمر. استخدم مسارات Android المطلقة، ولا تعتمد على أدوات Termux داخل `rish`.

---

## 6. الأمان والمخاطر

Shizuku server هو **هدف عالي القيمة**: أي تطبيق يُصرّح له يستطيع تنفيذ أوامر بصلاحية shell. لذلك:

1. **لا تثبّت Shizuku من مصادر غير رسمية.** استخدم GitHub Releases أو المتجر الرسمي.
2. **لا تمنح الصلاحية لتطبيقات غير موثوقة.**
3. **راجع التطبيقات المصرح لها** في Shizuku وأزل غير المستخدمة.
4. **لا تترك Wireless debugging مفعّلًا بلا حاجة.** لكن إبقاء USB debugging مطلوب لاستقرار Shizuku.
5. **لا تشارك رمز الاقتران** ولا منفذ ADB.
6. **سجّل الأوامر الحساسة** دون أسرار، واحتفظ بسجل تدقيق.
7. **`rish` متاح لأي عملية في Termux**؛ لا تجعله في `PATH` لتطبيقات غير موثوقة، وقيّد صلاحيات Termux.
8. **لا تستخدم `--no-sandbox` في Chromium كحل دائم**؛ هذا موضوع منفصل، لكنه يضعف العزل.
9. **افترض أن أي تطبيق Android يمكنه استدعاء الشيزوكو إذا مُنح الصلاحية**؛ لا تعتمد على "الخفاء".
10. **بعد الفحص، أوقف الخدمة أو ألغِ التصاريح** إذا لم تكن بحاجتها.

---

## 7. الأداء

- Shizuku نفسه خفيف نسبيًا؛ الخادم عملية Java واحدة، وليس عدة خدمات.
- `rish` يضيف عملية/جلسة عند كل استخدام؛ لا تستخدمه في حلقات سريعة.
- الأوامر المجمعة أفضل من مئات الاستدعاءات.
- لا تشغّل Shizuku + Antigravity + Chromium + code-server معًا على 8GB RAM دون مراقبة.
- Shizuku لا يحسّن رسومات Ubuntu ولا الصوت؛ لا علاقة له بـllvmpipe أو PulseAudio.
- لا تتوقع تحسين أداء proot منه؛ هو طبقة Android موازية.

---

## 8. خطة التحقق الميداني (عند عودة الهاتف)

### 8.1 التحقق من الاتصال

على Linux:

```bash
adb devices -l
```

على Termux:

```bash
python3 /root/Desktop/مهارات/.agents/skills/phone-bridge/scripts/phone_bridge.py status
```

**تحذير:** `status` يستدعي `ensure_connected()` ثم `auto_discover_port()`، الذي يفحص المنافذ `30000–49999` بـ120 عاملًا. إذا كان الهاتف غير متصل، قد يستغرق دقائق ويبدو كأنه معلّق. للتحقق السريع استخدم `adb connect` المستهدف بدلًا من الفحص الواسع:

```bash
adb connect <PHONE_IP>:<WIRELESS_DEBUGGING_PORT>
adb devices -l
```

لا تفترض أن Shizuku يعمل. في جولة التحقق الحالية:

- `phone_bridge.py status` تجاوز 35 ثانية؛
- `adb devices -l` أعاد قائمة فارغة؛
- ping للعنوان المحفوظ أعاد 100% packet loss؛
- لا يوجد اتصال TCP مؤكد بالهاتف.

لذلك أي حكم على تطبيق Shizuku المثبت يظل **غير متحقق** حتى يعود الاتصال.

### 8.2 التحقق من التطبيق

من ADB/Termux:

```bash
adb shell pm path moe.shizuku.privileged.api
adb shell dumpsys package moe.shizuku.privileged.api | grep -E 'versionName|versionCode|firstInstallTime|lastUpdateTime'
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
```

المتوقع:

- `package:/data/app/.../moe.shizuku.privileged.api.../base.apk`
- إصدار 13.x
- Android 11+ لمسار Wireless debugging

### 8.3 التحقق من الخدمة والصلاحية

من Shizuku app:

- هل الحالة "Running"؟
- هل بدأت عبر ADB أم root؟
- ما التطبيقات المصرح لها؟

من Termux (بعد توفير `rish`):

```bash
rish -c 'id'
rish -c 'getprop ro.product.model'
rish -c 'pm list packages | head'
rish -c 'settings get global adb_enabled'
```

المتوقع:

```text
uid=2000(shell) gid=2000(shell) ...
```

إذا ظهر `uid=0(root)` فهذا root، وليس مسارنا.

### 8.4 اختبارات سلبية (مهمة)

```bash
rish -c 'ls /data/data/com.android.settings'   # يجب أن يفشل
rish -c 'su -c id'                             # يجب أن يفشل
rish -c 'mount -o remount,rw /system'          # يجب أن يفشل
```

هذه الاختبارات تثبت أن Shizuku ليس root، وتمنع بناء توقعات خاطئة.

### 8.5 اختبار reboot

1. أعد تشغيل الهاتف.
2. تحقق: `rish -c id` يجب أن يفشل أو يقول الخدمة غير متاحة.
3. أعد تفعيل Shizuku عبر Wireless debugging.
4. أعد الاختبار.

هذا يؤكد القيد الأساسي: **non-root Shizuku ليس persistent**.

---

## 9. قرار التصميم

### 9.1 هل نتبناه؟

**نعم، كطبقة ثانوية اختيارية.** ليس كجزء من نواة النظام.

### 9.2 شروط التبني

- لا تعتمد عليه أي وظيفة أساسية في Ubuntu أو XFCE أو Chromium.
- لا يُشغّل تلقائيًا مع Ubuntu.
- لا يُمنح صلاحية لتطبيقات غير مراجعة.
- لا يُستخدم لأوامر عشوائية.
- يوجد fallback واضح عند غيابه.
- يوجد سجل تدقيق للأوامر.
- لا يُخزّن أسرارًا في سجلاته.
- **يُصلح `phone_bridge.py` أولًا:** الدالة `cmd_exec()` تفحص `which rish` على الجهاز الذي يعمل عليه السكربت. إذا كان السكربت على Linux، فإن وجود `rish` هناك سيُنفّذ الأمر محليًا بدل الهاتف؛ وإذا كان على Termux، فسيُنفّذه على Android. هذا سلوك غامض ويجب فصله صراحةً: `rish` للهاتف فقط، والخادم Linux لا يفترض وجوده.

### 9.3 ما لا نفعله الآن

- لا نثبت APK.
- لا نفعّل Wireless debugging.
- لا نغيّر إعدادات الهاتف.
- لا ننشئ `rish` أو wrapper قبل التحقق من الحاجة.
- لا نجعله شرطًا لأي مرحلة في `termux-rebuild-from-zero.md`.

### 9.4 الخطوة التالية المقترحة

1. إعادة توصيل الهاتف والتحقق من `adb devices`.
2. قراءة إصدار Android ونسخة Shizuku إن كان مثبتًا.
3. تنفيذ 5 أوامر قراءة فقط عبر `rish` (إن توفر).
4. مقارنة النتائج مع الجدول في القسم 4.
5. إذا أثبت فائدة واضحة، نبني wrapper allowlist صغيرًا.
6. إذا لم يثبت، يبقى خارج النظام.

---

## 10. ملخص المخاطر والفوائد

| البند | التقييم |
|---|---|
| الفائدة | تحكم Android أعمق من Termux:API، بدون كمبيوتر |
| التكلفة | تفعيل يدوي بعد كل reboot، قيود ADB، سطح هجوم إضافي |
| الأصالة | **غير أصيل**: ليس root، وليس خدمة نظام دائمة |
| الأداء | خفيف نسبيًا، لكن `rish` ليس للاستخدام المكثف |
| الأمان | متوسط إلى مرتفع الخطورة؛ يحتاج تقييدًا صارمًا |
| الاعتمادية | منخفضة كمكوّن أساسي؛ مقبولة كميزة ثانوية |
| القرار | **ثانوي/اختياري، لا أساسي** |

---

## 11. المراجع

- Shizuku — GitHub: https://github.com/RikkaApps/Shizuku
- Shizuku — دليل البدء: https://shizuku.rikka.app/guide/setup/
- Shizuku API — GitHub: https://github.com/RikkaApps/Shizuku-API
- Shizuku API — README: https://github.com/RikkaApps/Shizuku-API/blob/master/README.md
- أحدث إصدار: https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0
- أصل `rish`: https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/assets/rish
- `ShizukuShellLoader`: https://github.com/RikkaApps/Shizuku/blob/master/shell/src/main/java/rikka/shizuku/shell/ShizukuShellLoader.java
- `rish` README: https://github.com/RikkaApps/Shizuku-API/blob/master/rish/README.md
- `rish` source: https://github.com/RikkaApps/Shizuku-API/tree/master/rish
- AOSP Shell permissions: https://cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/Shell/AndroidManifest.xml

---

## 12. إفادة عدم التعديل

هذا التقرير **لم يثبّت Shizuku**، ولم يفعّل Wireless debugging، ولم يغيّر أي إعداد على الهاتف أو Termux أو Ubuntu. كل ما ورد من أوامر هو **مقترح للتحقق**، ويحتاج موافقة صريحة قبل التنفيذ.
