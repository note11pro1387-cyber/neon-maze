# NEON MAZE — وضعیت جاری ساختار و Build (بعد از Phase03-A و QA محدود Core)

> این فایل وضعیت جاری را ثبت می‌کند؛ اعداد و محدودیت‌های فایل‌محور Design Freeze قدیمی
> (مانند سقف Class A/B) با تصمیم جدید مالک که کیفیت/کامل‌بودن را بر تعداد فایل مقدم
> می‌داند، دیگر محدودیت اجرایی نیستند.

## هویت و هدف

- محصول: NEON MAZE، بازی Puzzle اندرویدی برای مسابقهٔ خصوصی بین دو سازنده.
- مقصد خروجی: APK قابل نصب مستقیم و Build بازتولیدپذیر از GitHub Actions.
- Google Play، انتشار عمومی، سیستم حساب/Backend و امضای فروشگاهی خارج از Scope هستند.
- بازی باید بدون اینترنت از منابع اصلی بسته‌بندی‌شده در APK کامل و قابل‌بازی باشد.
- بستهٔ محتوای آنلاین اختیاری است؛ تنها داده/تصویر مجاز دریافت می‌کند و هیچ کد اجرایی دریافت یا اجرا نمی‌کند.

## Baseline و زنجیرهٔ پیاده‌سازی

- Baseline کاری: سورس فعال پس از ادغام ترتیبی Patchهای اصلی Part01 تا Part08.
- ترتیب: FeedbackSystem → GeometryCollision → LevelSystem/Level01–03 → GameEngine/ReplaySystem → ProgressRules/DailyChallenge/Level04–05 → Gameplay decomposition → LocalStore/AvatarPipeline → Android Host/Navigation/Screens.
- Candidateهای ساخته‌شده توسط AIهای قبلی، Patch فرعی و آرشیوهای مقایسه‌ای، منبع تأییدشده نیستند؛ تا مهندسی معکوس پایانی جدا نگهداری می‌شوند.

## معماری

- `:core`: منطق بازی و قراردادهای مستقل از Android، Java خالص.
- `:app`: Android Host، UI، Lifecycle، Renderer/Input، ذخیره‌سازی و منابع.
- وابستگی یک‌طرفه: `:app` → `:core`.
- Android UI و شبکه روی Main Thread انجام نمی‌شوند؛ Startup و محتوای اختیاری در Worker اجرا می‌شوند.
- تنظیمات پایدار در LocalStore/SharedPreferences؛ داده‌های پروفایل/پیشرفت در SQLite.

## Phase02 — پیاده‌سازی تکمیل‌شده؛ گزارش و Patch برای بازبینی مالک آماده است

- افزودن `volumePercent` به `LocalStore.Settings` با سازگاری سازندهٔ ۷ پارامتری قدیمی؛ Preview/Commit صوت نیز واقعی است.
- اعمال واقعی Volume روی AudioTrack و Preview حین کشیدن SeekBar.
- `StartupContentManager`: کنترل منابع الزامی، ساخت/اعتبارسنجی Catalog پنج مرحله، سپس بررسی اختیاری Content Pack.
- `StartupSessionViewModel`: حفظ Startup session در تغییر پیکربندی Activity.
- `StartupScreen`: وضعیت آماده‌سازی، دانلود، Verify، Install، Retry و Continue Offline.
- `ContentPackInstaller`: HTTPS-only، اندازهٔ محدود، SHA-256، allow-list، اعتبارسنجی ZIP و مسیرها، استخراج به staging خصوصی، فعال‌سازی اتمیک و نگهداری نسخهٔ قبلی.
- `ContentAssetResolver`: اولویت نسخهٔ فعال و fallback به Assets APK.
- مصرف نمونهٔ واقعی محتوای اختیاری `ui/home-hero.webp` با Decode غیرهمزمان و downsampling.
- فونت Vazirmatn (v33.003، دو وزن ثابت + SIL OFL 1.1) پیش از اجرای Gradle با `tools/ci/fetch-vazirmatn-fonts.sh` در `app/src/main/assets/fonts/` آماده می‌شود (کنترل SHA-256 آرشیو، اندازه، Magic bytes). فایل‌های TTF در Git ثبت نمی‌شوند. ورودی آفلاین: `NEON_MAZE_VAZIRMATN_ARCHIVE`.
- اینترنت فقط برای به‌روزرسانی اختیاری؛ اگر URL تنظیم نشود، هیچ درخواست شبکه‌ای انجام نمی‌شود. بازی کامل از منابع APK قابل اجرا باقی می‌ماند.

## کنترل‌های Phase02 انجام‌شده

- ۳۴ فایل Java با Parser مبتنی بر JDK 21 و `-source 17` بدون خطای نحوی Parse شدند؛ این Compile اندروید نیست.
- پنج XML اندروید Parse شدند.
- ۳۰۸ کلید منابع فارسی و ۳۰۸ کلید انگلیسی با Placeholderهای متناظر یکسان‌اند.
- ۲۸۱ ارجاع Java به String Resource بررسی شد؛ نام گم‌شده وجود نداشت.
- Python Pack Builder با `py_compile` معتبر است؛ ساخت نمونهٔ PNG Pack، تطبیق SHA و رد URL غیر HTTPS/امضای تصویر متناقض کنترل شد.
- Build واقعی، Lint، تست Runtime و Device QA اجرا نشده‌اند و به Phase03 تعلق دارند.

## موارد بازِ واقعی برای خروجی قابل نصب

