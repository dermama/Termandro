# تدقيق مستقل: تقريرا DroidDeck وDroidDesk

**تاريخ التدقيق:** 2026-10-21  
**الأساس:** لقطة DroidDeck عند `origin/main` = `0e7818049ab5ee0cf4409af0143c12ac98e6e1a8`، ولقطة DroidDesk بلا git.  
**النطاق:** تحليل ساكن للمصادر والتوثيق وبيانات GitHub الحية. لا تشغيل، لا تثبيت، لا تعديل للمشروعين.

---

## 1. الحكم التنفيذي

| التقرير | الدقة البنيوية | المشكلة الأخطر | الحكم |
|---|---|---|---|
| `droiddeck-architecture.md` | عالية في وصف العميل وFEX وgamescope وTurnip | **ادعاء مركزي خاطئ ومكرر:** «المشروع لا يبني Wine خاصًا به»، إضافة إلى APK يحمل testkey في v1/v2 | يحتاج تصحيحًا جوهريًا قبل الاعتماد عليه |
| `droiddesk-architecture.md` | عالية، ويغطي معظم نقاط الأمان المهمة | فجوة توقيع build (fallback إلى debug) وسلسلة توريد سكربتات الجلب | قريب من الصلاحية مع تحفظات محدودة |

**الخلاصة:** تقرير DroidDeck ليس مجرد «أرقام قديمة»؛ فيه خطأ معماري مركزي يمس الهوية التقنية للمشروع (Proton مخصص)، وفجوات أمنية غير مذكورة (bwrap/Flatpak، ADB/agent، Decky). كما ثبت أن APK 0.5.0 يوقّع v1/v2 بمفتاح AOSP testkey العام، ما يجعل أجهزة Android 8.x تقبل تحديثًا موقّعًا به. تقرير DroidDesk أدق نسبيًا، والإصدار الرسمي موقّع بمفتاح إصدار سليم، لكن يبقى خطر تكوين build عند البناء المحلي.

---

## 2. المنهجية والقيود

- فحص المصادر مباشرة: `app/src/main/java`, `app/src/main/cpp`, `docs/`, `tools/`, Gradle، manifest.
- التحقق من الادعاءات مقابل الكود، لا مقابل README وحده.
- جلب بيانات GitHub الحية (stars/forks/issues/releases/downloads) بتاريخ التدقيق.
- تم التحقق يدويًا من توقيع APK المنشور: `sha256sum`، ثم `openssl pkcs7` لشهادة v1، ومحلل APK Signing Block بـ`python3`/`cryptography` لشهادات v2/v3 (لا `apksigner`/`keytool` في البيئة).
- لم تُجرَ اختبارات تشغيل أو استغلال؛ نتائج التوقيع والأمان تحليلية.
- لا قياسات أداء أو اختبارات تشغيل؛ كل ما يخص الأداء في التقريرين يبقى ادعاءً غير متحقق.

---

## 3. تصحيحات DroidDeck

### 3.1 الخطأ المركزي: «لا يوجد Wine fork» — غير صحيح

**ما يقوله التقرير (مواضع متعددة):**

- سطر 37–38: «يشغّل عميل Steam الرسمي ARM64 وProton ARM64 الأصليين… المشروع لا يبني Wine خاصًا به، بل يستخدم Proton Experimental/Proton 11.0 من Steam».
- سطر 52: «عميل Steam الرسمي، Proton الرسمي ARM64».
- سطر 67–68: «ألعاب Windows عبر Proton ARM64 (وليس Wine fork)».
- سطر 224: «بدلًا من بناء Wine/Proton fork، يستخدم المشروع حزم Valve الرسمية».
- سطر 456: «لا Wine fork، لا Box64».
- سطر 707: جدول «Proton | ARM64 رسمي | Wine fork/Box64».
- سطر 887 و901: «تغليف للسلسلة الرسمية» و«استخدام Valve الرسمي بدل Wine fork».

