# NEON MAZE — گزارش تحویل فاز ۰۲

**وضعیت:** پیاده‌سازی فاز ۰۲ در Working Tree تکمیل شده؛ تحویل برای بازبینی مالک. فاز ۰۳ هنوز مجاز به شروع نیست.

## ۱. هدف و مبنای اجرا

این فاز روی سورس تجمیع‌شدهٔ اصلی Part01 تا Part08 اجرا شده است. Candidateهای AIهای قبلی و Patchهای فرعی وارد سورس فعال نشده‌اند و برای مهندسی معکوس بعد از تکمیل فازهای اصلی محفوظ می‌مانند.

هدف محصول، APK قابل‌نصب مستقیم برای مسابقهٔ خصوصی است؛ نه انتشار فروشگاهی. سورس باید از GitHub Actions قابل Build باشد، اما بازی نباید برای اجرای اصلی به اینترنت وابسته باشد.

برای Patch قابل‌اعمال، چون ZIP ورودی Git HEAD معتبر و قابل بازیابی نداشت، یک Baseline مصنوعی از درخت بازسازی‌شدهٔ پذیرفته‌شدهٔ Part01–Part08 ساخته شد: `93a1b6b00a73a4039906db32cf1d14a108ad0d5d`. این Tree SHA فقط هویت Snapshot مهندسی است و به‌عنوان Commit اصلی پروژه ادعا نمی‌شود.

## ۲. تصمیم‌های معماری تثبیت‌شده

1. **Offline-first:** منطق بازی، پنج Level، UI، فونت لازم و منابع حیاتی باید در خود APK باشند.
2. **Optional online content:** در اولین اجرا پس از اعتبارسنجی منابع APK، در صورت پیکربندی Manifest عمومی HTTPS، نسخهٔ Content Pack بررسی می‌شود. نبود URL یا اینترنت، بازی را متوقف نمی‌کند.
3. **Data-only pack:** بسته فقط تصویرهای PNG/WebP/JPEG و فایل metadata ریشه را می‌پذیرد. هیچ کد اجرایی، DEX/JAR/SO، اسکریپت، فونت، صدا، XML، JSON یا HTML از شبکه دریافت یا اجرا نمی‌شود.
4. **No embedded secrets:** URL عمومی محتوا، در صورت استفاده، از Gradle Property یا Environment در زمان Build می‌آید. PAT، Token و Keystore داخل APK یا سورس قرار نمی‌گیرند.
5. **No store-specific architecture:** Google Play، Play Asset Delivery، حساب کاربری، Backend و امضای فروشگاهی خارج از Scope مسابقه‌اند.
6. **No file-count ceiling:** سقف‌های قدیمی Class A/B با تصمیم جدید مالک superseded هستند. تعداد فایل تابع مرزبندی و کیفیت معماری است.

## ۳. تغییرات پیاده‌سازی‌شده

### تنظیمات و صدا

- `LocalStore.Settings` اکنون `volumePercent` را با بازهٔ ۰ تا ۱۰۰ نگه می‌دارد؛ سازندهٔ قدیمی هفت‌پارامتری برای سازگاری حفظ شده و مقدار پیش‌فرض Volume برابر ۸۰ است.
- مقدار Volume در SharedPreferences ذخیره و هنگام Load به بازهٔ معتبر محدود می‌شود.
- `SettingsScreen` اسلایدر Volume واقعی دارد؛ حین حرکت Preview اعمال می‌شود و هنگام Commit مقدار پایدار می‌شود.
- `FeedbackSystem` منحنی Gain نرم دارد و Volume روی AudioTrackهای فعال و Trackهای تازه‌ساخته‌شده اعمال می‌شود.
- Reset تنظیمات، SharedPreferences را پاک می‌کند و در بارگذاری بعدی Defaultها از جمله Volume=80 بازمی‌گردند.

### Startup و Bootstrap

