# تحليل نظام Termux + Ubuntu 22.04  
## خط الأساس، الإضافات، التصنيف الرباعي، والأداء

**تاريخ التحليل:** 2026-10-21  
**النطاق:** الجلسة المحلية الحالية: Termux على Android، وproot Ubuntu 22.04.5، وXFCE، والخدمات، والحزم، والسكربتات، والتعديلات.  
**الحالة:** تحليل وتوثيق فقط — لم يُعدَّل أي ملف نظام، ولم تُثبَّت أو تُحذف أي حزمة.

---

## 0. المنهجية وحدود الثقة

اعتمد التحليل على أدلة مباشرة من النظام نفسه:

- `⁦/var/log/apt/history.log⁩` و`⁦/var/lib/dpkg/status⁩` و`⁦/var/lib/dpkg/info/*.list⁩` لتاريخ الحزم.
- تواريخ `⁦os-release⁩` و`⁦dpkg/status⁩` و`⁦apt/history.log⁩` وملفات `⁦.list⁩`، مع الانتباه إلى أن تواريخ الملفات تتغير مع الترقيات.
- `⁦start-ubuntu22.sh⁩` و`⁦launch-ubuntu.sh⁩` و`⁦ubuntu22-xfce.sh⁩` و`⁦fix-screen.sh⁩` و`⁦/etc/profile.d/proot-fixes.sh⁩` و`⁦/root/.proot-init.sh⁩` و`⁦/etc/supervisor/conf.d/proot-services.conf⁩`.
- فحوص حية: `⁦glxinfo -B⁩`، `⁦pactl info⁩`، `⁦sshd -T⁩`، `⁦supervisorctl status⁩`، `⁦fc-match⁩`، `⁦xrandr⁩`، `⁦setxkbmap⁩`، `⁦df⁩`، `⁦free⁩`، `⁦du⁩`، `⁦dpkg-query⁩`.
- فحص حزم Termux عبر `⁦--admindir=/data/data/com.termux/files/usr/var/lib/dpkg⁩`.

**حدود الثقة:**

- **عالية:** محتوى الملفات، السجلات، الأعداد، الأخطاء الحية.
- **متوسطة:** تحديد "أول تشغيل" وتاريخ بعض التعديلات، لأن تواريخ الملفات لا تساوي دائمًا تاريخ التثبيت.
- **منخفضة/غير مؤكدة:** الغرض الفعلي من بعض الأدوات الاختيارية (مثل `⁦code-server⁩` و`⁦Playwright⁩` و`⁦freebuff⁩`)، وما إذا كان المستخدم يحتاجها أم لا.

**قيد مهم:** `⁦/proc⁩` غير مثبّت كاملًا داخل proot؛ توجد ملفات وهمية محدودة فقط. لذلك `⁦ps⁩` و`⁦pgrep⁩` و`⁦top⁩` و`⁦lsof⁩` غير موثوقة، ولا يمكن حصر العمليات ومستهلكي الذاكرة بالاسم. الاستنتاجات المتعلقة بالذاكرة مبنية على `⁦free⁩` وعلى التطبيقات المعروفة، وليست حصرًا دقيقًا.

---

## 1. الخلاصة التنفيذية

1. **هذا ليس Ubuntu أصليًا كاملًا:** هو rootfs مصغّر Ubuntu 22.04.5 داخل proot على Android. كل ما يتعلق بالواجهة الرسومية، والمتصفح، والخطوط، والصوت، والتطبيقات، والوكلاء، أُضيف بعد التنصيب.
2. **خط الأساس الحقيقي قديم:** سجل APT يبدأ ببناء قاعدة في **2022-02-05**، و`⁦os-release⁩` بتاريخ **2024-09-10**. أول تشغيل/تهيئة للمستخدم يبدأ من **2026-06-07**.
3. **معظم النظام الحالي مضاف:** 1203 حزمة dpkg، منها 201 حزمة manual. لا يصح اعتبار الـ1203 "أصلية"؛ القاعدة المصغرة كانت بلا XFCE/Chromium/Node/Python-pip/Antigravity.
4. **الطبقات الأساسية للإضافات:** 2026-06-07 (تهيئة أولى)، 2026-06-16 (أدوات X والإدخال)، 2026-06-18 (Chromium/صوت/supervisor/SSH/Mesa)، 2026-07-03 (Qt6/ffmpeg/PyQt)، 2026-09-19 (ADB/أدوات سحابية)، 2026-10-12/14 (box64/Vulkan/مدير ملفات)، 2026-10-18 (NodeSource).
5. **أربعة أعطال تشغيلية فورية:**
   - `⁦GALLIUM_DRIVER=virgl⁩` يفرضه `⁦proot-fixes.sh⁩`، بينما `virgl` لا يعمل؛ `llvmpipe` يعمل. النتيجة: GL/تسريع معطّل أو غير مستقر.
   - `⁦GTK_IM_MODULE=ibus⁩` يُفرض من `⁦start-ubuntu22.sh⁩` مع أن `ibus` غير مثبّت. النتيجة: مشاكل إدخال، وخاصة العربية.
   - `⁦PULSE_SERVER=unix:/run/pulse/native⁩` داخل proot، بينما PulseAudio في Termux يعمل على `⁦tcp:127.0.0.1:4713⁩`. النتيجة: الصوت لا يعمل.
   - `sshd` في supervisor بحالة `FATAL`، ولا يوجد مستمع. السجل يظهر `Missing privilege separation directory: /run/sshd` ومحاولة ربط المنفذ 22 رغم أن الإعداد الحالي `Port 10022`.
6. **النظام متأخر أمنيًا:** Chromium المثبت 149.0.7827.114، والمرشح 155.0.8059.79. كما أن `apt` يرى **131 حزمة غير محدّثة** و**163 حزمة قابلة للإزالة التلقائية**.
7. **ضغط موارد حقيقي:** 7.0GiB مستخدمة من 10GiB، و5.0GiB من Swap مستخدمة، والقرص 161G/222G (73%). الأسباب المرجحة: Electron/Chromium، وتطبيقات متعددة، وذاكرة متصفحات، وخدمات غير ضرورية.
8. **تضخم ونسخ مكررة:** `/root/.npm` 1.8G، `/opt/antigravity-ide` 1.7G، `/opt/antigravity` 650M، `/opt/freebuff` 416M، خطوط Noto 758M، ذاكرة Chromium 480M، Playwright 405M، `.nvm` 540M، `.bun` 286M، وapt cache 450M.
9. **الإضافات الإلزامية الحقيقية محدودة:** proot + Termux-X11 + glibc + XFCE + Mesa/llvmpipe + خطوط عربية + Chromium + أدوات الإدخال/الحافظة + PulseAudio + سكربتات الإقلاع. الباقي قابل للتصنيف إلى متوسط/اختياري.
10. **لا حاجة لإعادة التثبيت من الصفر الآن:** يمكن إصلاح الأعطال الأربعة، تقليم النسخ المكررة، وتحديث Chromium، ثم إعادة تقييم. إعادة البناء تبقى خيارًا إذا أردت نظامًا نظيفًا وقابلًا للصيانة.

