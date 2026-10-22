# إعادة بناء Termux + Ubuntu 22.04 من الصفر

**الغرض:** إذا حُذف Termux، أو فُقد الجهاز، أو ثُبّت Termux على جهاز آخر، كيف تعيد نفس بيئة العمل، بنفس الأداء والبرامج، دون أن تعتمد على النسخة القديمة ودون أن تنقل عيوبها.

**تاريخ التدقيق:** 2026-10-21  
**المصدر:** النسخة الحالية على الجهاز (Ubuntu 22.04.5 + Termux + proot + Termux:X11).  
**الملف المرافق:** `termux-system-baseline-and-additions.md` (الجرد والتصنيف)، و`rebuild/manifests/` (قوائم الحزم والإصدارات).

> **قاعدة أساسية:** النظام ليس طبقة واحدة. هو **Termux على Android** + **rootfs أوبنتو داخل proot**. إعادة بناء أي طبقة وحدها لا تعيد نفس النتيجة. الترتيب الصحيح: Termux أولًا، rootfs ثانيًا، ثم الحزم والإعدادات، ثم التطبيقات.

---

## 0. الخلاصة التنفيذية

1. **أفضل طريقة للنسخة المطابقة** ليست إعادة تثبيت كل شيء يدويًا، بل **نسخة احتياطية من `ubuntu22-fs` + السكربتات + `~/.config`** ثم استعادتها. هذا يعيد نفس النظام حرفيًا إذا كان المعالج ARM64.
2. **أفضل طريقة لجهاز جديد** هي **بناء نظيف** من rootfs أوبنتو 22.04.5 + قوائم الحزم، ثم نسخ الإعدادات والمشاريع فقط. لا تنسخ الحزم القديمة ولا الكاشات ولا `/opt` كاملًا.
3. **الفرق بين الأداء الجيد والسيئ عندك محسوم في 4 نقاط:**
   - `GALLIUM_DRIVER=llvmpipe` وليس `virgl`؛
   - `PULSE_SERVER=tcp:127.0.0.1:4713` وليس سوكت يونكس غير موجود؛
   - إزالة `GTK_IM_MODULE=ibus` لأن `ibus` غير مثبت؛
   - عدم تشغيل `sshd` مرتين، وعدم تشغيل PulseAudio داخل أوبنتو.
4. **لا تشغّل `ubuntu22-xfce.sh` على النظام الجديد بعد التثبيت.** هذا السكربت القديم يعيد كتابة `start-ubuntu22.sh` بنسخة ناقصة، ويحمّل سكربتات VNC من مسار Ubuntu19، ويحذف `/usr/local/bin/*`. النسخة الحالية نجت لأنها عُدّلت يدويًا بعده.
5. **لا تحدّث إلى 24.04** إذا أردت نفس البيئة. ابق على 22.04.5. الترقية تغيّر Mesa وGTK وQt وقد تكسر Electron وChromium.
6. **العربية لا تحتاج ibus.** تحتاج: خطوط Noto Arabic + تخطيط XKB `us,ara` + إزالة `GTK_IM_MODULE`. هذا أخف وأثبت.
7. **إذا كان الجهاز الجديد بمعالج أو رام مختلفين**، لن تكون النتيجة "نفس الأداء" حرفيًا؛ ستحتاج خفض الدقة أو استخدام ملف الخفة. نفس الإعدادات تعطي أفضل أداء ممكن للجهاز، وليس بالضرورة نفس الأرقام.
8. **الهدفان لا يتساويان:** "نفس كل شيء" يعني نسخ rootfs. "نفس البرامج بخفة أكبر" يعني بناء نظيف + استبعاد 163 حزمة قابلة للحذف و758MB خطوط CJK وQt6/code-server/Playwright غير الضرورية.

---

## 1. قبل الحذف: ما يجب حفظه (10 دقائق)

### 1.1 أين تعيش البيانات؟

| المسار | ماذا يفقد عند حذف Termux؟ | الإجراء |
|---|---|---|
| `/data/data/com.termux/files/home/ubuntu22-fs` | **كل نظام أوبنتو وكل ما بداخله** | نسخة احتياطية كاملة |
| `/data/data/com.termux/files/home/*.sh` | سكربتات التشغيل | نسخ إلى `/sdcard` |
| `/data/data/com.termux/files/home/.termux` | إعدادات الطرفية والألوان | نسخ |
| `/root/.config` داخل rootfs | إعدادات XFCE والخطوط والتطبيقات | نسخ انتقائي |
| `/root/.local`, `/root/.ssh`, `/root/.gitconfig` | الجلسات والمفاتيح والمشاريع | نسخ آمن |
| `/sdcard` | لا يُحذف مع Termux | يبقى، لكنه ليس نسخة احتياطية كاملة |
| تطبيق Termux نفسه | بياناته داخل `/data/data` | لا يمكن استعادتها بعد الحذف إلا من نسخة |

### 1.2 أوامر النسخ من Termux (خارج proot)

```bash
mkdir -p /sdcard/deck-backup
cp -a "$HOME"/start-ubuntu22.sh "$HOME"/launch-ubuntu.sh "$HOME"/fix-screen.sh /sdcard/deck-backup/ 2>/dev/null
cp -a "$HOME"/.termux /sdcard/deck-backup/termux-config 2>/dev/null
cp -a "$HOME"/.bashrc "$HOME"/.profile /sdcard/deck-backup/ 2>/dev/null
```

### 1.3 نسخة rootfs كاملة

هذه أهم خطوة. أوقف جلسة أوبنتو أولًا (اخرج منها)، ثم من Termux:

```bash
pkg install -y zstd
cd "$HOME"
tar --warning=no-file-changed -cpf - ubuntu22-fs | zstd -T0 -19 -o /sdcard/deck-backup/ubuntu22-fs.tar.zst
```

للتحقق:

```bash
zstd -t /sdcard/deck-backup/ubuntu22-fs.tar.zst
ls -lh /sdcard/deck-backup/ubuntu22-fs.tar.zst
```

الاستعادة:

```bash
cd "$HOME"
zstd -dc /sdcard/deck-backup/ubuntu22-fs.tar.zst | tar -xpf - --no-same-owner -C "$HOME"
```

