# گزارش اصلاح زنجیرهٔ Build — NEON MAZE

تاریخ بسته‌بندی: 2026-11-23

## مرجع سورس

این بسته از درخت کامل `active-project` موجود در آرشیو تحویلی ساخته شده و فایل‌های Build/CI آن با وضعیت بررسی‌شدهٔ شاخهٔ `main` هم‌تراز شده‌اند. تغییرات صرفاً روی کپی کاری انجام شده‌اند؛ هیچ Commit، Push یا PR به مخزن راه دور ارسال نشده است.

Baseline مخزن هنگام بررسی:

`89fe60029ef1a7aa7188d28c6e85d72b8601dc3f`

## خطاهای قطعی هدف‌گیری‌شده

1. ورودی چندخطی `packages: |` در `android-actions/setup-android@v4.0.4` به یک ورودی واحد تبدیل می‌شد؛ چون کد همان نسخه رشته را با `split(' ')` جدا می‌کند. Workflow اکنون پکیج‌ها را با آرگومان‌های مستقل `sdkmanager --install` نصب می‌کند.
2. فراخوانی تک‌آرگومانی `onVariants {}` در Groovy با نسخهٔ AGP موجود در CI به خطای `Could not find method onVariants()` رسیده بود. اکنون Selector صریح `selector().all()` ارسال می‌شود.
3. callback مربوط به `addGeneratedSourceDirectory` قبلاً `it.outputs.files.singleFile` برمی‌گرداند. اکنون Task نوع‌دار و `DirectoryProperty` اختصاصی خود Task به API معرفی می‌شود.
4. مسیر قدیمی `sourceSets.main.assets.srcDir(Provider)` استفاده نمی‌شود؛ این مسیر در CI به‌علت ممنوعیت افزودن Provider به Android SourceSet شکست خورده بود.
5. Bootstrap Wrapper از POSIX `sh` استفاده می‌کند تا با نحوهٔ فراخوانی `gradlew` هماهنگ باشد. JAR فقط پس از دانلود HTTPS و تطبیق SHA-256 نصب می‌شود.

## Toolchain انتخاب‌شده

- AGP 9.4.0
- Gradle 9.6.0
- JDK 17
- Compile SDK Android 37.0
- Target SDK 36
- Min SDK 24
- Build Tools 36.0.0
- AndroidX Core 1.19.1
- Lifecycle ViewModel 2.11.1

Lifecycle به 2.11.0 تنزل داده نشده است. فونت‌ها به دلیل نبود فایل‌های باینری در سورس ارسالی، از آرشیو رسمی Vazirmatn v33.003 با SHA-256 اجباری تولید می‌شوند؛ حالت soft/غیرفعال برای کنترل Hash اضافه نشده است.

## دروازه‌های CI

ترتیب Workflow: نصب SDK، اعتبارسنجی Wrapper، `:core:test`، `:app:lintDebug`، `:app:assembleDebug`، اعتبارسنجی APK و سپس Upload. APK هنگام شکست تست یا Lint منتشر نمی‌شود. گزارش‌های تشخیصی در صورت شکست نیز جداگانه Upload می‌شوند.

## وضعیت آزمون در زمان ساخت این بسته

- ساختار کامل سورس از آرشیو بازیابی شد.
- بررسی ایستای فایل‌های XML، Shell و Python و تطبیق مسیرها در مرحلهٔ بسته‌بندی انجام می‌شود.
- Gradle/AGP واقعی در این محیط اجرا نشده است؛ بنابراین نتیجهٔ Core Tests، AAPT، Lint، Dependency Metadata و APK همچنان **تأییدنشده** است.
- GitHub Actions باید پس از جایگزینی سورس اجرا شود. موفقیت آن، شرط تحویل APK محسوب می‌شود.
- نصب روی دستگاه و آزمون Gameplay، ذخیره‌سازی و عملکرد، خارج از این بسته و در مرحلهٔ QA دستگاه است.

## بررسی GitHub Actions Run #17 — 2026-11-23

- Run ID: `45169342813`؛ Commit اجراشده: `cc53f4ad42adcab3ba6bfc8b2099c6fd4116fe70`.
- راه‌اندازی Runner، Checkout، نصب JDK 17، نصب Android SDK، Bootstrap و بررسی SHA-256 مربوط به Gradle Wrapper و اجرای Gradle 9.6.0 همگی موفق شدند.
- شکست در پیکربندی `:app` و فایل `app/build.gradle` خط ۴۳۳ رخ داد: `No signature of method: java.lang.String.positive()`. علت، شروع خط بعدی با `+` بود؛ Groovy آن را به‌عنوان unary plus روی مقدار String تفسیر می‌کرد.
- عبارت ساخت نام Variant اصلاح شد تا عملگر `+` در همان خطِ عبارت قبلی قرار بگیرد. این اصلاح صرفاً نحوی است و رفتار نام‌گذاری Variant را تغییر نمی‌دهد.
- چون پیکربندی پروژه شکست خورد، Core tests، Android Lint و APK assembly در این Run اجرا نشدند؛ APK نیز ساخته یا Upload نشد.
- این اصلاح در بستهٔ سورس فعلی اعمال شده است. موفقیت Build کامل هنوز تأیید نشده و به اجرای بعدی GitHub Actions وابسته است.