---

## 2. الطوبولوجيا ومسار الإقلاع

```
Android 14 — kernel 6.1.128-android14-11-31999054-abS928BXXS5CZD1 (aarch64)
└── Termux (uid 10482)
    ├── proot 5.1.107.92
    │   └── Ubuntu 22.04.5 (fake root, uid=0 داخل proot)
    │       ├── XFCE على Termux-X11 :1 — 1280x2260
    │       ├── Chromium 149 / Electron apps / opencode / code-server
    │       ├── supervisor
    │       │   ├── cron — RUNNING
    │       │   ├── sshd — FATAL
    │       │   └── avahi — STOPPED
    │       └── Mesa/llvmpipe (بعد إصلاح virgl)
    ├── PulseAudio — TCP 4713 (يعمل)
    └── ADB / Termux API
```

### 2.1 اللانشر `start-ubuntu22.sh`

اللانشر هو نقطة الدخول، ويحتوي قرارات مؤثرة:

- `proot` بالخيارات: `--kill-on-exit --link2symlink --sysvipc -0`.
- `-0` يعطي **root وهميًا** داخل proot، وليس صلاحيات Android حقيقية.
- `-b /dev` و`-b /proc` و`-b /sys` و`-b /data` و`-b /sdcard` (إن وُجد).
- ربط `/dev/shm` إلى `ubuntu22-fs/tmp` وليس tmpfs حقيقيًا.
- ربط سوكيت X11 من Termux، و`/etc/resolv.conf`.
- ملفات وهمية لـ`/proc/stat` و`/proc/vmstat` و`/proc/version`.
- بيئة `env -i` تتضمن:
  - `GALLIUM_DRIVER=llvmpipe` — السطر 52.
  - `DISPLAY=:1` — السطر 53.
  - `PULSE_SERVER=127.0.0.1` — السطر 54 (صيغة ناقصة).
  - `GTK_IM_MODULE=ibus` — السطر 55.
  - `MOZ_FAKE_NO_SANDBOX=1` و`ELECTRON_DISABLE_SANDBOX=1`.

**ملاحظة أمنية:** ربط `/data` كاملًا يمنح جلسة proot رؤية بيانات تطبيقات Android الأخرى. هذا مفيد للتكامل، لكنه يوسّع سطح الهجوم إذا شُغّل كود غير موثوق داخل الجلسة.

### 2.2 `proot-fixes.sh` يعارض اللانشر

الملف `⁦/etc/profile.d/proot-fixes.sh⁩` يُنفَّذ عند الدخول ويغيّر ما وضعه اللانشر:

| المتغير | اللانشر | `proot-fixes.sh` | القيمة الحالية | النتيجة |
|---|---|---|---|---|
| `GALLIUM_DRIVER` | `llvmpipe` (سطر 52) | `virgl` (سطر 4) | `virgl` | فشل GL/التسريع |
| `PULSE_SERVER` | `127.0.0.1` (سطر 54) | `127.0.0.1` ثم `unix:/run/pulse/native` (سطر 14) | `unix:/run/pulse/native` | لا صوت |
| `GTK_IM_MODULE` | `ibus` (سطر 55) | لا يُصدَّر (تعليق فقط) | `ibus` | إدخال معطوب |

هذا هو أهم اكتشاف في التحليل: **اللانشر يحاول الإعداد الصحيح، ثم يلغيه profile**. أي إصلاح يجب أن يعالج الملفين معًا، لا ملفًا واحدًا.

### 2.3 الخدمات

- `supervisor` هو مدير الخدمات الفعلي، لأن `systemd` ليس PID 1.
- `cron`: يعمل منذ ساعتين تقريبًا، لكن لا توجد أدلة على مهام cron مطلوبة.
- `sshd`: `FATAL`، لا مستمع.
- `avahi`: `STOPPED`، غير مطلوب في الغالب.
- `systemd` و`systemd-sysv` مثبتان لكنهما لا يعملان كمدير نظام؛ وجودهما لا يضيف وظيفة فعلية.

---

## 3. خط الأساس: ما كان أصليًا؟

### 3.1 القاعدة

الأدلة:

- `⁦/etc/os-release⁩`: Ubuntu 22.04.5 LTS (Jammy)، بتاريخ **2024-09-10**.
- `⁦/var/log/apt/history.log⁩`: أول عملية بناء قاعدة في **2022-02-05**، وهي سلسلة `apt-get install --reinstall` لأساسيات النظام.
- لا توجد في سجل 2022 أي حزم XFCE أو Chromium أو Node أو Python-pip أو Antigravity.
- `⁦/var/lib/dpkg/status⁩` و`⁦apt/history.log⁩` آخر تعديل: **2026-10-18 11:14**.
- مجلد rootfs `⁦ubuntu22-fs⁩` بتاريخ **2026-08-09** — تاريخ تعديل، وليس تاريخ الإنشاء.

**الخلاصة:** الأصل هو Ubuntu 22.04.5 مصغّر بلا واجهة رسومية. كل ما يلي كان إضافة:

- XFCE وTermux-X11 وXvfb/TigerVNC.
- Chromium والمتصفحات.
- الخطوط العربية وNoto.
- PulseAudio وsupervisor وSSH.
- Python-pip و151 حزمة pip.
- Node/nvm وnpm globals.
- code-server وAntigravity وfreebuff وopencode.
- ADB وbox64 وVulkan ومدير الملفات.

### 3.2 لماذا 1203 حزمة ليست أصلية؟

- 1203 هو إجمالي الحزم الحالية، وليس حجم القاعدة.
- 201 حزمة manual فقط؛ الباقي تبعيات.
- 12 ملف `⁦.list⁩` فقط بتاريخ 2022، و1191 بتاريخ 2026.
- تواريخ `⁦.list⁩` لا تثبت التثبيت؛ لأن `apt upgrade` يعيد كتابتها. السجل الموثوق هو أسطر `⁦Install:⁩` في `⁦history.log⁩`.
- 102 حزمة manual غير موجودة في سجل APT؛ بعضها قاعدة، وبعضها ثُبّت بسكربتات خارج APT (مثل `code-server`).