- `StartupContentManager` منابع اجباری را در Worker بررسی می‌کند، فونت‌ها را توسط Android Typeface loader می‌گشاید، Catalog پنج Level را می‌سازد و `LevelValidator` را روی هر پنج Level اجرا می‌کند.
- `StartupSessionViewModel` کار Startup را در تغییر پیکربندی Activity حفظ می‌کند.
- `StartupScreen` وضعیت‌های آماده‌سازی، بررسی آنلاین، دانلود، Verify، Install، Retry و ادامهٔ آفلاین را نمایش می‌دهد. مراحل بدون درصد واقعی به‌صورت Indeterminate نشان داده می‌شوند.
- ساخت Catalog و بررسی فایل‌ها/شبکه روی Main Thread انجام نمی‌شود.
- `MainActivity` تا آماده‌شدن پیش‌نیازها UI اصلی را نمی‌سازد؛ پس از آماده‌شدن از Catalog و Content Resolver تأییدشده استفاده می‌کند.

### محتوای آنلاین و نصب اتمیک

- `ContentPackInstaller` Manifest و Archive را فقط از HTTPS و با Redirectهای HTTPS-only می‌گیرد.
- سقف‌ها: Manifest=16KiB، Archive=32MiB، دادهٔ بازشده=64MiB، هر فایل=16MiB، حداکثر ۱۲۸ فایل عادی (با metadata) و ۲۵۶ ZIP entry با احتساب Directory entry؛ تصویر حداکثر ۱۶٬۳۸۴ پیکسل در هر ضلع و ۶۴ مگاپیکسل است.
- SHA-256 بسته با Manifest تطبیق داده می‌شود؛ کلیدهای Properties دقیق و بدون کلید تکراری/ناشناخته‌اند.
- پس از تطبیق امضای تصویر و ابعاد، Decode نمونه‌برداری‌شدهٔ محدود انجام می‌شود تا دادهٔ ناقص/خراب پیش از فعال‌سازی رد شود. مسیرهای Absolute، Drive، Backslash، Dot segment، Duplicate و Path traversal رد می‌شوند.
- تنها `pack.properties` در ریشه مجاز است؛ تصویرها باید امضای باینری سازگار با پسوند داشته و ابعاد حداکثر ۱۶٬۳۸۴ در هر ضلع و ۶۴ مگاپیکسل داشته باشند.
- استخراج در App-private staging انجام می‌شود؛ نسخهٔ فعال با Pointer اتمیک تغییر می‌کند و دقیقاً نسخه‌ای که پیش از تعویض Pointer فعال بوده حفظ می‌شود. پوشه‌های orphan حاصل از توقف ناگهانی نباید جای نسخهٔ قبلی واقعی را بگیرند.
- با قطع فرایند، فایل‌های موقت/باقی‌مانده در شروع بعدی پاک‌سازی می‌شوند. نصب خراب نباید نسخهٔ فعال قبلی را از بین ببرد.
- نسخهٔ محتوای Immutable است؛ انتشار همان شماره نسخه با Hash یا compatibility metadata متفاوت رد می‌شود.
- `ContentAssetResolver` تنها تصویرهای مجاز را از Pack فعال می‌خواند و در صورت نبودن، به APK Assets برمی‌گردد.
- مصرف‌کنندهٔ واقعی فعلی `ui/home-hero.webp` در Home است. تا وقتی تصویر واقعی در Pack قرار نگیرد، بخش تصویر پنهان می‌ماند و بازی کامل اجرا می‌شود.

### فونت و منابع

