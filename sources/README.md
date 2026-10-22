# مصادر DeckOS — لقطة مرجعية

> **تاريخ التجميد:** 2026-10-21  
> **الغرض:** مرجع محلي للدمج على مستوى المصدر. لا تُدمج APK‏ات، ولا تُنسخ هذه المجلدات كما هي إلى المستودع النهائي.

## المستودعات

| المشروع | الدور | الفرع | Commit | الترخيص | الحجم المحلي | طريقة الدمج |
|---|---|---|---|---:|---:|---|
| `DroidDesk` | مرجع سطح المكتب الأصلي (Termux native + X11 + AAudio/Turnip) | `main` | `a410944b15fce2fb136208ecdb06d49adba4eb2a` | GPL-3.0 | 67M | fork محدود للوحدات المفيدة، لا APK |
| `DroidDeck` | محرك الألعاب (Arch/proot/Steam/Proton/FEX/gamescope) | `main` | `0e7818049ab5ee0cf4409af0143c12ac98e6e1a8` | GPL-3.0 | 322M | حزمة ألعاب اختيارية لاحقًا، fork متعقّب |
| `termux-app` | الطرفية، `terminal-emulator`، `terminal-view`، bootstrap | `master` | `8629e632fcb95da272221be327db653fb24befe9` | GPL-3.0 (NOASSERTION في GitHub API) | 5.6M | وحدات Gradle أو fork محدود |
| `termux-x11` | خادم X11 (`lorie`) والواجهة | `master` | `5986cfc39458630dadd191735601cf2adc23a19f` | GPL-3.0 | 4.2M | fork/وحدة `lorie` داخل التطبيق |
| `Shizuku` | جسر صلاحيات الهاتف | `master` | `b844bc491f1790c72328e1a8e5b2349f8978f0ea` | Apache-2.0 | 3.0M | مرحلة لاحقة، UserService فقط |

## ملاحظات حرجة

- **DroidDeck** يحتوي `keystore/testkey.p12` عامًا، ويستخدمه في `debug` و`release`. لا يُعاد استخدامه. يجب توقيع كل builds بمفتاح DeckOS الخاص.
- **DroidDeck** بناءه ليس بناء APK بسيطًا: يعتمد على حزم `proot`/`directaudio`/`tracking`/`xbox`/`comet`، وأرشيف KDE، وSteam/Proton/FEX وقت التشغيل. لا يدخل في CI الأساسي.
- **DroidDesk** واجهته Flutter، لكن وحدات `runtime/` و`x11/` و`view/` و`jniLibs/` قابلة لإعادة الاستخدام. مسار non-root يعتمد `socket_hook` + `LD_PRELOAD`، ويحتاج مراجعة قبل الاعتماد.
- **termux-x11** استُنسخ بدون submodules. البناء يحتاج:
  ```bash
  git -C sources/termux-x11 submodule update --init --recursive --depth 1
  ```
- **Shizuku** يحتوي submodule `api` بعنوان SSH:
  ```bash
  git -C sources/Shizuku submodule set-url api https://github.com/RikkaApps/Shizuku-API.git
  git -C sources/Shizuku submodule update --init --recursive --depth 1
  ```
- **termux-app** يضمّن bootstrap عند البناء من `termux-packages` release، ويتحقق من SHA-256. لا نعتمد على testkey أو bootstrap غير موثّق.
- هذه المجلدات **مرجعية محلية**؛ لا تُضاف إلى Git. المستودع النهائي يستخدم submodules/forks مثبّتة على نفس commits أو أحدث بعد مراجعة.

## أوامر التحقق

```bash
for d in DroidDesk DroidDeck termux-app termux-x11 Shizuku; do
  printf '%-12s ' "$d"
  git -C "sources/$d" rev-parse HEAD
done
```

## ما لم يُحمَّل بعد

- `DroidDeck-Proton` (مستودع منفصل ثقيل).
- حزم DroidDeck (`proot`, `directaudio`, `tracking`, `xbox`, `comet`).
- أرشيف KDE/Steam/Proton/FEX.
- bootstrap‏ات Termux المتعددة.
- submodules الخاصة بـ`termux-x11` و`Shizuku`.

هذه تُجلب عند بناء الحزمة المعنية فقط، وليس في clone الأساسي.