### 3.3 أول تشغيل مقابل الإضافات

| المرحلة | التاريخ | ما حدث | التصنيف |
|---|---|---|---|
| بناء القاعدة | 2022-02-05 | Ubuntu مصغّر، أدوات APT/NSS/OpenSSL... | أصلي |
| أول تهيئة | 2026-06-07 | `sudo`، `wget`، `udisks2`، `keyboard-configuration`، `tzdata`، XFCE، TigerVNC، `dbus-x11`، `software-properties-common`، `x11-apps`، خطوط، Chromium، Python/Node، ImageMagick | إضافي تأسيسي |
| تثبيت X/الإدخال | 2026-06-16 | `xvfb`، `xdotool`، `wmctrl`، `xclip`، مكتبات X11 التطويرية، `xfonts-*` | إضافي ملزم/متوسط |
| الشبكة/الصوت/الخدمات | 2026-06-18 | `chromium`، `pulseaudio`، `supervisor`، `openssh-server`، `mesa-*`، `locales`، `cron`، `systemd`، `udev`، `net-tools` | إضافي ملزم/متوسط |
| Qt/وسائط | 2026-07-03 | 92 حزمة: Qt6، ffmpeg libs، PyQt6، OCR/وسائط | متوسط |
| التطبيقات | 2026-07-04 | code-server، Antigravity، إضافات Electron | متوسط/اختياري |
| Android/سحابة | 2026-09-19 | ADB، cloudflared، tailscale، أدوات الوكلاء | متوسط/اختياري |
| تكامل سطح المكتب | 2026-10-12/14 | box64، Vulkan، gvfs، tumbler، pcmanfm، pyatspi، 7zip | متوسط/اختياري |
| NodeSource | 2026-10-18 | `apt-transport-https`، Node 22 من NodeSource | متوسط/مكرر |

**ملاحظة:** في Termux، 136 حزمة تغيّر تاريخها في **2026-09-19** بسبب `full-upgrade`، وليست 136 إضافة جديدة. الإضافات الصريحة في Termux أقل بكثير: `proot`, `termux-x11-nightly`, `x11-repo`, `glibc`, `pulseaudio`, `termux-api`, `android-tools`, `git`, `gh`, `tmux`, `fribidi`, `expect`, `llama-cpp`, `zbar`, `dbus`, `libqrencode` وغيرها.

---

## 4. ما أُضيف لتحسين النظام — حسب الوظيفة

| المجال | ما أُضيف | القيمة | الحالة الحالية | التصنيف |
|---|---|---|---|---|
| العرض | Termux-X11، XFCE، Xvfb، TigerVNC | واجهة رسومية كاملة | Termux-X11 يعمل؛ Xvfb/TigerVNC غير مستخدمين | Termux-X11 ملزم؛ الباقي اختياري |
| الإدخال | `xdotool`, `wmctrl`, `xclip`, `autocutsel` | أتمتة/حافظة/تحكم نوافذ | مثبتة | ملزم/متوسط |
| الإدخال العربي | `GTK_IM_MODULE=ibus` | محاولة دعم IME | ibus غير مثبت | **معطوب** |
| الصوت | PulseAudio في Termux، عميل في Ubuntu | صوت للتطبيقات | Termux TCP يعمل؛ proot لا يتصل | **معطوب** |
| المتصفح | Chromium من xtradeb + wrapper `--no-sandbox` | متصفح كامل | 149 قديم، 155 متاح | ملزم + تحديث أمني |
| الخطوط | Noto (758M)، Arabeyes، Amiri | دعم عربي واسع | 136 خطًا عربيًا؛ الافتراضي DejaVu | ملزم؛ التقليم متوسط |
| الملفات | Thunar (XFCE) + pcmanfm + gvfs + tumbler | مدير ملفات ومعاينات | مديران | متوسط/مكرر |
| الأرشيف | `7zip` + `p7zip` + `p7zip-full` + `p7zip-rar` | فك/ضغط | أربع حزم متداخلة | مكرر |
| x86 | box64 | تشغيل تطبيقات x86 | مثبت | اختياري |
| Android | adb، termux-api | تكامل مع الجهاز | مثبت | متوسط/ملزم حسب الاستخدام |
| التطوير | Python 3.10 + 151 pip، Node 20 nvm + Node 22 apt، code-server | تشغيل الأدوات | نسختا Node | متوسط/مكرر |
| الوكلاء | opencode، agent_mesh، skills hub، Composio، cloud tools | بيئة الوكلاء | تعمل | ملزم لسير عملك |
| السحابة | tailscale، cloudflared، kaggle/hf CLIs | اتصال/حوسبة | مثبتة، غير مشغّلة | اختياري |
| سطح المكتب | XFCE، أيقونات، خلفيات، لوحة | تجربة استخدام | يعمل | ملزم |
| الأتمتة | supervisor، cron | إدارة خدمات/مهام | supervisor يعمل؛ cron بلا مهام | supervisor ملزم؛ cron اختياري |
| SSH | openssh-server | وصول عن بعد | FATAL | اختياري/معطوب |

---

## 5. إحصاءات الحزم

| المقياس | القيمة | المصدر |
|---|---:|---|
| حزم Ubuntu المثبتة | 1203 | `dpkg-query` |
| حزم Ubuntu manual | 201 | `apt-mark showmanual` |
| حزم قابلة لـautoremove | 163 | `apt-get -s autoremove` |
| حزم غير محدّثة | 131 | `apt` |
| حزم Termux | ~271 | dpkg Termux |
| حزم pip | 151 | `pip3 list` |
| حزم npm global | 5 | `npm ls -g` |
| خطوط عربية | 136 | `fc-list :lang=ar` |
| حزم Qt6 من APT | 35 | `dpkg -l` |

**قراءة الأرقام:**

- 163 حزمة قابلة للإزالة ليست كلها "زوائد"؛ بعضها تبعيات Node قديمة وsnapd. يجب مراجعة القائمة قبل `autoremove`.
- 131 حزمة غير محدّثة تعني نظامًا متأخرًا، لا سيما Chromium.
- 151 حزمة pip قد تكون طبيعية لمشروع Python، لكنها تحتاج تدقيقًا: بعضها قد لا يُستخدم.

---

## 6. التصنيف الرباعي

### 6.1 أصلي (Base)

**تعريف:** ما كان جزءًا من صورة Ubuntu المصغرة قبل تهيئة 2026-06-07، أو ما يلزم لتشغيل APT/NSS/الأساس.

