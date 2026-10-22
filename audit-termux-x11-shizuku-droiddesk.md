# تدقيق موسّع: Termux، Termux:X11، Shizuku، وDroidDesk

**تاريخ التحقق:** 2026-10-21  
**النطاق:** مراجعة التقارير المحلية مقابل المستودعات الحية، الإصدارات، الشيفرة المصدرية، وملفات APK المنشورة.  
**الطريقة:** تحليل ساكن فقط؛ لا تشغيل للتطبيقات، ولا اختبار على هاتف، ولا تعديل لأي مشروع.

---

## 1. الحكم التنفيذي

| المشروع | الحالة العامة | أهم ما يحتاج تصحيحًا أو معالجة |
|---|---|---|
| **Termux** | حي وصيانته نشطة في المستودع، لكن آخر إصدار تطبيق هو `v0.118.3` (2025-05-22) | APK الحالي **غير متوافق مع أجهزة 16 KB page size**: `libtermux.so` و`libtermux-bootstrap.so` بمحاذاة `0x1000`. كذلك GitHub builds موقّعة بمفتاح اختبار عام (`CN=APK Signer,OU=Earth,O=Earth`) وv2 فقط |
| **Termux:X11** | حي جدًا؛ nightly `1.03.01` بتاريخ 2026-10-06 وآخر commits في 2026-10-20 | التوثيق **يشترط Android 8+** وليس Android 7؛ و`sharedUid` يعمل فقط مع Termux من GitHub لأنه يستخدم **نفس مفتاح الاختبار** |
| **Shizuku** | مستقر تاريخيًا لكنه **قديم**: آخر إصدار `v13.6.0` (2025-05-25)، وآخر push (2025-06-18) | التقرير يقول Android 6.0+، لكن APK الرسمي `minSdk 24` أي **Android 7.0+**. توجد مشاكل Android 16/One UI 8 مفتوحة بلا إصدار أحدث |
| **DroidDesk** | حي؛ `v1.3.1` في 2026-10-14، وتوقيعه الرسمي سليم | **المسار non-root ليس PRoot** بل Termux prefix أصلي مع `libsocket_hook.so`؛ PRoot موجود فقط لـDebian الاختياري. README متناقض. تنزيل rootfs و`fetch_deps.sh` بلا checksums |

**أهم نتيجة جديدة:** مشكلة Termux 16 KB ليست نظرية. فحص APK الفعلي يُظهر:

```text
lib/arm64-v8a/libtermux.so            LOAD ... Align 0x1000
lib/arm64-v8a/libtermux-bootstrap.so  LOAD ... Align 0x1000
bootstrap/bin/bash                    LOAD ... Align 0x4000
lib/arm64-v8a/libXlorie.so (X11)      LOAD ... Align 0x4000
```

أي أن محتوى bootstrap صار متوافقًا، لكن مكتبات تطبيق Termux التي تحمّله ما زالت 4 KB. لذلك قد يفشل APK نفسه على جهاز 16 KB قبل الوصول إلى bootstrap.

---

## 2. المنهجية وحدود الثقة

- المصادر الحية: GitHub REST API للمستودعات والإصدارات، ملفات `build.gradle` و`gradle.properties` الخام، READMEs، وقوائم issues.
- تم تنزيل APK المنشورة والتحقق منها بـ`sha256sum`، ثم تحليل APK Signing Block بـ`python3`/`cryptography`، وفحص ELF بـ`readelf -lW`.
- لم تُجرَ اختبارات تشغيل أو استغلال. كل ما يخص 16 KB أو Android 16 هو استنتاج من الشيفرة/البيانات المتاحة، وليس اختبارًا على جهاز.
- التقرير لا يعيد تدقيق DroidDeck؛ انظر `audit-droiddeck-droiddesk.md`.

---

## 3. Termux

### 3.1 البيانات الحية

