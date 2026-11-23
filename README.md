# NEON MAZE — Android Debug Build

این مخزن سورس کامل بازی اندرویدی NEON MAZE و Workflow ساخت Debug APK را نگه می‌دارد. پروژه دو ماژول دارد: `:app` برای برنامهٔ Android و `:core` برای منطق مستقل بازی.

## دریافت APK با GitHub Actions

1. کل محتوای این پوشه را در ریشهٔ مخزن GitHub قرار بده.
2. وارد زبانهٔ **Actions** شو و Workflow با نام **Build APK** را باز کن.
3. گزینهٔ **Run workflow** را اجرا کن؛ Push روی مخزن نیز Workflow را شروع می‌کند.
4. فقط وقتی Job با وضعیت موفق تمام شد، در صفحهٔ همان اجرا بخش **Artifacts** را باز کن.
5. Artifact با نامی شبیه `neon-maze-debug-apk-<run-number>` را دانلود و Extract کن.
6. فایل `app-debug.apk` را روی گوشی نصب کن. فایل `app-debug.apk.sha256` برای کنترل صحت فایل همراه آن است.

Workflow ابتدا JDK و Android SDK را آماده می‌کند، سپس Gradle Wrapper را با SHA-256 بررسی می‌کند، تست‌های Core و Android Lint را اجرا می‌کند و فقط پس از موفقیت آن‌ها APK را می‌سازد و منتشر می‌کند. گزارش‌های تشخیصی در صورت شکست نیز به‌صورت Artifact جداگانه بارگذاری می‌شوند.

## Toolchain قفل‌شده

- Android Gradle Plugin: `9.4.0`
- Gradle Wrapper: `9.6.0`
- JDK: `17`
- Compile SDK: Android API `37.0`
- Target SDK: `36`
- Build Tools: `36.0.0`
- Minimum SDK: `24`
- AndroidX Core: `1.19.1`
- Lifecycle ViewModel: `2.11.1`

نسخه‌ها را بدون بررسی سازگاری و اجرای کامل پذیرش تغییر نده.

## فونت و اجرای آفلاین

دو وزن Vazirmatn در زمان Build از آرشیو رسمی نسخهٔ `v33.003` دریافت می‌شوند. پیش از استخراج، SHA-256 آرشیو با مقدار pin‌شده در `app/build.gradle` مقایسه می‌شود؛ سپس فقط دو فایل TTF موردنیاز و با محدودیت اندازه و بررسی امضای TrueType به Assets تولیدشده اضافه می‌شوند. فایل مجوز `OFL.txt` در سورس باقی می‌ماند.

این مرحله به دسترسی شبکه در زمان Build نیاز دارد؛ اما فونت‌ها داخل APK بسته‌بندی می‌شوند و برای اجرای بازی روی دستگاه به دانلود جداگانه نیاز ندارند. در صورت داشتن دو فایل فونت معتبر، می‌توان مسیر پوشهٔ محلی را از طریق `NEON_MAZE_VAZIRMATN_DIR` یا Gradle property با نام `neonMazeVazirmatnDir` تنظیم کرد.

`NEON_MAZE_CONTENT_MANIFEST_URL` اختیاری است و به‌طور پیش‌فرض خالی می‌ماند؛ بازی برای شروع به Backend یا Content Pack آنلاین وابسته نیست.

## Gradle Wrapper binary

فایل باینری `gradle-wrapper.jar` عمداً در این بسته قرار نگرفته است. Launcherهای `gradlew` و `gradlew.bat` ابتدا Bootstrap متناسب با سیستم‌عامل را اجرا می‌کنند. Bootstrap، JAR رسمی Gradle 9.6.0 را از مخزن رسمی Gradle از طریق HTTPS می‌گیرد و فقط پس از تطبیق SHA-256 قفل‌شده آن را نصب می‌کند. خود توزیع Gradle نیز جداگانه با `distributionSha256Sum` در `gradle/wrapper/gradle-wrapper.properties` اعتبارسنجی می‌شود.

بنابراین اولین Build به اینترنت و دسترسی به میزبان رسمی Gradle و مخازن Maven/Google نیاز دارد. فایل Wrapper JAR را از منبع ناشناس یا با نام جعلی جایگزین نکن.

## اجرای محلی

Linux/macOS:

```sh
chmod +x gradlew tools/ci/bootstrap-gradle-wrapper.sh
./gradlew --no-daemon :core:test :app:lintDebug :app:assembleDebug
```

Windows PowerShell / CMD:

```powershell
.\gradlew.bat --no-daemon :core:test :app:lintDebug :app:assembleDebug
```

در اجرای محلی، Android SDK Platform `37.0` و Build Tools `36.0.0` باید نصب باشند.

## نصب روی دستگاه

- APK را به گوشی منتقل و نصب کن.
- در صورت نمایش هشدار Android/MIUI، اجازهٔ نصب برنامه از همان منبع فایل را فعال کن.
- پس از نصب، اجرای اولیه را یک بار در حالت آفلاین بررسی کن.
- چک‌لیست نصب و بررسی دستگاه در `docs/release/BUILD-AND-INSTALL-FA.md` قرار دارد.

اگر `App not installed` یا `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ظاهر شد، احتمالاً نسخهٔ قبلی با امضای متفاوت نصب است؛ پیش از نصب Debug APK جدید، نسخهٔ قبلی را حذف کن. حذف برنامه ممکن است داده‌های محلی آن را پاک کند.

## وضعیت اعتبارسنجی

این بسته سورس و CI را شامل می‌شود، نه APK از پیش ساخته‌شده. وجود فایل‌های پروژه یا تکمیل بررسی ایستای آن‌ها به معنی موفقیت کامپایل نیست. Build واقعی، AAPT، Lint و تست‌ها باید در GitHub Actions اجرا شوند؛ نصب و Gameplay روی دستگاه نیز مرحلهٔ QA جداگانه است.