أمثلة مؤكدة من سجل 2022:

- `apt`, `dpkg`, `bash`, `coreutils`, `libc6`, `libssl3`, `openssl`, `ca-certificates`.
- `adduser`, `base-files`, `base-passwd`, `debconf`, `dpkg`, `gcc-12-base`, `libgcc-s1`, `libstdc++6`.
- `login`, `passwd`, `mount`, `util-linux`, `procps` (جزئيًا)، `sed`, `grep`, `findutils`.
- `tzdata`, `locales` (لكن `locales` أُعيد تثبيتها/تحديثها لاحقًا).

**ليست أصلية:** XFCE، Chromium، PulseAudio، supervisor، SSH، Node، Python-pip، ADB، box64، Vulkan، code-server، Antigravity، freebuff، opencode، الخطوط العربية، أدوات X.

### 6.2 إضافي ملزم ضروري للتحسين

**تعريف:** بدونه النظام الحالي لا يؤدي وظيفته الأساسية (سطح مكتب، متصفح، صوت، إدخال، وكلاء).

#### Termux host

| الحزمة/الأداة | الإصدار | لماذا ملزمة | ملاحظة |
|---|---|---|---|
| `proot` | 5.1.107.92 | تشغيل rootfs | أساسي |
| `termux-x11-nightly` | 1.03.01-6 | عرض XFCE | أساسي |
| `x11-repo` | 8.4-1 | مصدر Termux-X11 | أساسي |
| `glibc` | 2.44 | توافق glibc | أساسي لبعض الحزم |
| `pulseaudio` | 17.0-4 | صوت التطبيقات | يعمل TCP 4713 |
| `termux-api` | 0.60.0 | تكامل Android | متوسط/ملزم حسب الاستخدام |
| `adb`/`android-tools` | — | تكامل الجهاز | متوسط |
| `git`, `tmux`, `wget`, `curl`, `tar` | — | تطوير/إقلاع | أساسي |
| `fribidi` | — | تشكيل النص العربي | أساسي للعربية |
| `dbus` | — | تكامل جلسة | متوسط |
| `openssh` (Termux) | — | اتصال | اختياري |

#### Ubuntu

| الحزمة/الأداة | لماذا ملزمة | ملاحظة |
|---|---|---|
| `xfce4` | سطح المكتب | أساسي |
| `dbus-x11` | جلسة XFCE | أساسي |
| `x11-utils` | تشخيص X | أساسي/تشخيص |
| `libgl1-mesa-dri` + `libglx-mesa0` + `libegl-mesa0` | GL/EGL | أساسي؛ يجب استخدام llvmpipe |
| `mesa-utils` | `glxinfo` | تشخيص |
| `fonts-dejavu-core` + خط عربي (Noto/Amiri/Arabeyes) | عرض عربي | أساسي |
| `chromium` + `chromium-wrapper` | متصفح | أساسي؛ يجب تحديثه |
| `pulseaudio`/`libpulse` | عميل الصوت | أساسي |
| `xdotool`, `wmctrl`, `xclip`, `autocutsel` | إدخال/حافظة | أساسي لسير العمل |
| `supervisor` | إدارة الخدمات | أساسي لأن systemd غير فعّال |
| `sudo`, `wget`, `curl`, `ca-certificates` | إدارة/شبكة | أساسي |
| `python3`, `python3-pip`, `python3-venv` | أدوات الوكلاء | أساسي |
| Node واحد (nvm v20 أو apt v22) | تشغيل الأدوات | أساسي، لكن ليس الاثنان |
| `openssh-server` | SSH | اختياري حسب الحاجة |
| `tesseract-ocr` | OCR | متوسط؛ مثبت |
| `imagemagick` | تحويل صور | متوسط؛ مثبت |
| `7zip` | أرشيف | متوسط؛ واحد يكفي |

#### السكربتات الملزمة

- `⁦start-ubuntu22.sh⁩`: الإقلاع والربط.
- `⁦launch-ubuntu.sh⁩`: تشغيل Termux-X11/PulseAudio قبل proot.
- `⁦/proot-fixes.sh⁩`: إصلاحات الجلسة — لكنه حاليًا سبب عطلين.
- `⁦/root/.proot-init.sh⁩`: تشغيل supervisor والخدمات.
- `⁦/usr/local/bin/chromium-wrapper⁩` و`⁦electron-wrapper⁩`: no-sandbox.
- `⁦/usr/local/bin/antigravity-ide-launch⁩` و`handler`: تشغيل Antigravity.
- `⁦agent_mesh.sh⁩` وPATH في `.bashrc`: بيئة الوكلاء.

### 6.3 متوسط الأهمية

**تعريف:** يضيف قيمة واضحة، لكن النظام يستطيع الإقلاع والعمل بدونه.

| العنصر | القيمة | لماذا متوسط | قرار مقترح |
|---|---|---|---|
| `xvfb` | عرض افتراضي/اختبارات | غير مستخدم حاليًا | أبقِه إن كنت تستخدم headless؛ وإلا احذفه |
| `tigervnc-standalone-server` + common + tools | VNC بديل | Termux-X11 هو المستخدم | احذفه إن لم تستخدم VNC |
| `x11-apps` | أدوات X تجريبية | تشخيص فقط | اختياري |
| `pcmanfm` + `gvfs` + `tumbler` | مدير ملفات ومعاينات | Thunar موجود | احذف المدير المكرر |
| `box64` | تشغيل x86 | لا دليل على استخدام | احذفه إن لم تحتجه |
| `mesa-vulkan-drivers` + `libvulkan1` | Vulkan | `/dev/dri` غير متاح | يمكن إبقاؤه منخفض الأولوية |
| `qt6` (35 حزمة) + `PyQt6` | تطبيقات Qt | لا يظهر تطبيق Qt رئيسي | راجع إن كان هناك تطبيق يستخدمه |
| `code-server` | VS Code في المتصفح | غير مشغّل حاليًا | متوسط/اختياري |
| `Playwright` (405M) | أتمتة متصفح | غير مستخدم حاليًا | اختياري |
| `freebuff` (416M) | أداة/تطبيق | غير مؤكد الاستخدام | راجع |
| `cron` | مهام دورية | لا توجد مهام | أوقفه إن لم تحتجه |
| `udisks2` (مثبت ومحجوز) | تخزين قابل للإزالة | لا systemd؛ سبب الحجز غير مؤكد | راجع الحجز |
| `systemd`/`systemd-sysv` | مدير نظام | غير فعّال | يمكن إزالته بحذر |
| `tailscale`/`cloudflared` | اتصال سحابي | غير مشغّل | اختياري |
| `ffmpeg` | فيديو | **غير مثبت** (المكتبات فقط) | أضفه إن كنت تحتاجه |

