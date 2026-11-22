# گزارش نهایی Part 03-A و وضعیت یکپارچهٔ B/C/D

## تصمیم‌های اجرایی مالک

طبق آخرین دستور مالک:

1. Part 03-A باید نهایی شود.
2. بررسی اولین Build و QA خودکار (B و C) به‌صورت یک جریان پیوسته انجام می‌شود.
3. پس از آن اعتبارسنجی محصول روی Redmi Note 11 Pro انجام می‌شود (D).
4. طراحی‌ها و قابلیت‌های قبلاً تأییدشده دوباره ممیزی یا بازطراحی نمی‌شوند؛ فقط مانع واقعی Build، نصب، اجرا یا عملکرد اصلاح می‌شود.
5. تعداد فایل‌ها محدودیت کیفیت نیست.

## زنجیرهٔ نسخهٔ Build

| مؤلفه | مقدار قفل‌شده | وضعیت بررسی |
|---|---:|---|
| Android Gradle Plugin | 9.4.0 | با جدول رسمی سازگاری تطبیق شد |
| Gradle | 9.6.0 | URL و SHA-256 در Wrapper قفل شد |
| JDK موردنیاز Build | 17 | در CI صریحاً نصب می‌شود |
| compileSdk / targetSdk | 36 / 36 | مطابق تنظیمات فعلی پروژه |
| Build Tools | 36.0.0 | در CI صریحاً نصب می‌شود |
| minSdk | 24 | دستگاه‌های Android 7.0+ را پوشش می‌دهد |
| ماژول‌ها | `:app`, `:core` | ساختار موجود حفظ شد |

AGP 9.4.0 به Gradle 9.6.0، JDK 17 و Build Tools 36.0.0 نیاز دارد؛ نسخه‌ها بدون ارتقای غیرضروری حفظ شده‌اند.

## Part 03-A — Build Foundation

### فایل‌های آماده‌شده

- `.gitignore`
- `.github/workflows/build-apk.yml`
- `gradlew` و `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar.sha256`
- `tools/ci/bootstrap-gradle-wrapper.sh`
- `tools/ci/bootstrap-gradle-wrapper.ps1`
- `README.md` و راهنمای Build/Install

### Wrapper JAR و تصمیم نهایی

Hash رسمی مورد انتظار Wrapper JAR:

`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`

Hash رسمی Gradle 9.6.0 Binary Distribution:

`bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01`

فایل `gradle-wrapper.jar` در آرشیو ورودی و Cache محیط حاضر وجود نداشت و شبکهٔ دانلود مستقیم محیط اجرا نیز در دسترس نبود. برای جلوگیری از درج باینری ساختگی، فایل JAR تولید یا جعل نشده است.

راهکار قابل‌اجرا در بسته:

- Bootstrap مخصوص Bash و PowerShell، JAR را از دامنهٔ رسمی Gradle با HTTPS دریافت می‌کند.
- SHA-256 قبل از نصب کنترل می‌شود؛ فایل ناسالم فعال نمی‌شود.
- `gradlew` در اولین اجرا Bootstrap را فراخوانی می‌کند.
- GitHub Actions نیز پیش از اجرای Wrapper همین دریافت و کنترل را انجام می‌دهد.
- پس از اولین اجرای موفق روی Runner یا سیستم دارای اینترنت، JAR رسمی در مسیر `gradle/wrapper/gradle-wrapper.jar` ایجاد می‌شود.

بنابراین A از نظر Launcher، properties، checksum pinning، Ignore و CI bootstrap نهایی شده است؛ باینری JAR در ZIP حاضر نیست، اما مسیر دریافت و اعتبارسنجی آن اجرایی و مستند است. دریافت واقعی JAR در این محیط قابل انجام نبود.

### Workflow

مسیر: `.github/workflows/build-apk.yml`

Workflow در `push`، `pull_request` و اجرای دستی فعال می‌شود و:

1. مخزن را Checkout می‌کند.
2. JDK 17 را نصب می‌کند.
3. Android SDK Platform 36، Build Tools 36.0.0 و Platform Tools را آماده می‌کند.
4. مجوز اجرای Launcherهای POSIX را صریحاً تنظیم می‌کند.
5. Wrapper JAR رسمی را دریافت و SHA-256 آن را کنترل می‌کند.
6. Gradle Wrapper را اجرا و نسخهٔ Gradle را کنترل می‌کند.
7. `:core:test`، `:app:lintDebug` و `:app:assembleDebug` را اجرا می‌کند.
8. APK را با `unzip -t` کنترل و SHA-256 آن را تولید می‌کند.
9. APK و گزارش‌های تست/Lint را به‌صورت Artifact نگه می‌دارد.

خروجی APK مورد انتظار:

`app/build/outputs/apk/debug/app-debug.apk`

نام Artifact:

`neon-maze-debug-apk-<run_number>`

Build نوع Debug است و به Keystore یا Secret نیاز ندارد. انتشار GitHub Release یا Play Store در Scope نیست.

## Part 03-B — First Real Build

### وضعیت این محیط