- اجرای واقعی Wrapper و GitHub Actions روی Runner آنلاین؛ Bootstrap با SHA رسمی آماده است، ولی Wrapper JAR هنوز در این محیط دریافت نشده.
- اجرای Gradle Build، Android Lint و تولید APK واقعی؛ در محیط حاضر Gradle و Android SDK وجود ندارند.
- بررسی APK تولیدشده و تطبیق package/minSdk/manifest.
- نصب واقعی روی Redmi Note 11 Pro و کنترل Startup آفلاین، Gameplay، Replay، Settings/Volume، Lifecycle، Crash، FPS و گرما.
- اصلاح فقط نقص‌های بازتولیدپذیر که در Build یا دستگاه آشکار شوند.
- منابع هنری تکمیلی و Launcher placeholder فقط در صورت اثبات تأثیر روی پذیرش خروجی بررسی می‌شوند؛ از گسترش Scope بدون نیاز پرهیز شود.

## تنظیم Content Pack

- Gradle Property: `-PneonMazeContentManifestUrl=https://.../manifest.properties`
- Environment: `NEON_MAZE_CONTENT_MANIFEST_URL`
- خالی باشد: بازی از APK شروع می‌شود و هیچ دانلودی ندارد.
- فرمت v1 در `docs/release/CONTENT-PACK-FORMAT.md` مستند است.
- فعلاً endpoint عمومی/ثابت برای مسابقه تنظیم نشده؛ از URL ساختگی استفاده نکنید.

## قفل‌های Scope و ضدفراموشی

- Phase01: ممیزی و Roadmap انجام و توسط عامل تأیید شد.
- Phase02: پیاده‌سازی و کنترل‌های ایستای محدود تکمیل شد؛ مالک دستور صریح شروع Phase03 را صادر کرده است. Build یا QA واقعی Phase02 همچنان ادعا نمی‌شود.
- Phase03: Part03-A در سطح فایل و CI bootstrap تکمیل است. B/C به‌صورت محدود انجام شده‌اند: ۶۶ تست Core با Runner سبک موفق و Parseهای Java/XML/YAML بدون خطا؛ Build واقعی و CI هنوز باید روی Runner آنلاین اجرا شوند. D تا نصب APK روی Redmi Note 11 Pro باز است.
- توالی مورد تأیید مالک: A نهایی → B و C با هم → D؛ بدون ممیزی یا بازطراحی تکراری.
- Phase04: بسته‌بندی نهایی فقط پس از دریافت شواهد Build و Device QA.
- تست جامع و Build نهایی به Phase03 تعلق دارد؛ کنترل‌های ایمن و بررسی ایستا در Phase02 مجازند.
- هیچ تست یا Build اجرا نشده را PASS گزارش نکن. شواهد Phase02 در `docs/release/PHASE02-REPORT-FA.md` و شواهد Phase03 در `docs/release/PHASE03-PARTA-REPORT-FA.md` و Ledger در `docs/release/PHASE-LEDGER.md` ثبت شده‌اند.
- تغییر پارت‌های قبلی خارج از Scope ممنوع است، مگر برای رفع یک نقص بازتولیدپذیر و مستند که مستقیماً Build، نصب، اجرا یا عملکرد را مختل می‌کند.

## Phase03-A — Build Foundation (فایل‌ها و تنظیمات نهایی)

- نسخه‌های قفل‌شده: AGP 9.4.0، Gradle 9.6.0، JDK 17، compile/target SDK 36، Build Tools 36.0.0.
- `.gitignore`، `gradlew`، `gradlew.bat` و `gradle-wrapper.properties` آماده‌اند.
- Hash رسمی Wrapper JAR برابر `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` و Hash توزیع Gradle برابر `bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01` قفل شده‌اند.
- چون JAR در بسته/Cache نبود و شبکهٔ مستقیم محیط اجرا غیرفعال بود، JAR ساختگی تولید نشده است. Bootstrapهای Bash و PowerShell آن را در اولین اجرا از HTTPS رسمی دریافت و پیش از نصب SHA-256 را بررسی می‌کنند.
- `.github/workflows/build-apk.yml` برای Core tests، Android Lint، Debug APK و Artifact افزوده شده است. در CI بیت اجرایی اسکریپت‌های POSIX نیز صریحاً تنظیم می‌شود.
- README و راهنمای نصب Redmi Note 11 Pro اضافه شده‌اند.

## Phase03-B + C — Build و QA محدود

- Compile هسته با `javac --release 17`: موفق.
- اجرای محدود تست‌های Core با JUnit-compatible shim: ۶۶ موفق، صفر ناموفق. این اجرا جایگزین Gradle/JUnit رسمی نیست.
- تمام ۳۵ فایل Java بدون خطای Parse؛ پنج XML، Workflow YAML و اسکریپت‌های Shell نیز Parse/Syntax شدند.
- اصلاحات محدود تست فقط در Fixture/انتظارهای Replay anchor، زمان اولین تماس Goal و حد هندسی Gate انجام شد؛ منطق تولیدی Core تغییر نکرد.
- Build واقعی، AAPT، Android Compile، Lint و تولید APK اجرا نشده‌اند؛ محیط حاضر Gradle، Android SDK و شبکهٔ دانلود ندارد.

## Phase03-D — تست Redmi Note 11 Pro

- هنوز اجرا نشده است؛ APK تولیدشده و گوشی فیزیکی در محیط تحلیل حاضر نیستند.
- راهنمای دقیق گرفتن APK از Actions و چک‌لیست نصب/تست در `docs/release/BUILD-AND-INSTALL-FA.md` قرار دارد.

**گزارش شواهد و محدودیت‌ها:** `docs/release/PHASE03-PARTA-REPORT-FA.md` و `docs/release/PHASE-LEDGER.md`.