### 6.4 اختياري يمكن تجاهله

**تعريف المستخدم:** إذا أعدنا تثبيت النظام من الصفر، سيعمل بدون مشاكل أو نقص دونه.

| العنصر | لماذا يمكن تجاهله | الشرط |
|---|---|---|
| `tigervnc-*` | Termux-X11 يكفي | إن لم تستخدم VNC |
| `xvfb` | لا استخدام headless | إن لم تستخدم اختبارات headless |
| `x11-apps` | أدوات تشخيص | لا تؤثر على العمل |
| `pcmanfm`/`gvfs`/`tumbler` | Thunar يكفي | إن لم تحتج معاينات/مواقع |
| `p7zip`, `p7zip-full`, `p7zip-rar` | `7zip` يغطي الاحتياج | إن لم تحتج صيغ p7zip الخاصة |
| `fonts-noto-cjk` (داخل Noto) | 300M+ | إن لم تحتج الصينية/اليابانية/الكورية |
| `snapd`, `apparmor`, `squashfs-tools` | بقايا Chromium snap | إن لم تستخدم snap |
| `nodejs` من APT (22) | nvm v20 يكفي | إن لم يعتمد سكربت على `/usr/bin/node` |
| `code-server` | غير مشغّل | إن لم تستخدمه |
| `Playwright` browsers | 405M | إن لم تستخدم أتمتة المتصفح |
| `freebuff` | غير مؤكد | إن لم تستخدمه |
| `Antigravity` القديم في `/opt/antigravity` | نسخة أحدث في `/opt/antigravity-ide` | بعد التأكد من عدم استخدام القديمة |
| `tailscale`/`cloudflared` | غير مشغّلين | إن لم تحتج اتصالًا سحابيًا |
| `cron` | لا مهام | إن لم تحتج مهامًا دورية |
| `avahi` | موقوف | إن لم تحتج اكتشاف الشبكة |
| `udisks2` | محجوز | إن لم تحتج تخزينًا قابلًا للإزالة |
| `systemd` | لا PID 1 | إن لم تحتج وحدات systemd |
| `qt6`/`PyQt6` | لا تطبيق Qt رئيسي | إن لم تحتج PyQt |
| `box64` | لا تطبيقات x86 | إن لم تحتجها |
| `vulkan` | `/dev/dri` غير متاح | إن لم تحتج Vulkan |
| `xinput` (غير مثبت) | أداة تشخيص | لا تحتاجه |

**لا يمكن تجاهله:** proot، Termux-X11، glibc، XFCE، Mesa/llvmpipe، خط عربي، Chromium، PulseAudio، أدوات الإدخال/الحافظة، supervisor، سكربتات الإقلاع، Node واحد، Python، وبيئة الوكلاء إن كنت تستخدم opencode.

---

## 7. الأعطال الحالية وأولويات الإصلاح

### P0-1 — `virgl` يعطّل GL

**الدليل:**

- `printenv GALLIUM_DRIVER` → `virgl`.
- `⁦/etc/profile.d/proot-fixes.sh:4⁩` → `export GALLIUM_DRIVER=virgl`.
- `start-ubuntu22.sh:52` → `GALLIUM_DRIVER=llvmpipe`.
- `glxinfo -B` مع `virgl` يفشل.
- `GALLIUM_DRIVER=llvmpipe glxinfo -B` → `llvmpipe (LLVM 15.0.7)` و`OpenGL 4.5`.
- `/dev/dri` يحتوي `card0` و`renderD128` لكنهما غير قابلين للوصول (`c?????????`).

**الأثر:** تعطيل تسريع GL، فشل تطبيقات/متصفحات، اعتماد على مسار برمجي غير مستقر.

**الإصلاح المقترح (لا يُنفَّذ الآن):**

- اجعل `⁦proot-fixes.sh:4⁩` يساوي `llvmpipe`، أو احذف التصدير ليورث قيمة اللانشر.
- تحقق: `glxinfo -B | grep 'OpenGL renderer'`.
- لا تستخدم `LIBGL_ALWAYS_SOFTWARE=1` وحده؛ الاختبار أظهر فشل `drisw`، بينما `GALLIUM_DRIVER=llvmpipe` نجح.

### P0-2 — `GTK_IM_MODULE=ibus` بلا IBus

**الدليل:**

- `printenv GTK_IM_MODULE` → `ibus`.
- `start-ubuntu22.sh:55` → `GTK_IM_MODULE=ibus`.
- `ibus-daemon` و`fcitx5` غير مثبتين.
- `proot-fixes.sh` يحتوي تعليقًا يقول إن ibus غير مثبت، لكنه لا يزيل المتغير.
- `setxkbmap -query` → `layout: us` فقط.

**الأثر:** مشاكل إدخال، وخاصة العربية، وفقدان أحرف في تطبيقات GTK.

**الإصلاح المقترح:**

- احذف `GTK_IM_MODULE=ibus` من `start-ubuntu22.sh:55`، وأضف `unset GTK_IM_MODULE` في `proot-fixes.sh`.
- إن أردت IME فعليًا: ثبّت `ibus` + `ibus-m17n` أو `fcitx5` + دعم العربية، ثم اضبطه.
- بديل خفيف: استخدم XKB: `setxkbmap -layout us,ar -option grp:alt_shift_toggle`، وثبّته في إعدادات لوحة XFCE.

### P0-3 — PulseAudio لا يعمل داخل proot

**الدليل:**

- `pactl info` داخل proot → `Connection refused`.
- `PULSE_SERVER` الحالي → `unix:/run/pulse/native`.
- `PULSE_SERVER=tcp:127.0.0.1:4713 pactl info` → نجح، ويعرض `Server Protocol Version: 35`.
- Termux PulseAudio يعمل، ويوجد مجلد سوكيت في `/data/data/com.termux/files/usr/tmp/pulse-ASwViSrjuqfz`.
- `proot-fixes.sh:6` و`:14` يغيران العنوان مرتين، وآخر قيمة هي العنوان الخاطئ.

**الأثر:** لا صوت في التطبيقات.

**الإصلاح المقترح:**

- في `start-ubuntu22.sh:54` وفي `proot-fixes.sh:14`: `PULSE_SERVER=tcp:127.0.0.1:4713`.
- تأكد من تشغيل Termux: `pulseaudio --start --exit-idle-time=-1`.
- تحقق: `pactl info`.