> لا تستخرج فوق rootfs يعمل. أوقف proot أولًا. ملفات rootfs مملوكة لـ UID وهمي؛ `--no-same-owner` يمنع أخطاء الصلاحيات، وproot `-0` يعرضها كـ root.

### 1.4 نسخ الإعدادات من داخل أوبنتو

```bash
mkdir -p /sdcard/deck-backup/ubuntu-config
cp -a /root/.config/xfce4 /root/.config/fontconfig /root/.config/autostart /sdcard/deck-backup/ubuntu-config/ 2>/dev/null
cp -a /root/.local/share/applications /sdcard/deck-backup/ubuntu-config/ 2>/dev/null
cp -a /root/.bashrc /root/.profile /root/.xprofile /sdcard/deck-backup/ubuntu-config/ 2>/dev/null
cp -a /usr/local/bin/chromium-wrapper /usr/local/bin/antigravity-ide-launch /usr/local/bin/antigravity-ide-handler /sdcard/deck-backup/ubuntu-config/ 2>/dev/null
cp -a /etc/profile.d/proot-fixes.sh /etc/supervisor/conf.d/proot-services.conf /sdcard/deck-backup/ubuntu-config/ 2>/dev/null
```

**لا تنسخ:** `/root/.cache`, `/root/.npm/_cacache`, `/root/.cache/ms-playwright`, `/root/.cache/chromium`, `/root/.cache/code-server`, `/var/lib/apt/lists`, `/var/cache/apt`, `/tmp`, `/proc`, `/sys`, `/dev`. هذه كاشات قابلة لإعادة التوليد وتضخم النسخة.

### 1.5 الأسرار

هذه الملفات تحمل مفاتيح وجلسات، ولا تُرفع إلى Drive أو Git:

- `/root/.ssh/`
- `/root/.config/opencode/` (قد يحتوي auth)
- `/root/.local/share/opencode/`
- `/root/.config/gh/hosts.yml`
- `/root/.git-credentials`
- `/root/.npmrc`
- `/root/.config/code-server/config.yaml`
- أي `.env` داخل المشاريع

انقلها مشفرة (`age` أو `gpg`) أو أعد تسجيل الدخول من جديد. إذا فُقد الجهاز، ألغِ المفاتيح من لوحات الخدمات.

### 1.6 تصدير القوائم (مهم للبناء النظيف)

من داخل أوبنتو:

```bash
mkdir -p /sdcard/deck-backup/manifests
apt-mark showmanual | sort > /sdcard/deck-backup/manifests/ubuntu-manual.txt
pip3 freeze | sort > /sdcard/deck-backup/manifests/pip-freeze.txt
npm ls -g --depth=0 > /sdcard/deck-backup/manifests/npm-global.txt
dpkg-query -W -f='${Package}=${Version}\n' > /sdcard/deck-backup/manifests/ubuntu-versions.txt
```

الملفات الجاهزة موجودة الآن في:

- `rebuild/manifests/ubuntu-manual.txt`
- `rebuild/manifests/termux-packages.txt`
- `rebuild/manifests/pip-freeze.txt`
- `rebuild/manifests/npm-global.txt`
- `rebuild/manifests/versions.txt`

---

## 2. اختر مسارك: 3 مسارات

| المسار | متى تختاره | النتيجة | العيوب |
|---|---|---|---|
| **A. استعادة طبق الأصل** | نفس الجهاز، ولديك نسخة `ubuntu22-fs.tar.zst` | نفس النظام حرفيًا، أسرع استعادة | ينقل العيوب والبلoat (163 حزمة، كاشات، Node مزدوج) |
| **B. بناء نظيف** | جهاز جديد، أو تريد أخف نظام | نظام نظيف، أقل حزمًا، أسهل صيانة | يحتاج 45–90 دقيقة وتنزيلات |
| **C. هجين (موصى به)** | جهاز جديد مع الاحتفاظ بالإعدادات | rootfs نظيف + إعداداتك ومشاريعك | تحتاج مراجعة الإعدادات القديمة |

**القرار العملي:**

- إذا أردت **نفس الشيء بأقل جهد**: A.
- إذا أردت **نفس البرامج بخفة أكبر**: C.
- إذا كانت النسخة القديمة ملوثة أو غير مفهومة: B.

**لا تنسخ rootfs قديمًا إلى جهاز بمعمارية مختلفة.** إذا كان الجديد `aarch64` فيعمل غالبًا، لكنه يحمل إعدادات Android/جهاز قديم. البناء النظيف أنظف.

---

## 3. المرحلة A: تجهيز Termux المضيف

### 3.1 التثبيت الصحيح

- ثبّت **Termux من F-Droid أو GitHub Releases**، وليس من Google Play (نسخة Play قديمة ومهملة).
- ثبّت **Termux:X11** من نفس المصدر (F-Droid/GitHub).
- ثبّت **Termux:API** إذا ستستخدم `termux-api`.
- لا تثبّت `sudo` داخل Termux؛ rootfs هو الذي يعطي root وهميًا.

### 3.2 صلاحيات Android والأداء

في Android:

1. اجعل Termux وTermux:X11 **غير مقيّدين في استهلاك البطارية**.
2. اسمح لـ Termux بالوصول إلى التخزين.
3. شغّل `termux-wake-lock` أثناء العمل الطويل.
4. على Android 12+ عطّل **phantom process killer**، وإلا سيقتل عمليات proot/Chromium عند الضغط على الذاكرة:

```bash
adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config put activity_manager max_phantom_processes 2147483647
```

قد تحتاج إعادة تنفيذ الأمر بعد إعادة تشغيل الجهاز أو تحديث Android. يمكنك استخدام ADB لاسلكي من Termux نفسه إذا كان `android-tools` مثبتًا ومفعّلًا.

### 3.3 حزم Termux المطلوبة

```bash
pkg update -y && pkg upgrade -y
pkg install -y x11-repo
pkg install -y termux-x11-nightly proot pulseaudio termux-api android-tools git gh tmux fribidi dbus libqrencode expect zbar wget curl tar
```

اختياري / للمطابقة:

```bash
pkg install -y glibc-repo && pkg install -y glibc
pkg install -y llama-cpp python openssh
```

ملاحظات:

- `nodejs` غير مثبت في Termux حاليًا، ولا تحتاجه: Node يعمل داخل أوبنتو.
- `pulseaudio` هنا هو **خادم الصوت على Android**، وليس خادم أوبنتو.
- `fribidi` مهم لعرض العربية في الطرفية.
- `glibc` ليس شرطًا لتشغيل proot؛ أبقِه فقط إذا كنت تشغّل أدوات Termux المبنية على glibc.
- `llama-cpp` و`python` و`openssh` اختيارية.

### 3.4 الصوت وTermux:X11

```bash
termux-setup-storage
termux-wake-lock
pulseaudio --start --exit-idle-time=-1 --disallow-exit
```

للتحقق من الصوت من Termux:

```bash
pactl info
pactl list short sinks
```

ثم شغّل X11:

```bash
termux-x11 :1 -ac &
```

إذا ظهرت مشكلة أن أسفل الشاشة مخفي خلف شريط المفاتيح، شغّل `fix-screen.sh` أو:

```bash
termux-x11-preference \
  adjustHeightForEK:true \
  enableAccessibilityServiceAutomatically:true \
  additionalKbdVisible:true
```

للحفاظ على دقة الجهاز المصدر (اختياري، مناسب لـ S24 Ultra):

```bash
termux-x11-preference displayResolutionMode:exact displayResolutionExact:1280x2260
```

على جهاز جديد، استخدم دقته الأصلية أولًا، ثم خفّضها إذا كان llvmpipe بطيئًا.

---

## 4. المرحلة B: إنشاء rootfs

### 4.1 المصدر

**للنسخة المطابقة:** استخدم نفس rootfs الموجود حاليًا (Andronix Ubuntu 22.04 jammy arm64)، أو استعد نسختك الاحتياطية.

**للبناء النظيف (موصى به):** استخدم Ubuntu Base 22.04.5 arm64 من `cdimage.ubuntu.com` مع ملف `SHA256SUMS`، وليس سكربت Andronix. هذا يمنحك نظامًا أصليًا بلا سكربتات VNC قديمة.

المتطلبات:

```bash
uname -m
# يجب أن يكون aarch64
```

إذا كان `armv7l` أو `x86_64`، فأنت تحتاج rootfs مختلفًا، ولن تكون نفس الحزم/التطبيقات متاحة بنفس الشكل.

### 4.2 الاستخراج

```bash
cd "$HOME"
mkdir -p ubuntu22-fs ubuntu22-shm
cd ubuntu22-fs
proot --link2symlink tar -xf /path/to/ubuntu-base-22.04.5-base-arm64.tar.gz --exclude=dev
cd "$HOME"
mkdir -p ubuntu22-fs/proc/fakethings ubuntu22-fs/tmp ubuntu22-shm
chmod 1777 ubuntu22-fs/tmp ubuntu22-shm
```

### 4.3 ملفات `/proc` الوهمية

النسخة الحالية تستخدم `proc/fakethings/{stat,vmstat,version}` لخداع بعض البرامج التي تقرأ معلومات المعالج من Android. انسخها من النسخة الاحتياطية:

```bash
mkdir -p "$HOME/ubuntu22-fs/proc/fakethings"
cp -a /path/to/backup/fakethings/. "$HOME/ubuntu22-fs/proc/fakethings/"
```

إن لم تكن موجودة، يمكنك إنشاء ملفات بسيطة، لكن بعض الأدوات سترى معالج Android الحقيقي. الأفضل نسخها.

### 4.4 الإعداد الأولي

```bash
printf '127.0.0.1 localhost\n' > "$HOME/ubuntu22-fs/etc/hosts"
printf 'Acquire::Retries "3";\n' > "$HOME/ubuntu22-fs/etc/apt/apt.conf.d/80-retries"
```

انسخ السكربتات من `rebuild/scripts/` إلى `$HOME`:

```bash
cp /path/to/rebuild/scripts/start-ubuntu22.sh "$HOME/"
cp /path/to/rebuild/scripts/launch-ubuntu.sh "$HOME/"
cp /path/to/rebuild/scripts/fix-screen.sh "$HOME/"
chmod +x "$HOME"/start-ubuntu22.sh "$HOME"/launch-ubuntu.sh "$HOME"/fix-screen.sh
```

ثم:

```bash
bash "$HOME/launch-ubuntu.sh"
```

---

## 5. المرحلة C: أساس أوبنتو

### 5.1 التحديث واللغة

داخل أوبنتو:

```bash
export DEBIAN_FRONTEND=noninteractive
apt update
apt full-upgrade -y
apt install -y locales sudo ca-certificates curl wget gnupg lsb-release software-properties-common
locale-gen en_US.UTF-8
update-locale LANG=en_US.UTF-8
```

اختياري للعربية كلغة نظام (ليس ضروريًا لعرض النص العربي):

```bash
locale-gen ar_SA.UTF-8
```

### 5.2 المستودعات

```bash
add-apt-repository -y universe
add-apt-repository -y ppa:xtradeb/apps
apt update
```

`xtradeb` هو مصدر Chromium الحالي على 22.04 (لأن Ubuntu يوزع Chromium كـ snap فقط). إذا لم ترغب بPPA، استخدم متصفحًا آخر، لكن لا تتوقع نفس Chromium.

### 5.3 حزم النظام — ملف "Light"

هذه القائمة تعطي XFCE + طرفية + متصفح + أدوات يومية، مع استبعاد VNC وQt6 وcode-server والخطوط الصينية:

```bash
apt install -y --no-install-recommends \
  xfce4 xfce4-terminal thunar xfce4-whiskermenu-plugin xfce4-clipman-plugin \
  xfce4-notifyd xfce4-screenshooter xfce4-taskmanager xfce4-pulseaudio-plugin \
  dbus-x11 at-spi2-core xauth x11-xserver-utils x11-xkb-utils x11-utils x11-apps \
  desktop-file-utils xdg-utils exo-utils adwaita-icon-theme gnome-icon-theme \
  mesa-utils libgl1-mesa-dri libglx-mesa0 libegl1 libegl-mesa0 libgles2 \
  libvulkan1 mesa-vulkan-drivers libxcb-cursor0 \
  pulseaudio-utils libpulse0 \
  fonts-dejavu-core fonts-liberation fonts-noto-core fonts-noto-ui-core \
  fonts-noto-color-emoji fonts-arabeyes fonts-hosny-amiri \
  xclip autocutsel wmctrl xdotool \
  git nano unzip zip p7zip-full imagemagick tesseract-ocr tesseract-ocr-ara \
  python3 python3-pip python3-venv python3-tk \
  net-tools iputils-ping dnsutils
```