| العنصر | القيمة |
|---|---|
| المستودع | `termux/termux-app` |
| النجوم / forks / issues مفتوحة | 63,101 / 7,971 / 645 |
| أُنشئ / آخر push | 2015-10-23 / 2026-09-26 |
| آخر إصدار | `v0.118.3` — 2025-05-22 |
| APK arm64 | 35,106,607 بايت، 1,308,641 تحميلًا |
| SHA-256 للـAPK | `72fdb596045116bf5ba1b5bdf5b26fddb9acc0bd074ad9f2da9eb0ae85e83a4e` |
| شهادة التوقيع | `CN=APK Signer,OU=Earth,O=Earth` |
| بصمة الشهادة SHA-256 | `b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1` |
| إصدارات التوقيع | v2 فقط (لا v1 ولا v3) |
| `minSdk` / `targetSdk` / `compileSdk` | 21 / 28 / 36 |
| `ndkVersion` | 29.0.14206865 |
| مستودع الحزم | `termux/termux-packages` — 17,207 نجمة، آخر push 2026-10-21 |
| Bootstrap حديث | `bootstrap-2026.10.18-r1+apt.android-7` |

ملاحظة: كون APK المنشور v2-only يعني أنه غير قابل للتثبيت على Android 5/6 رغم `minSdk 21`. README يوجّه مستخدمي Android 5/6 إلى builds من نوع `apt-android-5`، لا إلى أصول `github-debug` هذه. لا تخلط بينها.

### 3.2 لماذا `targetSdk 28`؟

هذا قرار مقصود وليس إهمالًا:

- يسمح بتنفيذ ملفات من دليل التطبيق (`files/`) على Android 10+.
- يسمح بسلوكيات قديمة يحتاجها نموذج Termux، ومنها `sharedUserId`.
- رفع `targetSdk` إلى 29+ يفرض W^X وقيود `exec` تكسر نموذج التشغيل الأساسي.

النتيجة: Termux يعمل بنموذج أمان Android قديم نسبيًا. هذا ليس ثغرة بحد ذاته، لكنه يعني أن التطبيق لا يستفيد من تقييدات المنصة الحديثة، ويجب التعامل معه كبيئة تشغيل موثوقة لا كتطبيق عادي.

### 3.3 التوقيع: مفتاح اختبار عام

README الرسمي يحذّر صراحةً:

> APK files on GitHub are signed with a test key that has been shared with community. This IS NOT an official developer key and everyone can use it to generate releases for own testing... Everyone is able to use it to forge a malicious Termux update installable over the GitHub build.

(المفتاح نفسه موجود في المستودع باسم `app/testkey_untrusted.jks`.)

التحقق الفعلي يؤكد ذلك:

- الشهادة `CN=APK Signer,OU=Earth,O=Earth`.
- البصمة `b6da0148...` مطابقة لـTermux:X11 أيضًا.
- لا يوجد v3؛ أي لا توجد آلية key rotation.
- التطبيق يستخدم `sharedUserId=com.termux`، وكل إضافاته يجب أن تكون بنفس التوقيع.

**الأثر:** مستخدم GitHub builds معرّض نظريًا لتحديث موقّع بمفتاح الاختبار العام. F-Droid builds موقّعة بمفتاح مختلف، والخلط بينهما ممنوع. التوصية العملية: F-Droid أو بناء موثوق، وعدم تثبيت APK من Telegram/مواقع عشوائية.

### 3.4 مشكلة 16 KB page size

Android 15+ يدعم أجهزة 16 KB، والتطبيقات التي تحمّل مكتبات أصلية يجب أن تكون `LOAD` segments بمحاذاة 16 KB. فحص `v0.118.3`:

```text
libtermux.so:
  LOAD 0x000000 ... 0x0018e0 ... R E 0x1000
  LOAD 0x0018e0 ... 0x0002d8 ... RW  0x1000

libtermux-bootstrap.so:
  LOAD 0x000000 ... 0x1c076f0 ... R E 0x1000
  LOAD 0x1c076f0 ... 0x0001f8 ... RW  0x1000
```