### P0-4 — `sshd` لا يعمل

**الدليل:**

- `supervisorctl status` → `sshd FATAL Exited too quickly`.
- `netstat` (مع قيود `/proc`) → لا مستمع.
- `supervisorctl tail sshd stderr` → `Missing privilege separation directory: /run/sshd`، ثم `Bind to port 22 ... Permission denied`، ثم `Cannot bind any address`.
- `sshd -T -p 10022` → `port 10022`، `permitrootlogin yes`، `usepam yes`.
- `⁦/etc/ssh/sshd_config:14⁩` → `Port 10022`.
- `/run/sshd` موجود الآن، لكن السجل يقول إنه كان مفقودًا عند بدء الخدمة.
- `sshd_config` يحتوي `UsePAM` مرتين (سطر 85 و124)، و`PermitRootLogin` فعّال.

**الأثر:** لا SSH، وخدمة FATAL تلوث سجل supervisor.

**الإصلاح المقترح:**

- أنشئ `/run/sshd` قبل تشغيل sshd، أو استخدم:
  `command=/bin/sh -c 'mkdir -p /run/sshd && exec /usr/sbin/sshd -D -p 10022'`
- أزل أي تشغيل يدوي/مكرر لـ`sshd` بدون `-p` (السجل يشير إلى محاولة المنفذ 22).
- أصلح تكرار `UsePAM`/`PermitRootLogin`، وفضّل المفاتيح على كلمة المرور.
- أعد تشغيل supervisor وتحقق: `sshd -T` ثم `ss -ltn` أو `nc`.
- إن لم تكن تحتاج SSH، عطّل برنامج sshd في supervisor بدلًا من إصلاحه.

### P1-1 — Chromium قديم و131 حزمة غير محدّثة

**الدليل:**

- `chromium --version` → `Chromium 149.0.7827.114`.
- `apt-cache policy chromium` → المرشح `155.0.8059.79`.
- `apt` → 131 غير محدّثة، 163 قابلة للإزالة.

**الأثر:** ثغرات معروفة، وتوافق أضعف مع المواقع.

**الإصلاح المقترح:**

- راجع قائمة التحديثات، ثم `apt update && apt full-upgrade` بعد نسخة احتياطية.
- حدّث Chromium على الأقل.
- راجع 163 autoremove يدويًا قبل التنفيذ؛ لا تشغّل `autoremove` أعمى.

### P1-2 — الخط العربي الافتراضي ضعيف

**الدليل:**

- `fc-match 'sans-serif:lang=ar'` → `DejaVu Sans`.
- 136 خطًا عربيًا متاحًا، لكن الافتراضي ليس Noto/Amiri.
- Noto وحدها 758M (truetype 459M + opentype 299M).
- Arabeyes 5.3M، Amiri 2.5M.

**الأثر:** مظهر عربي أقل جودة، وارتفاع استهلاك الذاكرة/القرص بسبب Noto الكاملة.

**الإصلاح المقترح:**

- أضف alias في fontconfig يفضّل `Noto Naskh Arabic` أو `Amiri` للعربية.
- احتفظ بـ`fonts-noto-core`/العربية، واحذف CJK إن لم تحتجها.
- تحقق: `fc-match 'sans-serif:lang=ar'`.

### P1-3 — الذاكرة وSwap

**القياس:**

- RAM: 10GiB، مستخدم 7.0GiB، حر 347MiB، متاح 3.2GiB.
- Swap: 8GiB، مستخدم 5.0GiB.
- القرص: 161G/222G (73%)، متاح 62G.

**الأثر:** بطء، تقطيع، وإعادة تحميل تطبيقات.

**المسار المقترح:**

- أغلق تطبيقات Electron/Chromium غير المستخدمة.
- قلّل عدد عمليات Chromium: `--renderer-process-limit=2`، وحدّد cache.
- لا تشغّل `code-server`/Playwright/Antigravity القديم/Freebuff معًا إن لم تحتجها.
- أوقف `cron` و`avahi` إن لم تحتجهما.
- استخدم llvmpipe بدل virgl لتقليل فشل المسار الرسومي.
- خفّض دقة العرض من 1280x2260 إلى 1080x1908 أو 720p عند الحاجة.
- compositing في XFCE معطّل بالفعل (`false`) — جيد.

### P1-4 — ازدواج Node

**الدليل:**

- `command -v node` → `/root/.nvm/versions/node/v20.20.2/bin/node`.
- `/usr/bin/node --version` → `v22.23.3`.
- `nodejs` من NodeSource 22.23.3، و`npm` APT 8.5.1.
- npm globals موجودة تحت nvm: `composio-core`, `freebuff`, `wrangler`.
- `code-server` يستخدم Node الخاص به غالبًا.

**الأثر:** مساران، تبعيات مكررة، و163 حزمة Node قديمة قابلة للإزالة.

**الإصلاح المقترح:**

- اختر Node واحدًا. بما أن PATH يستخدم nvm v20، الأرجح إزالة `nodejs`/`npm` من APT إن لم يعتمد شيء على `/usr/bin/node`.
- راجع `/usr/bin/node` قبل الحذف: `grep -R "/usr/bin/node" /usr/local/bin /etc/systemd /root/.config 2>/dev/null`.
- نظّف `npm cache` و`.npm` (1.8G).

### P1-5 — سطح الأمان

- proot ليس حدًا أمنيًا؛ `-0` وهمي، و`-b /data` يوسّع الوصول.
- `--no-sandbox` في Chromium/Electron؛ متوقع في proot لكنه يقلل العزل.
- SSH: `PermitRootLogin yes` و`PasswordAuthentication yes`؛ يجب تقييدهما إن أُصلح.
- `code-server` إن شغّلته: اربطه بـ127.0.0.1 وكلمة مرور/جلسة.
- `tailscale`/`cloudflared` مثبتان؛ لا تشغّلهما دون حاجة.
- `.env.tri_layer` قد يحتوي أسرارًا؛ تأكد من `chmod 600` وعدم طباعته.
- مصادر خارجية: xtradeb PPA، NodeSource، box64؛ ثبّت الثقة وراجع المفاتيح.
- سكربت تنزيل rootfs يستخدم `curl` دون checksum؛ أضف SHA256 وتحقق.

---

## 8. الأداء والقرص

### 8.1 أين يذهب القرص