**ما يثبته الكود:**

- `app/src/main/java/com/droiddeck/launcher/session/ProtonExtras.kt:29-33` يسجّل ثلاث أدوات، منها:
  - `Tool("droiddeck", "DroidDeck-Proton", "DroidDeck-Proton", "Droid-Deck/DroidDeck-Proton", Regex("aarch64\\.tar\\.(gz|xz)$"))`
  - مع `GE-Proton` و`proton-cachyos`.
- `docs/development/x86-64-steam-client.md:343-378`:
  - «Windows games run on the arm64 client's Protons» ويعدد `DroidDeck-Proton` ضمن أدوات ARM64.
  - «**Default.** The newest DroidDeck-Proton, then the newest GE-Proton (aarch64), else `droiddeck-proton-a64`».
  - يذكر تشغيل FlatOut على `DroidDeck-Proton 11-9.1` وفشله على `GE-Proton11-7`.
- `docs/releases/0.4.0.md` يعلن صراحة: «their own custom **DroidDeck-Proton**».
- مستودع `https://github.com/Droid-Deck/DroidDeck-Proton` (README): مبني على **GE-Proton**، مع Wine مبني على سبع طبقات (`bp-11.9-tier1`…`tier7`)، ومصدر Wine `The412Banner/proton-wine` فرع `banner-proton/11.9`، وكان اسمه سابقًا Banner-Proton.

**الأثر:** الخطأ لا يقتصر على الوصف؛ بل يبطل:

1. قسم «الرهان المعماري» (Valve ARM64 بدل Wine fork).
2. مقارنة Winlator/GameHub التي تفترض أن DroidDeck لا يملك fork.
3. تحليل الترخيص وسلسلة التوريد (Wine/Proton مخصص = سطح صيانة وثغرات إضافي).
4. تفسير التوافق: نجاح/فشل ألعاب قد يعتمد على DroidDeck-Proton لا على Proton الرسمي.

**التصحيح المطلوب:** استبدال كل عبارات «لا Wine fork» بوصف دقيق: «DroidDeck يدمج Proton الرسمي ARM64 كخيار أساسي، لكنه يوزّع أيضًا DroidDeck-Proton المخصص (GE-Proton + Wine سبع طبقات + تصحيحات CachyOS) ويجعله الافتراضي في عميل x86-64 عند توفره».

### 3.2 الفجوة الأمنية الكبرى: `droiddeck-bwrap` وFlatpak

**ما يقوله التقرير:** يذكر Flatpak/Flathub في السطر 43 وبعض الأسطر الوصفية فقط، ولا يذكر `bwrap` ولا أن sandbox الخاص بـFlatpak معطّل.

**ما يثبته الكود:**

- `tools/linuxfs/overlay/usr/local/bin/droiddeck-bwrap`: ليس bubblewrap؛ يترجم عمليات bwrap إلى **proot binds** ويرسل الطلب عبر `LocalServerSocket` باسم `${packageName}.bwrap` إلى التطبيق.
- `app/src/main/java/com/droiddeck/launcher/runtime/BwrapSpawner.kt`: فحص `peer.uid` للتطبيق نفسه فقط، لا mount/PID/network namespaces، و`/proc` و`/dev` مربوطان.
- `docs/development/flatpak.md` (سطر 28، «bubblewrap without namespaces»): Android لا يمنح التطبيقات user namespaces، لذلك يُوجَّه Flatpak إلى `droiddeck-bwrap` عبر `FLATPAK_BWRAP` (تصدّره `droiddeck-session` وأوامر المتجر).
- `app/src/main/java/com/droiddeck/launcher/runtime/FlatpakManager.kt` يستخدم `/usr/bin/flatpak` و`/usr/local/bin/droiddeck-bwrap`.
- `tools/linuxfs/overlay/usr/local/bin/droiddeck-flatpak` يضبط `FLATPAK_BWRAP` و`REMOTE=flathub`.
- الـshim يعطّل صراحة sandbox المتصفحات (`browserSandboxes` لـFirefox/Chromium تحت proot).
- اختبار موجود: `app/src/test/java/com/droiddeck/launcher/runtime/BwrapSpawnerTest.kt` (under/hostPath/bindsFor).

