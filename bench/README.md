# DeckOS Bench — أدوات القياس

مجلد `bench/` يحتوي على سكربتات قياس الأداء لـ DeckOS Core.

## الأوامر

```bash
# قياس شامل (APK + Runtime + RAM + Startup)
./bench/run-all.sh

# قياس وقت البدء فقط (X11 + Desktop)
./bench/measure-startup.sh

# قياس RAM والحرارة (30 دقيقة)
./bench/measure-ram-heat.sh

# تقرير حجم APK والمكتبات
./bench/apk-size.sh

# تقليم الحزم وحذف الملفات غير المستخدمة
./bench/trim-runtime.sh --dry-run   # معاينة
./bench/trim-runtime.sh --apply     # تطبيق
```

## الميزانيات المستهدفة (Gate 3)

| المقياس           | الحد    | الحالة الحالية |
|-------------------|---------|----------------|
| APK               | ≤70 MB  | ~48 MB ✅       |
| Runtime on-disk   | ≤900 MB | ~1.4 GB ⚠️     |
| RAM (PSS)         | ≤250 MB | ~90 MB ✅       |
| Desktop startup   | ≤8 s    | <1 s ✅         |
| X11 startup       | baseline| TBD            |

## ملاحظات
- تُشغَّل السكربتات على الجهاز عبر ADB أو داخل bيئة Termux.
- جميع النتائج تُكتب في `bench/results/YYYY-MM-DD_HH-MM/`.