بالمقابل:

```text
bootstrap/bin/bash: 0x4000
libXlorie.so (X11): 0x4000
DroidDesk libs:     0x4000
```

**التقييم:** الإصدار المنشور غير متوافق مع أجهزة 16 KB. Issue `#5156` اقترح إضافة `-Wl,-z,max-page-size=16384`، لكن الشيفرة الحالية لا تحمل العلم، والـAPK الفعلي ما زال `0x1000`. Issue `#4185` أُغلق في 2025-09-02، و`#5156` وُسم كتكرار في 2026-06-22؛ إغلاق الـissue لا يعني إصلاح الـAPK.

**الأثر:** على جهاز يعمل بصفحات 16 KB، قد يفشل Termux في تحميل `libtermux.so` أو `libtermux-bootstrap.so` عند الإقلاع. الأجهزة ذات 4 KB تعمل طبيعيًا. هذه مشكلة توافق عالية لبعض أجهزة Android 15/16، وليست ثغرة أمنية. ملاحظة: شرط Google Play لمحاذاة 16 KB يسري على التطبيقات التي تستهدف API 35+؛ Termux يستهدف 28، لكن جهاز 16 KB نفسه يحتاج مكتبات متوافقة، لذا تبقى المشكلة عملية لا تنظيمية.

**الإصلاح المقترح:** إضافة `-Wl,-z,max-page-size=16384` إلى `terminal-emulator` و`app` ndkBuild، أو ترقية إعداد link إلى NDK 29 مع التحقق، ثم إصدار APK جديد وفحصه بـ`readelf`.

### 3.5 قيود Android 12+ والصيانة

- README يحذر من phantom process killer: النظام يقتل العمليات فوق 32 أو التي تستهلك CPU، فتظهر `[Process completed (signal 9)]`.
- آخر إصدار تطبيق منذ نحو 17 شهرًا، رغم وجود commits في master حتى 2026-09-26. مستخدم الإصدار المستقر لا يحصل على إصلاحات master.
- مستودع الحزم نشط جدًا (push 2026-10-21)، لذا المشكلة في وتيرة إصدار التطبيق لا في الحزم.

### 3.6 ما ينبغي إضافته إلى تقرير Termux

- قسم توقيع وسلسلة ثقة (testkey، v2 فقط، `sharedUserId`).
- قسم 16 KB مع نتيجة `readelf` الفعلية.
- تحديث أن `targetSdk 28` قرار مقصود، وليس مجرد «تخلف».
- توضيح أن GitHub builds debug/testkey وليست إصدارات إنتاجية موثوقة.

---

## 4. Termux:X11

### 4.1 البيانات الحية

| العنصر | القيمة |
|---|---|
| المستودع | `termux/termux-x11` |
| النجوم / forks / issues مفتوحة | 4,988 / 794 / 26 |
| أُنشئ / آخر push | 2019-08-25 / 2026-10-20 |
| آخر إصدار | nightly `20261006` — الإصدار `1.03.01` |
| APK universal-debug | 14,693,944 بايت، 21,802 تحميلًا |
| APK sharedUid-debug | 14,693,972 بايت، 4,672 تحميلًا |
| SHA-256 للـAPK universal | `64995746d1887a0e70bc8977d4a9c5ea56e3e00eadd4a6eeab32ae6048ba733a` |
| شهادة التوقيع | نفس شهادة Termux: `CN=APK Signer,OU=Earth,O=Earth` |
| بصمة الشهادة | `b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1` |
| إصدارات التوقيع | v2 فقط |
| `minSdk` / `compileSdk` | 24 / 34 |
| `targetSdk` | 34 للنسخة المستقلة، 28 لـ`sharedUid` |
| `libXlorie.so` | محاذاة `0x4000` — متوافق مع 16 KB |