**الأثر:** أي تطبيق Flatpak يُثبَّت من Flathub يعمل بلا عزل حقيقي، وأي متصفح داخل الجلسة يفقد sandbox الخاص به. هذا بند أمني يجب أن يظهر في تقرير معماري يغطي سطح المكتب وFlatpak.

### 3.3 سطح ADB/agent غير مذكور

- `docs/agent-control.md` وسطح `content://com.droiddeck.launcher.agent` عبر `agent/AgentBridgeProvider.kt` و`AgentStartActivity`.
- محمي بـ`android.permission.DUMP` (يملكه ADB shell عادة)؛ يتيح تشغيل/إيقاف الجلسة، تشغيل/إنهاء ألعاب Steam، input، قراءة UI، logs، screenshots، recording، وتنفيذ أوامر داخل الجلسة.
- `droiddeck-agent` يعمل داخل كل جلسة ويتصل بـSteam Client DevTools.
- `WirelessAdbFix.kt` و`WirelessAdbPairingService.kt`: يخزنان شهادة ومفتاح ADB لمدة 10 سنوات في `noBackupFilesDir` (`wireless-adb-cert.pem`, `wireless-adb-key.pem`)، مع اقتران لاسلكي واكتشاف منفذ واستخدام ADB لتعديل قيود العمليات.

**الأثر:** توسيع سطح الهجوم المحلي وسطح «التحكم الآلي»؛ يجب ذكره في نموذج التهديد حتى لو كان محميًا بـDUMP.

### 3.4 Decky Loader وzRAM وsuspend غير مذكورة

- `DeckyManager.kt`: `REPOSITORY = "SteamDeckHomebrew/decky-loader"`، تحميل releases عبر GitHub API، `PATH = "root/homebrew/services/PluginLoader"`، marker `.droiddeck-decky-version`، وقوائم `CEF_REMOTE_DEBUG_MARKERS`، وتثبيت plugins عبر `DeckyPluginInstall.activate` — أي تنفيذ كود داخل الجلسة بحكم التصميم.
- `ZramSupport.kt` + `SessionPrefs.zramCompression` + `SessionService.kt` (حوالي 653) يشترط zRAM مع `MODE_STEAM`.
- `SessionSuspendController.kt` (317 سطرًا): تجميد/استئناف عبر `SIGSTOP`/`SIGCONT` مع snapshots للعمليات والخيوط.

هذه ليست أخطاءً في التقرير، لكنها فجوات تغطية تمنع فهم سلوك الجلسة على الأجهزة ذات الذاكرة المحدودة.

### 3.5 الأرقام والإحصاءات

- التقرير سطر 33 يصف 281 ملفًا بأنها «C/C++» في `app/src/main/cpp`. الدقيق: 90 ملف مصدر C/C++، و281 كل الملفات (بما فيها headers/موارد).
- تعداد DroidDeck: 462 Kotlin و23 Java في `app/src/main/java`، 296 Kotlin اختبار، 758 Kotlin في `app/src`.
- بيانات GitHub الحية (2026-10-21): 12,047 نجمة، 618 fork، 376 عنصرًا مفتوحًا. التقرير: 11,937 نجمة و613 fork.
- إصدار 0.5.0: أربعة APK بحجم 105,925,476 بايت؛ تحميلات القياسي 88,411 (التقرير: 83,492).
- آخر push: `2026-10-21T10:36:33Z`.

### 3.6 التوقيع وبيانات الاعتماد