- JDK نصب‌شده در محیط تحلیل: 21؛ کامپایل Core با `--release 17` انجام شد.
- Gradle اجرایی نصب نیست.
- Android SDK، `android.jar`، AAPT2 و ابزارهای Build اندروید در محیط حاضر وجود ندارند.
- Wrapper JAR در ورودی یا Cache موجود نبود.
- دسترسی مستقیم شبکه برای دریافت ابزارها از محیط اجرا ممکن نبود.

بنابراین Gradle Sync، AAPT/Resource Linking، Android Compile، Lint و تولید APK در این محیط **اجرا نشده‌اند**. این موارد PASS فرض نشده‌اند.

## Part 03-C — QA محدود و مبتنی بر شواهد

### کنترل‌های انجام‌شده

| کنترل | نتیجه | تفسیر |
|---|---:|---|
| کامپایل Core با `javac --release 17` | PASS | کد اصلی Core روی API/bytecode سطح Java 17 کامپایل شد |
| آزمون‌های Core موجود | 66/66 PASS در Runner سبک | اجرای محدود با Shim سازگار با JUnit؛ جایگزین `:core:test` واقعی نیست |
| Parse تمام Javaها | 35 فایل، صفر خطای Parse | بررسی نحو؛ Compile کامل Android محسوب نمی‌شود |
| Parse منابع XML | 5 فایل، صفر خطا | بررسی ساختار XML |
| Parse Workflow YAML | PASS | فایل Workflow از نظر YAML قابل‌خواندن است |
| نحو Shell | PASS | `gradlew` و Bootstrap Bash با `bash -n` کنترل شدند |
| Wrapper checksum metadata | PASS | مقدار sidecar و properties با Hashهای قفل‌شده تطبیق داده شدند |

### اصلاحات واقعی انجام‌شده در تست‌ها

هیچ منطق تولیدی Core برای سبزکردن تست‌ها تغییر نکرد. ایرادها در Fixtureها و انتظارهای تست بودند:

1. ReplayTrace طبق قرارداد به نمونهٔ شروع در `t=0` نیاز دارد؛ سه Fixture بدون Anchor اصلاح شدند و یک آزمون منفی برای رد Trace بدون Anchor اضافه شد.
2. زمان پایان/امتیاز بر اساس اولین تماس swept-disk با Goal ثبت می‌شود، نه زمان انتهای Pointer sample؛ انتظارهای تست با زمان تماس واقعی هم‌راستا شدند.
3. انتظار تست Gate با هندسهٔ واقعی Level04 ناسازگار بود؛ اکنون موقعیت Marker در بازهٔ پیش از Barrier بررسی می‌شود.

نتیجهٔ Runner محدود پس از اصلاح: `passed=66 failed=0`.

**محدودیت:** چون Runner از Shim حداقلی JUnit استفاده می‌کند، این نتیجه فقط نشان می‌دهد بدنهٔ ۶۶ آزمون با این Runner و پیاده‌سازی فعلی Core موفق اجرا شده‌اند. موفقیت اجرای Gradle/JUnit واقعی، تست Android، تست Instrumentation یا UI را ثابت نمی‌کند.

## Part 03-D — Redmi Note 11 Pro

Device QA در این محیط قابل اجرا نیست؛ گوشی فیزیکی به محیط حاضر متصل نیست و APK نیز تا اجرای موفق GitHub Actions تولید نشده است.

پس از دانلود APK، راهنمای `docs/release/BUILD-AND-INSTALL-FA.md` مراحل نصب و چک‌لیست کوتاه روی گوشی را مشخص می‌کند. موارد اصلی: نصب تمیز، اولین Startup آفلاین، ورود به Home، اجرای Levelها، لمس/حرکت، Pause/Resume، Result/Replay، Settings/Volume، چرخش/بازگشت از پس‌زمینه و بررسی Crash/دما/مصرف.

## وضعیت صادقانهٔ تحویل

| خروجی | وضعیت |
|---|---|
| سورس بازسازی‌شده و تغییرات Part03 | آمادهٔ بسته‌بندی |
| Part03-A: فایل‌های Build Foundation و CI bootstrap | تکمیل در سطح فایل و اعتبارسنجی ایستا |
| Wrapper JAR واقعی | در ZIP نیست؛ Bootstrap با SHA آماده است |
| Core QA محدود | 66/66 با Runner سبک موفق |
| Gradle Build واقعی | اجرا نشده؛ محیط فاقد Gradle/Android SDK و شبکهٔ دانلود است |
| GitHub Actions Run | هنوز اجرا نشده |
| APK | هنوز تولید نشده |
| نصب/Device QA روی Redmi Note 11 Pro | پس از تولید APK و نصب واقعی باقی است |

**نتیجه:** بخش قابل‌انجام A و کنترل‌های محدود B/C در این محیط انجام شده‌اند. پروژه از نظر فایل‌های لازم برای شروع GitHub Actions آماده است، اما تا اجرای موفق Workflow و دریافت APK نمی‌توان آن را Build-successful یا آمادهٔ نصب اعلام کرد.