- دو وزن ثابت Vazirmatn Regular و Bold هنگام Build از Release نسخهٔ ثابت v33.003 دریافت می‌شوند؛ SHA-256 آرشیو پیش از استخراج کنترل می‌شود.
- مسیر ورودی محلی برای توسعه آفلاین از `neonMazeVazirmatnDir` یا `NEON_MAZE_VAZIRMATN_DIR` وجود دارد و اکنون به‌عنوان Task Input ثبت می‌شود تا تغییر فونت محلی Build cache را باطل کند.
- Redirectهای دانلود فونت دستی کنترل می‌شوند و هر Redirect باید HTTPS بماند.
- فایل‌های فونت باینری عمداً داخل سورس/پچ قرار نگرفته‌اند؛ Build آن‌ها را در `build/generated` تولید و داخل APK بسته‌بندی می‌کند. در نخستین Build آنلاین، دریافت Release رسمی لازم است؛ برای Build آفلاین باید دو فایل معتبر در مسیر محلی معرفی‌شده با Gradle Property/Environment وجود داشته باشند.
- متن مجوز SIL Open Font License 1.1 در `app/src/main/assets/fonts/OFL.txt` قرار دارد.
- Typefaceها در Worker پیش‌بارگذاری می‌شوند؛ در خطای موقت، نتیجهٔ شکست Cache نمی‌شود تا Retry بتواند دوباره تلاش کند.
- ۳۰۸ String Key برای هر زبان فارسی و انگلیسی موجود است و Placeholderها برابرند.

### ابزار تولید Content Pack

`tools/content-pack/create_content_pack.py` با Python Standard Library، ZIP قطعی و Manifest Properties تولید می‌کند؛ URL، نوع واقعی تصویر، Symlink، مسیر، حجم، تعداد فایل و مسیر خروجی را کنترل می‌کند.

## ۴. فایل‌های اصلی تغییرکرده/اضافه‌شده

- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/neonmaze/app/LocalStore.java`
- `app/src/main/java/com/neonmaze/app/FeedbackSystem.java`
- `app/src/main/java/com/neonmaze/app/SettingsScreen.java`
- `app/src/main/java/com/neonmaze/app/MainActivity.java`
- `app/src/main/java/com/neonmaze/app/ScreenUI.java`
- `app/src/main/java/com/neonmaze/app/UiKit.java`
- `app/src/main/java/com/neonmaze/app/HomeScreen.java`
- `app/src/main/java/com/neonmaze/app/StartupContentManager.java` (جدید)
- `app/src/main/java/com/neonmaze/app/StartupScreen.java` (جدید)
- `app/src/main/java/com/neonmaze/app/StartupSessionViewModel.java` (جدید)
- `app/src/main/java/com/neonmaze/app/ContentPackInstaller.java` (جدید)
- `app/src/main/java/com/neonmaze/app/ContentAssetResolver.java` (جدید)
- `app/src/main/assets/fonts/OFL.txt` (جدید)
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-fa/strings.xml`
- `settings.gradle` (حذف قید قدیمی تعداد فایل از Comment)
- `docs/release/CONTENT-PACK-FORMAT.md` (جدید)
- `tools/content-pack/create_content_pack.py` (جدید)

## ۵. آنچه هنوز فعال/کامل نشده است

- هیچ Content Manifest URL واقعی در سورس پیکربندی نشده است؛ چون مخزن/میزبان مسابقه و آدرس واقعی ارائه نشده و URL ساختگی مجاز نیست. با URL عمومی معتبر، سازوکار Online فعال می‌شود. در حال حاضر APK بدون URL از منابع داخلی شروع می‌شود.
- فایل تصویر `ui/home-hero.webp` فعلاً تأمین نشده؛ Resolver و مصرف‌کننده آماده‌اند و نبود تصویر خطا ایجاد نمی‌کند.
- هنوز تست Runtime واقعی Installer روی Android انجام نشده است؛ ابزار تولید Pack در Python آزمایش شده است، ولی Android Installer نیازمند Build/Device در فاز ۰۳ است.
- Build APK، Lint رسمی، تست‌های Unit/Integration/UI/E2E، تست Offline/Online روی دستگاه و پروفایل Performance در فاز ۰۳ هستند.
- Gradle Wrapper و GitHub Actions هنوز در فاز ۰۳ ایجاد خواهند شد.
- دو ZIP نهایی Developer Handoff و GitHub-ready فقط در فاز ۰۴ ساخته می‌شوند.

