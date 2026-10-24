# L-60 — تقرير مراجعة DroidDesk Fast Path

**التاريخ:** 2026-10-23  
**المهمة:** تحليل ما يُنقل من DroidDesk إلى DeckOS Core، وما يُرفض، وما يتطلب تكيفًا.

---

## 1. ملخص القرار

| المكون                        | القرار     | السبب                                                     |
|-------------------------------|------------|----------------------------------------------------------|
| `dbus-daemon` + session bus   | ✅ **يُنقل** | يحسن استقرار تطبيقات X11 التي تتوقع dbus               |
| `waitForDesktop` عبر `/proc`  | ✅ **يُنقل** | أسرع وأكثر موثوقية من `pgrep`                           |
| GPU detection (Adreno check)  | ✅ **يُنقل** | opt-in `DECKOS_GPU=turnip` بدلاً من تفعيل افتراضي      |
| `devilspie2` window rules     | ✅ **يُنقل** | يحسن تجربة نوافذ X11 على الجوال                        |
| session lifecycle Kotlin API  | ✅ **يُنقل** | بناء `SessionManager` موحد                              |
| thread-safe connect() hook    | ✅ **يُنقل** | إصلاح `static char path_buf[256]` في socket_hook.c     |
| bootstrap path repair (apt)   | ✅ **يُنقل** | إصلاح تلقائي للحزم النصف-مثبتة عند بدء الجلسة          |
| Ubuntu rootfs / PRoot / chroot| ❌ **يُرفض** | +600MB، بطء، تعارض 16KB، لا يناسب Core               |
| llvmpipe كـGPU افتراضي        | ❌ **يُرفض** | رسم برمجي بطيء، S24U لها Adreno A740                   |
| rootfs بدون SHA-256           | ❌ **يُرفض** | خطر supply chain                                         |
| XDG_CONFIG_DIRS = com.termux  | ❌ **يُرفض** | مسار هش، يكشف prefix القديم                             |
| `close_range` shim            | ⚠️ **يُقيَّم** | Android 5.4 kernels فقط؛ S24U يعمل على 6.x — يُضاف كـfallback آمن |
| Wayland compositor (هيكل)     | ❌ **يُرفض** | غير مكتمل في DroidDesk، لا يُفيد Core                  |
| Decky Loader / ADB agent      | ❌ **يُرفض** | سطح هجوم لا يناسب Core v0.1                            |

---

## 2. ما يُنقل — التفاصيل التقنية

### 2.1 dbus-daemon (جلسة session bus)

**DroidDesk يفعل:**
```bash
dbus-daemon --session --print-address 1>&3
export DBUS_SESSION_BUS_ADDRESS=$(...)
```

**DeckOS يحتاج:** تطبيقات مثل `featherpad` و`pcmanfm` تبحث عن dbus عند التشغيل. بدونه تظهر تحذيرات أو تأخيرات.

**التنفيذ:** إضافة `dbus` إلى `DESKTOP_PACKAGES` وتشغيله في autostart.

### 2.2 waitForDesktop عبر `/proc`

**DroidDesk يستخدم `pgrep`** — قد يفشل إذا لم يكن `procps` مثبتًا.

**DeckOS يستخدم** `/proc/[pid]/cmdline` مباشرة — أسرع، لا تبعيات.

**التنفيذ:** إضافة `waitForDesktopReady()` في `X11Server.kt` يقرأ `/proc` مباشرة.

### 2.3 GPU Detection — opt-in Turnip

**DroidDesk يفعل:**
```kotlin
fun hasAdrenoGpu() = File("/dev/kgsl-3d0").exists()
```

**DeckOS يضيف:** `DeckOSGpuDetector` يكشف Adreno ويضبط env vars فقط إذا طُلب صراحة.

### 2.4 Session Lifecycle API

بناء `SessionManager.kt` موحد يحل محل الكود المتفرق في `LinuxRuntime` + `X11Server`.

### 2.5 thread-safe socket_hook connect()

**المشكلة في DroidDesk:** `static char path_buf[256]` في `connect()` — غير thread-safe.

**DeckOS socket_hook.c** يستخدم `buf` على الـstack في معظم الأماكن لكن يجب التحقق.

### 2.6 Auto-repair عند بدء الجلسة

DroidDesk يُصلح الحزم النصف-مثبتة قبل بدء الجلسة:
```bash
dpkg --configure -a
apt-get install -f -y
```
→ ينقل هذا إلى `ensureInstalled()`.

---

## 3. قرار socket_hook — الاستمرار مع التحسين

**القرار:** الاستمرار في استخدام `socket_hook` في DeckOS Core مع التحسينات التالية:
1. إصلاح `thread-safety` في `connect()` — استبدال `static char path_buf[256]` بـ`char path_buf[256]` على الـstack.
2. إضافة `execve` hook لإصلاح shebangs في runtime بدلاً من scan عند التثبيت.
3. عدم توسيع hook لـ`/proc` أو `/sys` — هذه مسارات kernel لا تحتاج إعادة توجيه.

**السبب:** DeckOS لا يحتاج PRoot ولا chroot؛ socket_hook كافٍ وأخف بكثير.

---

## 4. A/B Comparison جدول (L-65 مسبقًا)

| المقياس                  | Core Path (DeckOS) | DroidDesk Native Path | الفائز     |
|--------------------------|--------------------|-----------------------|------------|
| بدء سطح المكتب          | <1s                | ~3-5s (dbus+XFCE)    | DeckOS ✅   |
| RAM PSS                  | ~90MB              | ~180MB (XFCE+dbus)   | DeckOS ✅   |
| حجم APK                  | 47.5MB             | 40.4MB                | DroidDesk  |
| توافق 16KB page          | ✅ (مُصلَّح)        | ❌ (libtermux 4KB)   | DeckOS ✅   |
| GPU (Adreno A740)        | vendor default     | Turnip/Zink (opt-in) | تعادل      |
| استقرار جلسة 20/20      | لم يُختبر بعد     | ~15/20 (issues)      | غير محدد   |
| صوت                      | AAudio (موثوق)     | AAudio (موثوق)       | تعادل      |
| حافظة                    | ✅ xclip           | ✅ xclip              | تعادل      |

**الحكم:** DeckOS Core Path هو المسار الافتراضي. DroidDesk fast path = تحسينات محددة فقط.