### 4.2 المعمارية

- تطبيق Android + حزمة `termux-x11-nightly` من `x11-repo`.
- `lorie` هو خادم X الأصلي (C/C++)، و`lorie-app` واجهة Android، و`shell-loader` يشغّل الخادم ويولّد الحزمة.
- `sharedUid` variant يعلن:

```xml
android:sharedUserId="com.termux"
android:process="com.termux"
```

أي أنه يعمل داخل عملية Termux نفسها لتجنب cpuset/phantom killer. هذا يفسر تحسين الأداء، لكنه يربطه بتوقيع Termux.

### 4.3 التصحيح المهم: Android 8+ وليس 7

- `minSdkVersion 24` (Android 7.0) في Gradle.
- README يقول صراحةً: **Termux:X11 requires Android 8 or later**.
- لذلك عبارة التقرير «API 24 عمليًا» مضللة: التطبيق قد يُثبّت على 7، لكنه غير مدعوم رسميًا. الصياغة الأدق: «minSdk 24، لكن المتطلب الرسمي Android 8+ (API 26)».

### 4.4 `sharedUid` وسلسلة الثقة

- README: النسخة `sharedUid` تعمل فقط مع Termux المثبت من GitHub، لا F-Droid ولا Play، لأن `sharedUid` يتطلب نفس التوقيع.
- التحقق يؤكد أن APK X11 موقّع بنفس مفتاح Termux GitHub (`b6da...`).
- **الأثر:** من يستخدم `sharedUid` يضع Termux وX11 في نفس مجال الثقة. أي تطبيق موقّع بمفتاح الاختبار العام ويعلن `sharedUserId=com.termux` قد يصبح قادرًا على الوصول لبيانات Termux على الأجهزة التي ما زالت تسمح بـ`sharedUserId` (targetSdk 28). هذا ليس exploit جاهزًا، لكنه يوسّع سطح الثقة ويستوجب توثيقًا واضحًا.

### 4.5 إضافات جديدة يستحقها التقرير

- `-tag <tag>`: تشغيل عدة شاشات X ك نوافذ Android منفصلة.
- `-legacy-drawing`: حل الشاشة السوداء مع مؤشر على بعض الأجهزة.
- `-force-bgra`: حل الألوان المعكوسة.
- `libXlorie.so` متوافق مع 16 KB، بعكس تطبيق Termux نفسه.

### 4.6 تصحيحات لتقرير X11

- إضافة الإصدار الحالي `1.03.01` وتاريخ nightly `2026-10-06`.
- تصحيح «API 24 عمليًا» إلى «minSdk 24 لكن التوثيق يشترط Android 8+».
- إضافة بصمة التوقيع وربطها بـTermux.
- أرقام «176 ملفًا / 23 Java / 8,800 C/C++» تقديرية وقد تغيّرت؛ يُفضّل إعادة عدّها أو وصفها بأنها تقريبية.

---

## 5. Shizuku

### 5.1 البيانات الحية

| العنصر | القيمة |
|---|---|
| المستودع | `RikkaApps/Shizuku` |
| النجوم / forks / issues مفتوحة | 31,901 / 3,395 / 677 |
| أُنشئ / آخر push | 2017-05-24 / **2025-06-18** |
| آخر إصدار | `v13.6.0` — 2025-05-25 |
| APK | 2,571,773 بايت، 3,292,573 تحميلًا |
| SHA-256 | `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f` |
| شهادة التوقيع | `CN=Rikka` |
| بصمة الشهادة | `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30` |
| إصدارات التوقيع | v2 فقط |
| `minSdk` / `targetSdk` / `compileSdk` | **24** / 36 / 36 |

### 5.2 تصحيح: Android 7.0+ وليس 6.0+