| المسار | الحجم | التصنيف | ملاحظة |
|---|---:|---|---|
| `/root/.npm` | 1.8G | قابل للتقليم | cache/npm |
| `/opt/antigravity-ide` | 1.7G | متوسط | النسخة الأحدث |
| `/root/.local/share` | 1.1G | يحتاج تدقيقًا | بيانات أدوات |
| `/root/.cache` | 1.1G | قابل للتقليم | Chromium 480M، Playwright 405M، code-server 147M |
| `/usr/share/fonts` | 798M | قابل للتقليم | Noto 758M |
| `/opt/antigravity` | 650M | اختياري | نسخة قديمة محتملة |
| `/root/.nvm` | 540M | قابل للتقليم | Node واحد فقط |
| `/opt/freebuff` | 416M | اختياري | راجع الاستخدام |
| `/root/.bun` | 286M | قابل للتقليم | cache |
| `/var/cache/apt` | 249M | آمن | `apt clean` |
| `/root/.opencode` | 239M | ملزم | opencode |
| `/root/.local/bin` | 206M | ملزم | agy/uv/vibe |
| `/var/lib/apt/lists` | 201M | آمن نسبيًا | `apt clean` |
| `/root/Desktop/deck` | 176K | أعمال | تقارير |

**إمكان التقليم التقريبي:** 5–7GB دون المساس بالوظائف الأساسية، بشرط مراجعة الاستخدام. القرص به 62G متاحة، لذا التقليم ليس طارئًا، لكنه يحسّن الصيانة والنسخ الاحتياطي.

### 8.2 الذاكرة

لا يمكن حصر العمليات بسبب `/proc`. لكن المؤشرات:

- 7.0GiB مستخدمة و5.0GiB Swap مستخدمة → ضغط واضح.
- التطبيقات المرجحة: Chromium، Electron (Antigravity/Freebuff/opencode)، XFCE، وربما code-server/Playwright.
- الحل ليس "زيادة Swap" فقط؛ الأفضل تقليل التطبيقات المتزامنة وتحديث Chromium وتقليم الخدمات.

### 8.3 الإقلاع والخدمات

- `start-ubuntu22.sh` يربط `/data` و`/sdcard` ويشغّل proot.
- `proot-fixes.sh` يغيّر المتغيرات (ويسبب عطلين).
- `.proot-init.sh` يشغّل supervisor وsshd/PulseAudio/autocutsel.
- `cron` يعمل بلا مهام → يمكن إيقافه.
- `sshd` FATAL → يضيف محاولات فاشلة عند كل إقلاع.
- `avahi` موقوف → لا يؤثر.

---

## 9. خطة إعادة البناء من الصفر (إن أردت)

### 9.1 الحد الأدنى الذي يعمل

**Termux:**

- `proot`, `termux-x11-nightly`, `x11-repo`, `glibc`, `pulseaudio`, `termux-api`, `git`, `tmux`, `wget`, `curl`, `fribidi`, `dbus`.
- `android-tools` إن كنت تحتاج ADB.

**Ubuntu:**

- `sudo`, `wget`, `curl`, `ca-certificates`, `locales`.
- `xfce4`, `dbus-x11`, `x11-utils`, `x11-xserver-utils`.
- `libgl1-mesa-dri`, `libglx-mesa0`, `libegl-mesa0`, `mesa-utils`.
- خط عربي واحد جيد + DejaVu.
- `chromium` (أحدث إصدار) + wrapper.
- `pulseaudio`/`libpulse` + `pulseaudio-utils`.
- `xdotool`, `wmctrl`, `xclip`, `autocutsel`.
- `supervisor` (لأن systemd غير فعّال).
- Python + pip، وNode واحد.
- `openssh-server` فقط إن كنت تحتاج SSH.
- `tesseract-ocr`, `imagemagick`, `7zip` حسب الحاجة.

**التطبيقات:**

- opencode (أساسي لسير عملك).
- Antigravity نسخة واحدة.
- code-server/Playwright/Freebuff فقط عند الحاجة.

### 9.2 ما لا تثبته افتراضيًا

- Node الثاني (nvm + APT معًا).
- TigerVNC + Xvfb + Termux-X11 معًا (اختر مسار عرض واحدًا أساسيًا).
- Thunar + PCManFM معًا.
- `7zip` + `p7zip` + `p7zip-full` + `p7zip-rar` معًا.
- `fonts-noto` الكاملة إن كنت لا تحتاج CJK.
- `snapd`/`apparmor`/`squashfs-tools` إن لم تستخدم snap.
- `systemd`/`systemd-sysv` (لا PID 1).
- حزم تطوير X11/Qt إن لم تكن تبني شيئًا.
- Vulkan/box64 إن لم يكن لديك تطبيقات تحتاجهما.
- نسختا Antigravity، أو Freebuff مع npm global.

### 9.3 ترتيب الإصلاح الذكي

1. **P0:** أصلح `virgl` و`GTK_IM_MODULE` و`PULSE_SERVER` و`sshd` (أو عطّل sshd).
2. **P1:** حدّث Chromium و131 حزمة بعد نسخة احتياطية.
3. **تنظيف:** راجع 163 autoremove، ونظّف `.npm` وapt cache وChromium cache.
4. **تقليم:** قرّر في Playwright/code-server/Freebuff/Antigravity القديم/CJK/box64/Qt.
5. **تحسين:** alias الخط العربي، لوحة مفاتيح عربية، تقليل دقة العرض عند الحاجة.
6. **إعادة تقييم:** إن بقي النظام غير مستقر، فكر في إعادة بناء نظيفة.

---

## 10. إجابة مباشرة: ماذا يمكن تجاهله؟

### يمكن تجاهله إذا أعدت التثبيت من الصفر

- `TigerVNC` بكامل حزمه — لأن Termux-X11 هو مسار العرض الحالي.
- `Xvfb` — إن لم تستخدم اختبارات headless.
- `x11-apps` — أدوات تشخيص فقط.
- `pcmanfm`/`gvfs`/`tumbler` — Thunar يكفي.
- `p7zip` و`p7zip-full` و`p7zip-rar` — `7zip` يكفي.
- `fonts-noto-cjk` — إن لم تحتج CJK.
- `snapd` و`apparmor` و`squashfs-tools` — بقايا snap.
- Node 22 من APT — إن كان nvm v20 يكفي.
- `code-server` — إن لم تستخدمه.
- Playwright browsers — إن لم تستخدم أتمتة المتصفح.
- `freebuff` — إن لم تستخدمه.
- Antigravity القديم في `/opt/antigravity` — بعد التأكد.
- `tailscale`/`cloudflared` — إن لم تحتج اتصالًا سحابيًا.
- `cron` — إن لم تحتج مهامًا دورية.
- `avahi` — إن لم تحتج اكتشاف الشبكة.
- `udisks2` — إن لم تحتج تخزينًا قابلًا للإزالة.
- `systemd` — لا يعمل كمدير نظام.
- `qt6`/`PyQt6` — إن لم تحتج تطبيقات Qt.
- `box64` — إن لم تحتج تطبيقات x86.
- `vulkan` — `/dev/dri` غير متاح أصلًا.
- `xinput` — غير مثبت، وأداة تشخيص فقط.