- `build.gradle.kts` يوقّع بـ`signingConfigs.testkey` و`keystore/testkey.p12` (AOSP testkey عام).
- `.github/workflows/build.yml:611-637` يوقّع APK بـv1+v2+v3 باستخدام testkey، ثم **يشترط** وجود `CN=Android, OU=Android, O=Android` — أي أن الـCI يطلب توقيع testkey صراحةً.
- `tools/release/sign-apk.sh:44-57` يعيد التوقيع بمُوقِّعين: testkey ثم مفتاح الإصدار، مع `--rotation-min-sdk-version 28`. النتيجة المقصودة: **v1/v2 = testkey، v3 = مفتاح الإصدار**.
- `keystore/release-signer.sha256` = `b241ea7d77822d39b89d3227e06f3ecf62adec6cbfd38ad06196787991b5d3f0`.

**تحقق فعلي من APK 0.5.0 المنشور (2026-10-21):**

- SHA-256 = `ab6a53e41b6fa715bd3f9048ada53d22d8535ad2978d97943d723cf60fa430b1` ويطابق `SHA256SUMS.txt`.
- شهادة v1: `CN=Android, OU=Android, O=Android`، SHA-256 = `a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc` (نفس بصمة `keystore/testkey.p12`).
- شهادة v2: نفس شهادة testkey (`a40da8…`).
- شهادة v3: `CN=DroidDeck, O=The412Banner`، SHA-256 = `b241ea…d3f0` (مفتاح الإصدار).

**الأثر الأمني:** `minSdk 26` يعني أن Android 8.0/8.1 يتحقق عبر v1/v2 فقط، أي بمفتاح testkey **العام**. من يملك هذا المفتاح (متاح للجميع) يستطيع توقيع APK تقبله تلك الأجهزة كتحديث. على Android 9+ يُطبَّق v3 بمفتاح الإصدار، والـlineage يمنع rollback. هذا اختيار مقصود لضمان الترقية من البناءات القديمة، لكنه يعني أن أجهزة API 26–27 خارج سلسلة الثقة الحديثة. الحل: إسقاط 26–27، أو بناء قديم منفصل، أو توثيق القيد بوضوح.
- بيانات اعتماد المتاجر **مشفرة** عبر `StoreAccounts.kt` + `CredentialCipher.Keystore` (Android Keystore)، وليست نصًا صريحًا. التقرير لا يدعي العكس، لكنه لا يذكر هذه النقطة الإيجابية.
- `app/src/main/res/xml/network_security_config.xml` يسمح بـcleartext لـ`127.0.0.1` و`localhost` فقط — ليس cleartext عامًا. التقرير لا يدعي العكس.
- `GogAuth.kt:16` يحتوي `CLIENT_SECRET` ثابتًا (سر عميل GOG Galaxy العام). ليس تسريبًا حصريًا، لكنه يخالف مبدأ عدم تضمين الأسرار، ويستحق ملاحظة منخفضة الخطورة.

---

## 4. تدقيق DroidDesk

### 4.1 ما تأكد صحته

- المانيفست: `usesCleartextTraffic="true"`، `MANAGE_EXTERNAL_STORAGE`، `REQUEST_INSTALL_PACKAGES`، `POST_NOTIFICATIONS`، `READ/WRITE_EXTERNAL_STORAGE`، `requestLegacyExternalStorage`، `QUERY_ALL_PACKAGES`.
- `minSdk/targetSdk 28` مع تعليق صريح: «Downgraded to 28 to bypass W^X» و«API 28 completely disables the Android 10+ execve() block».
- غياب mount/PID namespaces (proot/chroot) — ما يذكره التقرير صحيح.
- `AppUpdater.kt` يتحقق من اسم الحزمة **وشهادة التوقيع** (SHA-256) مقابل التطبيق المثبت — أي أن ادعاء التقرير بأن updater يتحقق من الشهادة صحيح.
- `libXlorie.so` شيفرة أصلية مسبقة البناء في `jniLibs/arm64-v8a` — يتوافق مع تحفظ التقرير حول الشيفرة المسبقة.
- تعارض llvmpipe مع ادعاء Turnip/Zink — يبقى قائمًا كما وثقه التقرير.