- تقرير Shizuku يقول «Android 6.0+» نقلًا عن README الخاص بـShizuku-API.
- README الـAPI يقول إن Shizuku وSui يتطلبان Android 6.0+.
- لكن APK الرسمي `v13.6.0` مبني بـ`minSdk 24`، أي **Android 7.0+**. لا يمكن تثبيته على Android 6.
- الصياغة الصحيحة: «API library قد تدعم 6.0+، لكن التطبيق الرسمي يتطلب Android 7.0+».

### 5.3 نموذج العمل والأمان

- Shizuku ليس root: في وضع ADB يعمل الخادم بصلاحيات `shell` (uid 2000).
- يعمل كوسيط Binder بين التطبيقات وخدمات النظام؛ التطبيقات لا تحصل على root، بل على الصلاحيات التي يملكها ADB.
- ADB permissions محدودة وتختلف بين إصدارات Android.
- Wireless debugging: Android 11+، ويحتاج إعادة تفعيل بعد كل reboot.
- USB ADB: Android 10 وأقدم.
- `rish`: shell تفاعلي يستخدم صلاحيات Shizuku؛ يحتاج تثبيتًا/إعدادًا.
- وضع root: يشغّل الخادم كـroot، ويوفر صلاحيات أوسع لكنه يفقد نموذج «بدون root».

### 5.4 مخاطر الصيانة وتوافق Android 16

- آخر إصدار منذ نحو 17 شهرًا، وآخر push منذ 2025-06-18.
- توجد issues مفتوحة على Android 16/One UI 8:
  - `#2596`: Shizuku ينقطع تلقائيًا عند تفعيل Wireless debugging على Android 16 (Samsung S25 Ultra، One UI 8.5).
  - `#1459`: Shizuku يتوقف بعد دقائق أو عند إطفاء الشاشة على One UI 8.
  - `#2180`: تقرير عن Android 17.
- `targetSdk 36` في المصدر لا يعني أن الإصدار المنشور اختُبر على Android 16؛ الإصدار سبق Android 16 النهائي.
- **التقييم:** لا توجد ثغرة معروفة مثبتة، لكن الاعتماد على Shizuku كطبقة أساسية على Android 16/17 يحمل مخاطر توافق وصيانة. الأفضل استخدامه كطبقة ثانوية، مع خطة بديلة.

### 5.5 التوقيع

- APK موقّع بشهادة `CN=Rikka`، v2 فقط.
- `minSdk 24` يعني أن v2 كافٍ (Android 7+).
- لا يوجد v3، أي لا يوجد key rotation؛ ليس خطرًا مباشرًا، لكنه قيد مستقبلي.
- مكتبات APK الأصلية `libadb.so` و`librish.so` و`libshizuku.so` بمحاذاة `0x4000`؛ متوافقة مع 16 KB.

### 5.6 تصحيحات لتقرير Shizuku

- استبدال «Android 6.0+» بـ«APK الرسمي: Android 7.0+ (minSdk 24)».
- إضافة عمر الإصدار (17 شهرًا) وissues Android 16.
- إضافة بصمة APK/الشهادة إن أردنا توثيق سلسلة التوريد.
- توضيح أن `targetSdk 36` لا يضمن اختبار Android 16.

---

## 6. DroidDesk

### 6.1 البيانات الحية

| العنصر | القيمة |
|---|---|
| المستودع | `orailnoor/DroidDesk` |
| النجوم / forks / issues مفتوحة | 4,343 / 403 / 84 |
| أُنشئ / آخر push | 2026-04-20 / 2026-10-14 |
| الرخصة | GPL-3.0 |
| آخر إصدار | `v1.3.1` — 2026-10-14 |
| APK | 42,398,714 بايت، 3,882 تحميلًا |
| SHA-256 | `a2ff81c07883c8bd57a5560827b373da3055817b2f7ba3cfd1825c4d92d97e9e` |
| شهادة التوقيع | `CN=DroidDesk, O=DroidDesk, C=IN` |
| بصمة الشهادة | `74c93922d73d6c62944c4f69411c081b7b5621882a13d7739652e30e323d8dff` |
| إصدارات التوقيع | v2 فقط (كافٍ لأن `minSdk 28`) |
| `minSdk` / `targetSdk` | 28 / 28 |