## ۶. امنیت و محدودیت‌های صریح

- Content Pack v1 امضای دیجیتال مستقل ندارد؛ HTTPS + SHA-256 صحت انتقال را نسبت به Manifest می‌سنجد، اما اگر خود میزبان/Manifest مورد اعتماد تسخیر شود، منشأ محتوا اثبات نمی‌شود. این نسخه فقط برای مسابقهٔ خصوصی و دادهٔ غیرقابل‌اجراست.
- اگر دامنهٔ توزیع به افراد/میزبان‌های نامطمئن گسترش یابد، باید امضای Manifest با Public Key pin شده اضافه شود.
- هیچ Token، PAT، Keystore یا فایل محلی محرمانه در این فاز تولید یا تعبیه نشده است.
- مجوزهای Manifest فقط INTERNET و VIBRATE هستند؛ Cleartext غیرفعال است و مجوز Storage عمومی/Media درخواست نمی‌شود.

## ۷. شواهد و وضعیت کنترل‌ها

| کنترل | نتیجه | محدودهٔ اثبات |
|---|---|---|
| Java Parse با JDK 21 و `-source 17` | PASS — ۳۴ فایل، صفر خطای نحوی | Syntax فقط؛ نه Compile Android |
| Parse فایل‌های Android XML | PASS — پنج فایل | خوش‌ساختی XML |
| تطبیق منابع فارسی/انگلیسی | PASS — ۳۰۸ کلید در هر زبان | برابری Key و Placeholder |
| ارجاع `R.string` از Java | PASS — ۲۸۱ نام، صفر نام گم‌شده | فقط String reference |
| Python `py_compile` برای Pack Builder | PASS | Syntax ابزار |
| ساخت نمونه Content Pack با PNG معتبر | PASS | تولید ZIP و محاسبه Hash |
| تطبیق SHA-256 خروجی با Manifest | PASS | Hash archive |
| رد پسوند/امضای متناقض | PASS | Negative test ابزار Python |
| رد URL غیر HTTPS | PASS | Negative test ابزار Python |
| مجوزهای Android Manifest | PASS — INTERNET و VIBRATE | شمارش Manifest |
| فونت باینری در سورس | صفر فایل | فایل‌ها در Build تولید می‌شوند |
| Gradle Build / Android Lint / APK | NOT RUN | SDK و Gradle اجرایی در محیط فعلی نیست |
| Android Runtime / Device QA | NOT RUN | متعلق به فاز ۰۳ |

**تأکید:** PASSهای بالا تست جامع یا تأیید Runtime نیستند. هیچ Build یا APK موفق در این فاز ادعا نمی‌شود.

## ۸. نقطهٔ شروع فاز ۰۳

پس از تأیید مالک:

1. تثبیت Gradle Wrapper رسمی 9.6.0 و تأیید Hash باینری JAR.
2. ساخت `.gitignore` متناسب با Android/Java و حذف فایل‌های محرمانه/محلی از انتشار.
3. ساخت GitHub Actions برای JDK 17، Build Debug APK، Unit/Instrumentation/Lint و Artifact upload.
4. اجرای Build کامل و اصلاح تمام خطاهای واقعی compile/resource/manifest/lint.
5. اجرای ماتریس تست Core، Content installer، Persistence، lifecycle، permissions، Offline/Online، RTL، accessibility و performance.
6. تولید APK واقعی و شواهد قابل بازتولید از خروجی Workflow و دستگاه.

## ۹. قانون تحویل و تأیید

فاز ۰۲ از نظر پیاده‌سازی و کنترل‌های محدود آمادهٔ بازبینی است. تا تأیید صریح مالک، فاز ۰۳ شروع نمی‌شود. بعد از هر فاز، Patch و گزارش مستقل تحویل و Ledger به‌روزرسانی می‌شود.