### 4.2 الفجوة المتبقية: توقيع build

`app/android/app/build.gradle.kts`:

- ينشئ `signingConfigs.release` فقط إذا وُجد `releaseStoreFile`.
- `release` buildType يسقط إلى `signingConfigs.getByName("debug")` عند غيابه.
- المفاتيح تُقرأ من `key.properties` أو `DROIDDESK_KEYSTORE`/`DROIDDESK_KEYSTORE_PASSWORD`.

**تحقق فعلي:** APK الرسمي `v1.3.1` موقّع بشهادة `CN=DroidDesk, O=DroidDesk, C=IN`، SHA-256 = `74c93922d73d6c62944c4f69411c081b7b5621882a13d7739652e30e323d8dff`، عبر v2 فقط (لا v3؛ وهذا كافٍ لأن `minSdk 28`). إذن الإصدار الرسمي **ليس** موقّعًا بـdebug.

**الأثر المتبقي:** الخطر في تكوين البناء لا في الإصدار: أي بناء «release» محلي بلا مفتاح ينتج APK بمفتاح debug. يُستحسن أن يفشل build release صراحةً عند غياب `releaseStoreFile` بدل السقوط إلى debug.

### 4.3 سلسلة التوريد

- `fetch_deps.sh:28` ينزّل حزم `.deb` من `packages-cf.termux.dev` عبر HTTPS دون أي checksum أو توقيع قبل فك الضغط والنسخ إلى `jniLibs`.
- `termux-linux-setup.sh` ينزّل خلفية من `wallpapercave.com` دون checksum (أثر تجميلي، لكنه يثبت النمط).
- التقرير يذكر غياب checksum للـrootfs؛ لم نتحقق من مسار rootfs الرسمي في هذه الجولة، لكن النمط في `fetch_deps.sh` يدعم التحفظ.

### 4.4 الأرقام

- حي (2026-10-21): 4,343 نجمة، 403 fork، 84 issue مفتوحًا.
- إصدار `v1.3.1` (2026-10-14): APK بحجم 42,398,714 بايت و3,879 تحميلًا (التقرير: 3,807).
- `v1.0.0` ضمّ `app-release-root.apk` و`app-release.apk`.

---

## 5. مصفوفة الشدة

| # | البند | النوع | الشدة | الإجراء |
|---|---|---|---|---|
| C1 | «لا Wine fork» مع وجود DroidDeck-Proton | خطأ واقعي مركزي | **عالية** | تصحيح المواضع المتعددة + الجدول + قسم الرهان |
| C2 | bwrap shim يعطّل Flatpak/browser sandbox | فجوة أمنية | **عالية** | قسم عزل صريح |
| C3 | ADB/agent/Wireless ADB | فجوة نموذج تهديد | متوسطة–عالية | قسم سطح التحكم |
| C4 | Decky plugins = تنفيذ كود | فجوة نموذج تهديد | متوسطة | توثيق |
| C5 | zRAM/suspend غير مذكورين | فجوة تغطية | منخفضة | إضافة |
| C6 | 281 «C/C++» + أرقام قديمة | دقة | منخفضة | تحديث |
| C7 | v1/v2 بتوقيع testkey على API 26–27 (متحقق) | ثغرة سلسلة تحديث | **عالية** | إسقاط 26–27 أو بناء منفصل |
| C8 | GOG client secret ثابت | ممارسة | منخفضة | ملاحظة |
| D1 | build release يسقط إلى debug | تكوين بناء (الإصدار الرسمي سليم) | منخفضة | فشل صريح عند غياب المفتاح |
| D2 | fetch_deps بلا checksum | سلسلة توريد | متوسطة | توقيع/تحقق |
| D3 | أرقام DroidDesk قديمة | دقة | منخفضة | تحديث |

---

## 6. ما يجب فعله قبل الاعتماد على التقريرين