### 6.2 تصحيح جوهري: المسار non-root ليس PRoot

README يقول في سطر واحد:

> Non-rooted phones: Run an app-private native Termux userspace... **PRoot is not used.**

لكنه يقول في موضع آخر:

> For tools not available in TUR... a **Proot container** provides a standard Ubuntu/Debian/Kali environment.

والكود يوضح الصورة:

- `LinuxRuntime.kt:15`: *Runs native Termux Linux on Android without PRoot.*
- `LinuxRuntime.kt` يستخدم `libsocket_hook.so` عبر `LD_PRELOAD` لإعادة توجيه المسارات من `/data/data/com.termux/files/usr` إلى prefix التطبيق.
- `LinuxRuntime.kt:151` يعرض `proot_debian` كخيار اختياري.
- `LinuxRuntime.kt:176-190` يكتب launcher يستدعي `proot-distro login debian` عند تثبيت Debian.
- `ChrootRuntime.kt` هو مسار root الحقيقي: `chroot` + `su` + bind mounts.
- `jniLibs/arm64-v8a/` تحتوي `libproot.so` و`libproot-loader.so` حتى في الوضع non-root.

**الخلاصة الدقيقة:**

| الوضع | التقنية |
|---|---|
| Non-root desktop الافتراضي | Termux prefix أصلي + `socket_hook`، **بدون PRoot** |
| Debian اختياري داخل non-root | PRoot (`proot-distro`) |
| Root desktop | `chroot` حقيقي + `su` |

لذا عبارة README «PRoot is not used» صحيحة للمسار الافتراضي فقط، لكنها مطلقة ومضللة لأن PRoot مضمّن ومستخدم للـDebian الاختياري. هذا تناقض توثيقي يستحق التصحيح.

### 6.3 سلسلة التوريد

- `RootfsManager.kt:35-37` ينزّل:
  - Ubuntu 24.04.4 base من `cdimage.ubuntu.com`
  - Alpine 3.20 من `dl-cdn.alpinelinux.org`
  - Kali NetHunter من `kali.download`
- لا يوجد تحقق SHA-256 أو توقيع GPG بعد التنزيل؛ الاعتماد على HTTPS فقط.
- `fetch_deps.sh:28` ينزّل `.deb` من `packages-cf.termux.dev` بلا checksum قبل فك الضغط والنسخ إلى `jniLibs`.
- `COMPLIANCE.md` يعترف صراحةً بأن هذه العناصر **غير مكتملة**:
  - تحديد commit Termux:X11 الدقيق وpatches المستخدمة لبناء `libXlorie.so`.
  - مصدر/بناء قابل للتكرار لـ`libandroid-shmem.so` و`libtalloc.so`.
  - إعادة بناء bootstrap لـ`com.orailnoor.droiddesk` بدل نقل binaries مبنية لـ`com.termux`.
  - manifest قابل للقراءة آليًا بأسماء الحزم والإصدارات والتراخيص والـhashes.
- هذه فجوة سلسلة توريد/امتثال أكثر منها ثغرة مباشرة، لكنها تمنع التحقق الكامل من أن الـAPK المنشور يطابق المصدر المعلن.

### 6.4 التوقيع والمُحدّث

- `v1.3.1` موقّع بشهادة إصدار حقيقية، وليس debug.
- `AppUpdater.kt:147-176` يتحقق من:
  - اسم الحزمة `com.orailnoor.droiddesk`.
  - تقاطع بصمات SHA-256 بين APK المنزّل والتطبيق المثبت.