### لا يمكن تجاهله

- `proot`، `termux-x11-nightly`، `x11-repo`، `glibc`.
- `xfce4`، `dbus-x11`، `x11-utils`.
- `libgl1-mesa-dri` مع `GALLIUM_DRIVER=llvmpipe`.
- خط عربي جيد + `fontconfig` alias.
- `chromium` حديث + wrapper.
- `pulseaudio` في Termux + `PULSE_SERVER=tcp:127.0.0.1:4713`.
- `xdotool`/`wmctrl`/`xclip`/`autocutsel`.
- `supervisor` (لأن systemd غير فعّال).
- Node واحد، Python، وسكربتات الإقلاع.
- opencode وبيئة الوكلاء إن كنت تستخدمها.

### متوسط

- `code-server`، `Playwright`، `Freebuff`، `box64`، `Vulkan`، `Qt6/PyQt6`، `Tesseract`, `ImageMagick`, `7zip`, `pcmanfm`, `gvfs`, `tumbler`, `at-spi2-core`, `pyatspi`, `tailscale`, `cloudflared`.

---

## 11. أسئلة مفتوحة تحتاج قرارك

1. **هل تستخدم SSH فعلًا؟** إن لا، عطّل sshd بدل إصلاحه.
2. **هل تستخدم code-server؟** إن لا، احذفه ووفّر 147M+.
3. **هل تستخدم Playwright/أتمتة المتصفح؟** إن لا، احذف 405M.
4. **هل تستخدم Freebuff؟** إن لا، احذف `/opt/freebuff` وnpm global.
5. **هل تحتاج Antigravity القديم؟** إن لا، احذف `/opt/antigravity` (650M).
6. **هل تحتاج خطوط CJK؟** إن لا، قلّم Noto (قد توفر 300M+).
7. **هل تحتاج box64/Vulkan/Qt6؟** إن لا، احذفها.
8. **هل الإدخال العربي عبر لوحة Android أم عبر X11؟** يحدد حل IBus/XKB.
9. **لماذا `udisks2` محجوز؟** قد يكون لتجنب كسر systemd؛ يجب معرفة السبب قبل فك الحجز.
10. **ما حجم rootfs الفعلي؟** `du` انتهت مهلته؛ يمكن قياسه لاحقًا بـ`timeout 120 du -sh`.
11. **هل تريد إصلاح الأعطال الآن أم إعادة بناء نظيفة؟** التقرير لا يغيّر شيئًا.

---

## 12. ملحق الأدلة

### 12.1 مسارات مفتاحية

- `⁦/data/data/com.termux/files/home/start-ubuntu22.sh⁩` — الأسطر 52–55.
- `⁦/etc/profile.d/proot-fixes.sh⁩` — الأسطر 4، 6، 14.
- `⁦/etc/profile.d/proot-fixes.sh.bak-20260921105438⁩` — نسخة قديمة، يمكن أرشفتها.
- `⁦/root/.proot-init.sh⁩`.
- `⁦/etc/supervisor/conf.d/proot-services.conf⁩`.
- `⁦/etc/ssh/sshd_config⁩` — السطر 14، 85، 123، 124.
- `⁦/usr/local/bin/chromium-wrapper⁩`, `⁦electron-wrapper⁩`, `⁦antigravity-ide-launch⁩`, `⁦antigravity-ide-handler⁩`.
- `⁦/opt/antigravity⁩`, `⁦/opt/antigravity-ide⁩`, `⁦/opt/freebuff⁩`.
- `⁦/root/.local/bin⁩` — `agy`, `uv`, `uvx`, `vibe`, `vibe-acp`, `vibe-app-server`.
- `⁦/root/.local/share⁩` — 1.1G.
- `⁦/root/.cache⁩` — 1.1G.
- `⁦/root/.npm⁩` — 1.8G.
- `⁦/root/.nvm⁩` — 540M.
- `⁦/root/.bun⁩` — 286M.
- `⁦/root/.opencode⁩` — 239M.
- `⁦/usr/share/fonts⁩` — 798M.

### 12.2 أوامر تحقق آمنة (لا تغيّر شيئًا)

```bash
glxinfo -B
GALLIUM_DRIVER=llvmpipe glxinfo -B
pactl info
PULSE_SERVER=tcp:127.0.0.1:4713 pactl info
sshd -T | grep -E '^(port|permitrootlogin|usepam)'
supervisorctl status
fc-match 'sans-serif:lang=ar'
setxkbmap -query
xrandr --current
free -h
df -h /
apt-get -s autoremove
apt-cache policy chromium
```

### 12.3 ملاحظات ثقة

- **مؤكد:** أعداد الحزم، تواريخ APT، قيم المتغيرات، فشل GL، فشل الصوت، فشل SSH، Chromium 149/155، 131/163.
- **مرجح:** أن `virgl` هو سبب فشل GL (لأن llvmpipe نجح مباشرة).
- **مرجح:** أن `GTK_IM_MODULE=ibus` سبب مشاكل الإدخال (لأن ibus غير مثبت).
- **غير مؤكد:** سبب `udisks2` hold؛ الاستخدام الفعلي لـcode-server/Playwright/Freebuff/Antigravity القديم/CJK/Qt/box64.
- **غير متاح:** حصر العمليات والذاكرة بالاسم بسبب `/proc`؛ حجم rootfs الكامل بسبب timeout.

---

## 13. إفادة عدم التعديل

هذا التقرير **لم يغيّر** أي حزمة، أو ملف إقلاع، أو خدمة، أو إعداد نظام. كل ما ورد من إصلاحات هو **مقترح**، ويحتاج موافقة صريحة قبل التنفيذ.

---

## 14. الدليل التنفيذي لإعادة البناء

لخطوات إعادة البناء من الصفر (نسخ احتياطي، 3 مسارات، حزم Light/Parity، الصوت والعرض والعربية، والتحقق)، راجع:

- `termux-rebuild-from-zero.md`
- `rebuild/manifests/`
- `rebuild/scripts/`