ثم Chromium:

```bash
apt install -y chromium
chromium --version
```

### 5.4 حزم "Parity" (إذا أردت نفس كل شيء)

أضف فوق القائمة السابقة:

```bash
apt install -y \
  xfce4-goodies tumbler tumbler-plugins-extra gvfs gvfs-backends gvfs-fuse pcmanfm \
  python3-dev python3-nautilus python3-pyatspi \
  python3-pyqt6 python3-pyqt6.qtsvg python3-pyqt6.qtwebengine \
  qt6-base-dev qt6-webengine-dev-tools \
  libqt6webengine6-data libqt6webenginecore6 libqt6webenginecore6-bin \
  libqt6webenginequick6 libqt6webenginewidgets6 \
  libx11-dev libxtst-dev \
  pulseaudio supervisor cron openssh-server \
  tigervnc-standalone-server tigervnc-common tigervnc-tools xvfb
```

هذه هي الحزم التي تجعل النسخة "مطابقة" لكنها أيضًا مصدر الثقل. لا تثبتها إلا إذا كنت تستخدم VNC أو Qt أو PyQt فعليًا.

### 5.5 Node (اختياري لكنه مطلوب لبعض الأدوات)

النسخة الحالية فيها Node مزدوج: nvm v20 وNodeSource v22. هذا تضخم. اختر واحدًا:

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
apt install -y nodejs
node --version
npm --version
```

ثم لا تثبت nvm إلا إذا كنت تحتاج تبديل إصدارات Node فعليًا. `opencode` و`Antigravity` لا يحتاجان Node خارجيًا (لأنهما standalone/Electron)، لكن `freebuff` و`code-server` قد يحتاجان.

### 5.6 تنظيف

```bash
apt autoremove --purge -y
apt clean
rm -rf /var/lib/apt/lists/*
```

---

## 6. المرحلة D: XFCE والعرض والصوت والعربية

### 6.1 تشغيل الجلسة

```bash
startxfce4
```

إذا لم تبدأ الجلسة، تأكد من `dbus-x11` و`xfce4-session` ثم أعد المحاولة.

### 6.2 إعدادات الأداء

```bash
xfconf-query -c xfwm4 -p /general/use_compositing -s false
xfconf-query -c xsettings -p /Xft/DPI -s 122
```

- `use_compositing=false` يقلل حمل llvmpipe.
- DPI 122 هو المستخدم حاليًا. على جهاز بدقة مختلفة، جرّب 96 أو 110.
- لا تضع `LIBGL_ALWAYS_SOFTWARE=1`؛ على هذا النظام يفشل. استخدم `GALLIUM_DRIVER=llvmpipe` فقط.
- لا تستخدم `virgl`؛ `/dev/dri` غير متاح داخل proot.

### 6.3 الصوت عبر Termux

خادم الصوت يجب أن يكون **في Termux**، والعميل في أوبنتو:

```bash
# في Termux
pulseaudio --start --exit-idle-time=-1 --disallow-exit
pactl load-module module-native-protocol-tcp port=4713 listen=127.0.0.1 auth-ip-acl=127.0.0.1
```

داخل أوبنتو:

```bash
PULSE_SERVER=tcp:127.0.0.1:4713 pactl info
PULSE_SERVER=tcp:127.0.0.1:4713 pactl list short sinks
```

إذا ظهر خطأ مصادقة:

```bash
export PULSE_COOKIE=/data/data/com.termux/files/usr/etc/pulse/cookie
```

ثم أعد الاختبار. لا تشغّل `pulseaudio --start` داخل أوبنتو؛ هذا يعارض خادم Termux.

لتعطيل خادم PulseAudio المحلي داخل أوبنتو (إن كان مثبتًا):

```bash
mkdir -p /root/.config/autostart
cat > /root/.config/autostart/pulseaudio.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=PulseAudio (disabled)
Exec=/bin/true
Hidden=true
EOF
```

### 6.4 الحافظة

```bash
autocutsel -fork -selection CLIPBOARD
autocutsel -fork -selection PRIMARY
```

هذا يجعل النسخ بين Chromium والطرفية يعمل. شغّله بعد بدء X11.

### 6.5 العربية: الخطوط والتخطيط

**الخطوط:**

```bash
mkdir -p /root/.config/fontconfig
cp /path/to/rebuild/scripts/fonts.conf /root/.config/fontconfig/fonts.conf
fc-cache -fv
fc-match -f '%{family}\n' 'sans-serif:lang=ar'
```

المتوقع بعد الإعداد: `Noto Sans Arabic` أو `Noto Naskh Arabic`، وليس `DejaVu Sans`. الخطوط موجودة أصلًا في `fonts-noto-core`، لكن fallback الافتراضي الحالي يختار DejaVu.

**التخطيط:**

```bash
setxkbmap -layout us,ara -option grp:alt_shift_toggle
setxkbmap -query
```

للحفظ بعد كل جلسة، انسخ:

```bash
cp /path/to/rebuild/scripts/arabic-keyboard.desktop /root/.config/autostart/
```

**لا تفعل:**

- لا تصدّر `GTK_IM_MODULE=ibus` بدون تثبيت ibus.
- لا تثبت fcitx5 لمجرد العربية؛ العربية تحتاج تخطيط XKB وخطوطًا، لا IME.
- إذا كنت ستستخدم ibus/fcitx5 لاحقًا، اضبط `GTK_IM_MODULE` و`QT_IM_MODULE` معًا بعد تثبيتهما، وليس قبلهما.

---

## 7. المرحلة E: التطبيقات

### 7.1 opencode

النسخة الحالية: `1.18.35` في `/root/.opencode/bin/opencode`.

أعد التثبيت من قناة opencode الرسمية، ثم:

```bash
opencode --version
```

احفظ/انقل:

```bash
/root/.config/opencode/
/root/.local/share/opencode/
```

هذه قد تحتوي جلسات ومفاتيح؛ انقلها بأمان.

### 7.2 Antigravity

النسخة الحالية:

- `/opt/antigravity` → `2.0.11`
- `/opt/antigravity-ide` → `2.5.5`
- يوجد أيضًا `Antigravity-IDE.bak-2.0.4`

للنسخة الجديدة، ثبّت **Antigravity IDE 2.5.5 فقط** إن كنت لا تحتاج القديم. حمّل حزمة `.deb` arm64 الرسمية:

```bash
apt install -y ./Antigravity-IDE-2.5.5-arm64.deb
apt -f install -y
```

ثم انسخ السكربتات:

```bash
cp /path/to/rebuild/scripts/antigravity-ide-launch /usr/local/bin/
cp /path/to/rebuild/scripts/antigravity-ide-handler /usr/local/bin/
chmod +x /usr/local/bin/antigravity-ide-launch /usr/local/bin/antigravity-ide-handler
```

ملاحظة: `--no-sandbox` ضروري داخل proot، لكنه يقلل العزل الأمني. لا تشغّل به ملفات غير موثوقة.

### 7.3 Chromium

بعد تثبيت `chromium` من PPA:

```bash
cp /path/to/rebuild/scripts/chromium-wrapper /usr/local/bin/
chmod +x /usr/local/bin/chromium-wrapper
ln -sf /usr/local/bin/chromium-wrapper /usr/local/bin/chromium
```

أنشئ Desktop Entry محلية تستخدم الwrapper:

```bash
mkdir -p /root/.local/share/applications
cat > /root/.local/share/applications/chromium.desktop <<'EOF'
[Desktop Entry]
Version=1.0
Name=Chromium
Exec=/usr/local/bin/chromium %U
Terminal=false
Type=Application
Icon=chromium
Categories=Network;WebBrowser;
EOF
```

- ابدأ بـ `--no-sandbox --disable-dev-shm-usage`.
- لا تضع `--disable-gpu` افتراضيًا؛ جرّب بدونه أولًا.
- إذا حصلت شاشة سوداء أو crash، أضف `--disable-gpu` كحل مؤقت.
- إذا كان `/dev/shm` صغيرًا، `--disable-dev-shm-usage` مفيد؛ مع مجلد `/dev/shm` المخصص قد لا تحتاجه.

### 7.4 code-server (اختياري)

النسخة الحالية `4.123.0`. إن لم تكن تستخدمه، لا تثبته؛ فهو يضيف Node وحزمًا وكاشًا.

```bash
curl -fsSL https://code-server.dev/install.sh | sh
code-server --version
```

أو ثبّت `.deb` بنفس الإصدار من GitHub Releases:

```bash
curl -fLO https://github.com/coder/code-server/releases/download/v4.123.0/code-server_4.123.0_arm64.deb
apt install -y ./code-server_4.123.0_arm64.deb
```

### 7.5 Python وPyQt6 (اختياري)

النسخة الحالية فيها 146 حزمة pip، منها PyQt6 6.3.1 وFastAPI وHuggingFace. الأفضل عدم تثبيت كل هذا عالميًا. أنشئ venv للمشروع:

```bash
python3 -m venv /root/venvs/app
source /root/venvs/app/bin/activate
pip install -r /path/to/requirements.txt
```

إذا كنت تحتاج PyQt6 تحديدًا:

```bash
pip install PyQt6==6.3.1 PyQt6-WebEngine==6.3.1
```

هذه الحزم ثقيلة (Qt6 WebEngine). لا تثبتها إلا لتطبيق Qt فعلي.

### 7.6 أدوات أخرى

| الأداة | الحالة الحالية | recommendation |
|---|---|---|
| `uv` | 0.11.26 | أعد تثبيته إن كنت تستخدمه: `curl -LsSf https://astral.sh/uv/install.sh \| sh` |
| `vibe` | 2.25.0 | أعد تثبيته من مصدره/طريقته السابقة |
| `freebuff` | **مكسور** (الملف يشير إلى `@codebufffreebuff-desktop` غير موجود) | لا تعتمد عليه؛ أعد تثبيت الحزمة الرسمية أو استغن عنه |
| `composio-core`, `wrangler` | npm global | اختياري |
| `tesseract` + `ara` | مثبت | ضروري فقط لـOCR |
| `imagemagick` | مثبت | ضروري لمعالجة الصور |
| `ffmpeg` | **غير مثبت** | ثبّته فقط إذا كنت تحتاجه |

---

## 8. السكربتات الكاملة

انسخها من `rebuild/scripts/` بعد مراجعتها. لا تشغّل أي سكربت قديم قبل قراءته.

### 8.1 `start-ubuntu22.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

if [ ! -d "$DIR/ubuntu22-fs" ]; then
    echo "ubuntu22-fs غير موجود في $DIR" >&2
    exit 1
fi

unset LD_PRELOAD

SHM_DIR="$DIR/ubuntu22-shm"
RUNTIME_DIR="$DIR/ubuntu22-fs/tmp/runtime-root"
mkdir -p "$SHM_DIR" "$RUNTIME_DIR" "$DIR/ubuntu22-fs/proc/fakethings"
chmod 1777 "$SHM_DIR" 2>/dev/null || true
chmod 700 "$RUNTIME_DIR" 2>/dev/null || true

cmd=(proot --kill-on-exit --link2symlink --sysvipc -0 -r "$DIR/ubuntu22-fs")
cmd+=(-b /dev -b /proc -b /sys)
[ -d /data ] && cmd+=(-b /data)
[ -d /sdcard ] && cmd+=(-b /sdcard)
cmd+=(-b "$SHM_DIR:/dev/shm")
cmd+=(-b /proc/self/fd/2:/dev/stderr)
cmd+=(-b /proc/self/fd/1:/dev/stdout)
cmd+=(-b /proc/self/fd/0:/dev/stdin)
cmd+=(-b /proc/self/fd:/dev/fd)
cmd+=(-b /dev/urandom:/dev/random)
cmd+=(-b "$DIR/ubuntu22-fs/proc/fakethings/stat:/proc/stat")
cmd+=(-b "$DIR/ubuntu22-fs/proc/fakethings/vmstat:/proc/vmstat")
cmd+=(-b "$DIR/ubuntu22-fs/proc/fakethings/version:/proc/version")

if [ -d "$PREFIX/tmp/.X11-unix" ]; then
    cmd+=(-b "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix")
elif [ -d /tmp/.X11-unix ]; then
    cmd+=(-b /tmp/.X11-unix:/tmp/.X11-unix)
fi

[ -f /etc/resolv.conf ] && cmd+=(-b /etc/resolv.conf:/etc/resolv.conf)
cmd+=(-w /root)

cmd+=(/usr/bin/env -i)
cmd+=(MOZ_FAKE_NO_SANDBOX=1)
cmd+=(ELECTRON_DISABLE_SANDBOX=1)
cmd+=(GALLIUM_DRIVER=llvmpipe)
cmd+=(DISPLAY=:1)
cmd+=(PULSE_SERVER=tcp:127.0.0.1:4713)
cmd+=(QT_QPA_PLATFORM=xcb)
cmd+=(HOME=/root)
cmd+=(PATH=/root/.local/bin:/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/bin:/usr/bin:/sbin:/usr/sbin:/usr/games:/usr/local/games)
cmd+=(TERM="${TERM:-xterm-256color}")
cmd+=(LANG=en_US.UTF-8)
cmd+=(LC_CTYPE=en_US.UTF-8)
cmd+=(XAUTHORITY=/root/.Xauthority)
cmd+=(XDG_RUNTIME_DIR=/tmp/runtime-root)
cmd+=(/bin/bash --login)

if [ "$#" -eq 0 ]; then
    exec "${cmd[@]}"
else
    exec "${cmd[@]}" -c "$*"
fi
```

### 8.2 `launch-ubuntu.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

if command -v pulseaudio >/dev/null 2>&1; then
    if ! pidof pulseaudio >/dev/null 2>&1; then
        rm -rf "$PREFIX/tmp"/pulse-* 2>/dev/null || true
        pulseaudio --start --exit-idle-time=-1 --disallow-exit
        sleep 2
    fi
fi

if command -v pactl >/dev/null 2>&1; then
    pactl load-module module-native-protocol-tcp port=4713 listen=127.0.0.1 auth-ip-acl=127.0.0.1 >/dev/null 2>&1 || true
fi

if command -v termux-x11 >/dev/null 2>&1; then
    pkill -f 'termux-x11 :1' 2>/dev/null || true
    sleep 1
    termux-x11 :1 -ac >"$PREFIX/tmp/termux-x11.log" 2>&1 &
    sleep 2
else
    echo "termux-x11 غير مثبت" >&2
    exit 1
fi

exec bash "${DIR}/start-ubuntu22.sh" "$@"
```

### 8.3 `proot-fixes.sh`

يوضع في `/etc/profile.d/proot-fixes.sh`:

```bash
export MOZ_FAKE_NO_SANDBOX=1
export ELECTRON_DISABLE_SANDBOX=1
export GALLIUM_DRIVER=llvmpipe
export DISPLAY=:1
export PULSE_SERVER=tcp:127.0.0.1:4713
export QT_QPA_PLATFORM=xcb
export SSH_PORT=10022
unset GTK_IM_MODULE QT_IM_MODULE XMODIFIERS
```

**لا تكرر `PULSE_SERVER`.** آخر سطر يفوز؛ وجوده مرتين كان سبب تعطيل الصوت.

### 8.4 `fix-screen.sh`

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -e
termux-x11-preference \
    adjustHeightForEK:true \
    enableAccessibilityServiceAutomatically:true \
    additionalKbdVisible:true
# termux-x11-preference displayResolutionMode:exact displayResolutionExact:1280x2260
```

### 8.5 `chromium-wrapper`

```bash
#!/bin/bash
exec /bin/chromium --no-sandbox --disable-dev-shm-usage "$@"
```

### 8.6 `antigravity-ide-launch`

```bash
#!/bin/bash
exec </dev/null >/tmp/agi_icon.log 2>&1
export NODE_OPTIONS="--max-old-space-size=2048"
exec setsid --fork /usr/local/bin/antigravity-ide \
  --no-sandbox \
  --use-gl=angle \
  --use-angle=swiftshader \
  --enable-unsafe-swiftshader \
  --disable-gpu-compositing \
  --disable-dev-shm-usage \
  --disable-workspace-trust \
  --mute-audio \
  --disable-features=AudioServiceOutOfProcess,MediaSessionService,HardwareMediaKeyHandling \
  --js-flags="--max-old-space-size=2048" \
  "$@"
```

### 8.7 `.proot-init.sh` — لا تستخدمه كما هو

النسخة الحالية تحتوي على `.proot-init.sh` لكنه **لا يُستدعى من أي ملف بدء** (لا `.bashrc` ولا `.profile` ولا XFCE). وإذا شغّلته يدويًا فهو:

- يشغّل `supervisord`،
- ثم يشغّل `sshd` مرة ثانية خارج supervisor،
- ويحاول تشغيل PulseAudio داخل أوبنتو،
- ويشغّل `autocutsel`.

لنظام خفيف: لا تستخدمه. شغّل `autocutsel` يدويًا أو عبر autostart. إذا احتجت cron/sshd، شغّل supervisor صراحةً وتأكد من `/run/sshd`.

---

## 9. قائمة التحقق النهائية

### 9.1 العرض والرسومات

```bash
glxinfo -B | grep -E 'OpenGL renderer|OpenGL version'
```

المتوقع:

```text
OpenGL renderer string: llvmpipe (LLVM 15.0.7, 128 bits)
OpenGL version string: 4.5 (Compatibility Profile) Mesa 23.2.1
```

- إذا ظهر `virgl` أو فشل `glxinfo`، راجع `GALLIUM_DRIVER`.
- إذا ظهر `failed to create drisw screen`، أنت تستخدم `LIBGL_ALWAYS_SOFTWARE=1`؛ أزله.

### 9.2 الصوت

```bash
PULSE_SERVER=tcp:127.0.0.1:4713 pactl info
```

المتوقع: `Server Name: pulseaudio`، `Protocol Version: 35`.

### 9.3 الدقة واللوحة

```bash
xrandr --current | head -5
setxkbmap -query
```

- الدقة المتوقعة على الجهاز المصدر: `1280x2260`.
- التخطيط: `us,ara` مع `grp:alt_shift_toggle`.

### 9.4 الخطوط

```bash
fc-match -f '%{family}\n' 'sans-serif:lang=ar'
fc-match -f '%{family}\n' 'serif:lang=ar'
```

المتوقع: `Noto Sans Arabic` / `Noto Naskh Arabic`.

### 9.5 التطبيقات

```bash
chromium --version
opencode --version
antigravity-ide --version
code-server --version 2>/dev/null || true
```

- `chromium`: 149.x أو أحدث من PPA.
- `opencode`: 1.18.35 (أو أحدث).
- `antigravity-ide`: 2.5.5.
- `code-server`: 4.123.0 إن ثُبّت.

### 9.6 الحافظة

انسخ نصًا من Chromium، ثم:

```bash
xclip -selection clipboard -o
```

### 9.7 الموارد

```bash
df -h /
free -h
```

- اترك 15GB حرة على الأقل قبل البناء.
- 8GB RAM قد تكفي لـ XFCE + Chromium، لكن Antigravity + Chromium + code-server معًا قد تستهلك أكثر من 7GB.

---

## 10. الأداء والخفة: ما يجب فعله وما يجب تجنبه

### 10.1 ما يستهلك في النسخة الحالية

| العنصر | الحجم/الحالة | الإجراء |
|---|---|---|
| `~/.npm` | 1.8GB | `npm cache clean --force` |
| `~/.cache` | 1.1GB | احذف Chromium/Playwright/code-server إن لم تستخدمها |
| `/opt/antigravity` | 650MB (2.0.11) | احذفه إذا استخدمت IDE 2.5.5 فقط |
| `/opt/antigravity-ide/Antigravity-IDE.bak-2.0.4` | نسخة قديمة | احذفها |
| `/opt/freebuff` | 416MB + أداة مكسورة | احذفه إن لم تصلحه |
| `~/.nvm` | 540MB | احذفه إذا اخترت NodeSource |
| `~/.bun` | 286MB | احذفه إن لم تستخدم Bun |
| apt cache + lists | 450MB | `apt clean` و`rm -rf /var/lib/apt/lists/*` |
| `fonts-noto` الكامل + CJK | 758MB | استبدله بـ `fonts-noto-core` فقط |
| Qt6 + PyQt6 | ~5.8MB مكتبات + 35 حزمة | لا تثبته إلا لتطبيق Qt |
| code-server | 147MB كاش | لا تثبته إن لم تستخدمه |
| Playwright cache | 405MB | لا تثبته إن لم تستخدمه |
| 163 حزمة autoremove | غير معروفة | راجعها ثم احذفها |

### 10.2 أوامر تنظيف آمنة نسبيًا

```bash
apt autoremove --purge -y
apt clean
npm cache clean --force 2>/dev/null || true
pip cache purge 2>/dev/null || true
rm -rf /root/.cache/ms-playwright
rm -rf /root/.cache/code-server
rm -rf /root/.cache/chromium
rm -rf /root/.npm/_cacache
```

**لا تحذف** `/root/.config` أو `~/.local/share/opencode` أو مشاريعك. راجع قبل الحذف.

### 10.3 إعدادات الأداء

- `GALLIUM_DRIVER=llvmpipe`.
- `xfwm4` compositing = false.
- دقة 1280×2260 بدل الدقة الأصلية الأعلى إذا كان llvmpipe بطيئًا.
- `termux-wake-lock`.
- تعطيل battery optimization.
- تعطيل phantom process killer.
- لا تشغّل خادم PulseAudio داخل أوبنتو.
- لا تشغّل sshd/cron/avahi إذا لم تحتجها.
- لا تثبّت `fonts-noto` الكامل ولا `fonts-noto-cjk` إلا للحاجة.
- لا تثبّت Node مرتين.
- لا تثبّت `tigervnc`/`xvfb` إذا كان Termux:X11 يعمل.
- لا تضع `--disable-gpu` في Chromium إلا عند crash.

### 10.4 حدود "نفس الأداء"

- **نفس الجهاز + نفس Android + نفس الدقة:** النتيجة قريبة جدًا إذا اتبعت الإعدادات.
- **جهاز جديد بنفس المعالج والرام:** قريبة، لكن إصدار Android وthermal يغيران النتيجة.
- **جهاز أضعف:** استخدم ملف Light، خفّض الدقة، ولا تشغّل Antigravity + Chromium معًا.
- **معالج مختلف (x86/ARM32):** لا يمكن استخدام نفس rootfs والحزم.

---

## 11. الأخطاء الشائعة وحلولها

| العرض | السبب المرجح | الحل |
|---|---|---|
| `glxinfo` يفشل أو `virgl` | `GALLIUM_DRIVER=virgl` و`/dev/dri` غير متاح | `GALLIUM_DRIVER=llvmpipe` |
| `failed to create drisw screen` | `LIBGL_ALWAYS_SOFTWARE=1` | أزل المتغير |
| لا صوت | `PULSE_SERVER` مكرر أو سوكت غير موجود | سطر واحد: `tcp:127.0.0.1:4713` |
| الصوت يعمل ثم ينقطع | خادم PulseAudio داخل أوبنتو | عطّل autostart المحلي |
| الحروف العربية لا تُكتب | `GTK_IM_MODULE=ibus` بدون ibus | `unset GTK_IM_MODULE` |
| العربية تظهر بمربعات | لا يوجد fallback عربي | `fonts-noto-core` + `fonts.conf` |
| Chromium شاشة بيضاء | sandbox/proot أو `/dev/shm` | `--no-sandbox --disable-dev-shm-usage` |
| Chromium crash | GPU process | أضف `--disable-gpu` مؤقتًا |
| Antigravity لا يفتح | sandbox/GPU | استخدم `antigravity-ide-launch` |
| `sshd` يفشل | `/run/sshd` غير موجود أو منفذ مزدوج | `mkdir -p /run/sshd`، ومشغّل واحد فقط |
| `apt` يفشل | DNS أو مساحة أو قفل | `apt update`، `df -h`، `dpkg --configure -a` |
| `Permission denied` في `/dev/shm` | مجلد shm بصلاحيات خاطئة | `chmod 1777 ubuntu22-shm` |
| أسفل الشاشة مخفي | شريط مفاتيح Termux:X11 | `fix-screen.sh` |
| `freebuff` لا يعمل | wrapper يشير إلى binary غير موجود | أعد تثبيته أو احذفه |
| `code-server` لا يعمل | لم يُثبّت/لم يُشغّل | ثبّته وشغّله يدويًا؛ ليس خدمة تلقائية |
| `netstat/ps` غريب | `/proc` وهمي | لا تعتمد عليه؛ استخدم `ss` أو أدوات Android |

---

## 12. سيناريوهات "ماذا لو"

### 12.1 حُذف Termux ولم توجد نسخة احتياطية

- بيانات `/data/data/com.termux` ذهبت. لا يمكن استرجاعها إلا من نسخة Android سابقة.
- ابدأ من الصفر: Section 3 ثم 4.
- أعد تسجيل الدخول في opencode/Antigravity/GitHub.
- لا تحاول استعادة ملفات من `/sdcard` إلا إذا كنت قد نسختها فعلًا.

### 12.2 جهاز جديد بنفس المعمارية

- استخدم البناء النظيف (C).
- انسخ فقط: المشاريع، `~/.config/xfce4`، `~/.config/fontconfig`، `~/.config/opencode`، `~/.ssh`، `~/.gitconfig`، والسكربتات.
- لا تنسخ `/opt` ولا الحزم؛ أعد تثبيتها.
- اضبط الدقة/DPI حسب شاشة الجهاز الجديد.

### 12.3 جهاز جديد بمعمارية مختلفة

- `uname -m` في Termux.
- إن كان `aarch64`: استخدم rootfs arm64.
- إن كان `armv7l`: تحتاج rootfs armhf وتطبيقات أقل.
- إن كان `x86_64`: تحتاج rootfs amd64، وغالبًا Termux نفسه مختلف (Termux x86 غير رسمي).

### 12.4 Android يقتل العمليات

- طبّق أوامر phantom process.
- ثبّت `termux-wake-lock`.
- اجعل البطارية غير مقيدة.
- لا تفتح تطبيقات ثقيلة أثناء البناء.

### 12.5 مساحة غير كافية

- احذف كاشات أولًا.
- احذف `/opt/antigravity` القديم و`freebuff` و`~/.nvm` إن لم تحتجها.
- لا تحذف `ubuntu22-fs` قبل نسخه.
- استخدم `du -xhd1 /` و`du -xhd1 /root` لمعرفة المستهلك.

### 12.6 تريد الرجوع للنسخة القديمة

- أوقف الجلسة.
- أعد تسمية `ubuntu22-fs` إلى `ubuntu22-fs.bak`.
- استعد النسخة الاحتياطية.
- لا تستخرج فوق مجلد يعمل.

---

## 13. الفروق بين النسخة الحالية والبناء النظيف

| النقطة | الحالي | البناء النظيف |
|---|---|---|
| Ubuntu | 22.04.5 | 22.04.5 |
| XFCE | 4.16 + goodies | Light أو Parity حسب اختيارك |
| Chromium | 149.0.7827.114 من xtradeb | أحدث من نفس PPA |
| Mesa | 23.2.1 + llvmpipe | نفس المصدر |
| الصوت | Termux Pulse + TCP، لكن `proot-fixes` يعطله | TCP صريح، بلا تكرار |
| GL | `virgl` في `proot-fixes` (يفشل) | `llvmpipe` فقط |
| العربية | DejaVu + `GTK_IM_MODULE=ibus` | Noto Arabic + XKB + بلا ibus |
| Node | v20 (nvm) + v22 (NodeSource) | واحد فقط |
| الخدمات | cron يعمل، sshd فاشل، avahi متوقف | لا خدمات إلا عند الحاجة |
| الحجم | 1203 حزمة + كاشات | ~حزم أقل، بلا CJK/Qt/VNC غير الضرورية |
| الحزم القابلة للحذف | 163 | تبدأ نظيفة |
| التحديثات | 131 غير محدثة | محدثة عند البناء |

**ملاحظة:** البناء النظيف ليس "نسخة من الحالي"؛ هو **نسخة مصححة**. إذا أردت نفس الحالي حرفيًا، استخدم المسار A.

---

## 14. الملفات والمراجع

- `termux-system-baseline-and-additions.md` — الجرد الكامل والتصنيف.
- `rebuild/manifests/ubuntu-manual.txt` — 201 حزمة manual من النسخة الحالية (للتدقيق، لا تثبتها كلها).
- `rebuild/manifests/termux-packages.txt` — حزم Termux.
- `rebuild/manifests/pip-freeze.txt` — 146 حزمة Python.
- `rebuild/manifests/npm-global.txt` — حزم npm global.
- `rebuild/manifests/versions.txt` — الإصدارات المرجعية.
- `rebuild/scripts/start-ubuntu22.sh` — مشغّل proot المصحح.
- `rebuild/scripts/launch-ubuntu.sh` — تشغيل Pulse + Termux:X11 + proot.
- `rebuild/scripts/proot-fixes.sh` — متغيرات الجلسة المصححة.
- `rebuild/scripts/fix-screen.sh` — إصلاح شريط Termux:X11.
- `rebuild/scripts/chromium-wrapper` — تشغيل Chromium.
- `rebuild/scripts/antigravity-ide-launch` — تشغيل Antigravity IDE.
- `rebuild/scripts/antigravity-ide-handler` — handler.
- `rebuild/scripts/arabic-keyboard.desktop` — تخطيط us,ara.
- `rebuild/scripts/fonts.conf` — fallback عربي.

---

## 15. قرارك

- **تريد نفس النظام حرفيًا؟** استخدم A (نسخة `ubuntu22-fs` + السكربتات).
- **تريد نفس البرامج بخفة وأمان؟** استخدم C (rootfs نظيف + إعداداتك).
- **تريد أقل حجم وأقل أعطال؟** استخدم B (Light) وأعد تثبيت التطبيقات عند الحاجة فقط.

في كل الحالات: **لا تشغّل `ubuntu22-xfce.sh` القديم**، ولا تنسخ `proot-fixes.sh` القديم، ولا تضع `GTK_IM_MODULE=ibus`، ولا تكرر `PULSE_SERVER`، ولا تستخدم `virgl`.