- هذا جيد: لا يقبل تحديثًا موقّعًا بمفتاح مختلف.
- `build.gradle.kts:66-69` يسقط إلى `signingConfigs.getByName("debug")` عند غياب `releaseStoreFile`. الإصدار الرسمي سليم، لكن أي بناء release محلي بلا مفتاح سينتج APK debug. يُستحسن جعل ذلك خطأً فادحًا.

### 6.5 سطح الأمان والقيود

- `targetSdk 28` مقصود لتعطيل W^X والسماح بتنفيذ binaries من app data. هذا يضحي بسلوكيات أمان حديثة.
- Manifest يتضمن `MANAGE_EXTERNAL_STORAGE`، `QUERY_ALL_PACKAGES`، `REQUEST_INSTALL_PACKAGES`، و`usesCleartextTraffic="true"`.
- وضع root:
  - `chroot` كامل مع bind mounts لـ`/dev`، `/proc`، `/sys`.
  - يعمل كـroot، وأي تطبيق داخل الجلسة يملك root.
  - لا توجد mount/PID namespaces؛ `chroot` ليس حاوية أمنية.
- `libXlorie.so` مسبق البناء وغير موثّق المصدر بدقة.
- جميع مكتبات arm64 المفحوصة متوافقة مع 16 KB (`0x4000`)، بما فيها `libXlorie.so` و`libsocket_hook.so` و`libproot.so` (بعضها `0x10000`).

### 6.6 مشاكل PRoot الاختياري

- `#40`: بعد تثبيت Debian، `start-debian` يفشل بـ`libandroid-support.so not found`.
- `#73`: تثبيت تطبيقات داخل Proot Distro يفشل بـ`container 'ubuntu' is not installed`.
- هذا لا يمس المسار الافتراضي native، لكنه يثبت أن مسار Debian الاختياري غير مستقر، ويجب توثيقه كتجريبي.

### 6.7 تصحيحات لتقرير DroidDesk

- إضافة تفصيل native/chroot/PRoot؛ التقرير المعماري الحالي دقيق، لكن تقرير التدقيق لا يشرحه.
- إضافة أن rootfs و`fetch_deps.sh` بلا checksums.
- تحديث عدد التحميلات إلى 3,882 (بدل 3,807).
- توضيح تناقض README حول PRoot.
- إضافة نتيجة 16 KB للمكتبات.

---

## 7. مصفوفة الشدة

| ID | النتيجة | النوع | الشدة | الإجراء |
|---|---|---|---|---|
| T1 | Termux 0.118.3 مكتبات APK بمحاذاة 4 KB | توافق 16 KB | **عالية** للأجهزة المتأثرة | إصلاح link + إصدار جديد + فحص `readelf` |
| T2 | GitHub Termux builds بمفتاح اختبار عام v2 فقط | سلسلة تحديث | **متوسطة–عالية** | F-Droid أو بناء موثوق؛ توثيق واضح |
| X1 | X11 يتطلب Android 8+ لكن minSdk 24 | دقة توثيق | منخفضة | تصحيح التقرير |
| X2 | sharedUid يستخدم نفس مفتاح Termux العام | توسيع ثقة | **متوسطة** | لا تستخدمه مع builds غير موثوقة |
| S1 | Shizuku بلا إصدار منذ 17 شهرًا ومشاكل Android 16 | صيانة/توافق | **متوسطة** | اختبار Android 16، خطة بديلة |
| S2 | تقرير Shizuku يقول Android 6.0+ | دقة | منخفضة | تصحيح إلى 7.0+ |
| D1 | rootfs و`fetch_deps` بلا checksums | سلسلة توريد | **متوسطة** | SHA-256/GPG وتوقيع الحزم |
| D2 | README يناقض نفسه حول PRoot | توثيق | منخفضة | تصحيح الصياغة |
| D3 | targetSdk 28 وسطح صلاحيات واسع | أمان مقصود | **متوسطة** | توثيق المخاطر، تقليل الصلاحيات |
| D4 | Debian PRoot الاختياري معطّل لدى مستخدمين | وظيفي | منخفضة | إصلاح أو وسم تجريبي |