1. **تصحيح Proton:** إعادة كتابة 5.4/5.5 والقسم التاريخي والجدول والمقارنات؛ ذكر DroidDeck-Proton كمكوّن افتراضي في x86-64 وخيار ARM64.
2. **إضافة قسم عزل:** proot ليس sandbox؛ `droiddeck-bwrap` يترجم إلى binds؛ Flatpak غير معزول؛ browser sandboxes معطّلة.
3. **إضافة سطح التحكم:** agent/DUMP، Wireless ADB، Decky plugins.
4. **معالجة توقيع API 26–27:** القرار المطلوب: إسقاط الدعم، أو بناء قديم منفصل، أو قبول القيد وتوثيقه. لا يُكتفى بوصف v3 وحده؛ يجب ذكر أن v1/v2 يحملان testkey.
5. **تحديث الأرقام** بتاريخ مرجعي واحد، وتصحيح إحصاء 281.
6. **DroidDesk:** جعل غياب مفتاح الإصدار خطأً فادحًا في build release، وإضافة checksums لـ`fetch_deps.sh` وrootfs.
7. **ختم التقريرين** بنطاق واضح: «تحليل ساكن بتاريخ…؛ لا يشمل اختبارًا ديناميكيًا أو تدقيق CVE».

---

## 7. ما لم يتحقق منه

- checksum مسار rootfs الرسمي في DroidDesk (فُحص `fetch_deps.sh` فقط).
- قابلية استغلال سطح agent عمليًا، وسلوك `peer.uid` تحت سيناريوهات ADB.
- أي قياس أداء أو حرارة أو استقرار.
- محتوى `DroidDeck-Components` وسلامة مكوناته.

---

## 8. المراجع الرئيسية

- `droiddeck-architecture.md:37-38, 52, 67-68, 224, 456, 707, 887, 901`
- `droiddesk-architecture.md:15-24` (تغطية الصلاحيات/cleartext/targetSdk/no-namespaces)
- `DroidDeck/app/src/main/java/com/droiddeck/launcher/session/ProtonExtras.kt:29-33`
- `DroidDeck/docs/development/x86-64-steam-client.md:343-378`
- `DroidDeck/docs/releases/0.4.0.md`
- `DroidDeck/docs/development/flatpak.md:28`
- `DroidDeck/tools/linuxfs/overlay/usr/local/bin/droiddeck-bwrap`
- `DroidDeck/tools/linuxfs/overlay/usr/local/bin/droiddeck-flatpak`
- `DroidDeck/app/src/main/java/com/droiddeck/launcher/runtime/BwrapSpawner.kt`
- `DroidDeck/app/src/test/java/com/droiddeck/launcher/runtime/BwrapSpawnerTest.kt`
- `DroidDeck/app/src/main/java/com/droiddeck/launcher/runtime/DeckyManager.kt`
- `DroidDeck/app/src/main/java/com/droiddeck/launcher/stores/StoreAccounts.kt`
- `DroidDeck/app/src/main/res/xml/network_security_config.xml`
- `DroidDeck/app/src/main/java/com/droiddeck/launcher/stores/gog/GogAuth.kt:16`
- `DroidDeck/tools/release/sign-apk.sh` و`keystore/release-signer.sha256`
- `DroidDesk/app/android/app/build.gradle.kts`
- `DroidDesk/app/android/app/src/main/AndroidManifest.xml`
- `DroidDesk/app/android/app/src/main/kotlin/com/orailnoor/droiddesk/runtime/AppUpdater.kt`
- `DroidDesk/fetch_deps.sh:28`
- `https://github.com/Droid-Deck/DroidDeck-Proton`
- `https://github.com/Droid-Deck/DroidDeck-Components`
- `DroidDeck/SHA256SUMS.txt` (إصدار 0.5.0) وشهادات APK المستخرجة
- `DroidDesk-v1.3.1.apk` وشهادته `CN=DroidDesk`