---

## 8. التوصيات

### Termux
1. إصلاح 16 KB: إضافة `-Wl,-z,max-page-size=16384` في `terminal-emulator` و`app` ndkBuild، ثم إصدار `0.118.4` وفحص `libtermux.so` و`libtermux-bootstrap.so` بـ`readelf`.
2. إبقاء تحذير testkey واضحًا; يفضّل F-Droid للمستخدم العادي.
3. عدم استخدام GitHub builds في بيئات حساسة دون التحقق من المصدر.
4. توثيق أن `targetSdk 28` قرار مقصود، وليس دينًا تقنيًا.

### Termux:X11
1. تصحيح التوثيق إلى Android 8+، أو اختبار Android 7 فعليًا ودعمه.
2. توثيق أن `sharedUid` يستخدم مفتاح Termux العام، وأنه ليس عزلًا أمنيًا.
3. إضافة `-tag` و`-legacy-drawing` و`-force-bgra` إلى دليل الاستخدام.
4. إبقاء nightly كقناة تطوير؛ لا تعاملها كإصدار مستقر.

### Shizuku
1. تثبيت `v13.6.0` مع وعي بأنه قديم.
2. اختبار Android 16/One UI 8 قبل الاعتماد عليه.
3. عدم بناء وظائف أساسية عليه؛ استخدامه كطبقة ثانوية.
4. متابعة issues `#2596` و`#1459` و`#2180`.

### DroidDesk
1. إضافة SHA-256/GPG لكل rootfs و`.deb` قبل الاستخراج.
2. تصحيح README: native ليس PRoot؛ PRoot للـDebian الاختياري فقط.
3. توثيق commit `libXlorie.so` وبناء قابل للتكرار.
4. جعل غياب مفتاح الإصدار خطأً فادحًا في build release.
5. توثيق أن chroot ليس عزلًا أمنيًا، وأنه يمنح root كاملًا.
6. إصلاح أو وسم Debian PRoot كتجريبي.

---

## 9. ما لم يُتحقق

- لم يُختبر Termux على جهاز 16 KB فعلي؛ النتيجة من `readelf` فقط.
- لم تُختبر Shizuku على Android 16/One UI 8؛ الاعتماد على issues.
- لم تُجرَ اختبارات تشغيل لـDroidDesk native/chroot/PRoot.
- لم يُتحقق من أن كل حزم bootstrap الحالية 16 KB؛ تم فحص `bash` وبعض libs فقط.
- لم يُتحقق من تطابق `libXlorie.so` مع commit محدد من Termux:X11.
- لم تُراجع F-Droid builds أو Google Play variants.
- لم يُختبر أي استغلال لمفتاح الاختبار العام؛ الأثر نظري/مستند إلى توثيق Termux.

---

## 10. المراجع

- Termux app: <https://github.com/termux/termux-app>
- Termux packages: <https://github.com/termux/termux-packages>
- Termux:X11: <https://github.com/termux/termux-x11>
- Shizuku: <https://github.com/RikkaApps/Shizuku>
- Shizuku API: <https://github.com/RikkaApps/Shizuku-API>
- DroidDesk: <https://github.com/orailnoor/DroidDesk>
- Termux 16 KB issue: <https://github.com/termux/termux-app/issues/5156>
- Termux 16 KB duplicate: <https://github.com/termux/termux-app/issues/4185>
- Shizuku Android 16 issue: <https://github.com/RikkaApps/Shizuku/issues/2596>
- Shizuku One UI 8 issue: <https://github.com/RikkaApps/Shizuku/issues/1459>
- DroidDesk Proot issue: <https://github.com/orailnoor/DroidDesk/issues/73>
- DroidDesk Debian issue: <https://github.com/orailnoor/DroidDesk/issues/40>
