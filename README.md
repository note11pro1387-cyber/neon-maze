# NEON MAZE — Android Debug Build

این مخزن برای ساخت APK دیباگ بازی NEON MAZE و نصب مستقیم روی گوشی آماده شده است. انتشار Google Play، حساب کاربری، Backend و امضای Release در این نسخه لازم نیست.

## دریافت APK با GitHub Actions

1. محتوای این پوشه را در ریشهٔ یک GitHub repository قرار بده.
2. وارد زبانهٔ **Actions** شو و Workflow با نام **Build APK** را انتخاب کن.
3. گزینهٔ **Run workflow** را بزن؛ اجرای آن با `push` هم شروع می‌شود.
4. پس از سبزشدن همهٔ مراحل، در صفحهٔ اجرای Workflow به بخش **Artifacts** برو.
5. Artifact با نامی شبیه `neon-maze-debug-apk-1` را دانلود و ZIP آن را Extract کن.
6. فایل `app-debug.apk` را روی گوشی نصب کن. فایل `app-debug.apk.sha256` برای کنترل Hash همراه آن است.

در اولین اجرا، GitHub Actions فایل رسمی Gradle Wrapper را از سرور Gradle دریافت و قبل از استفاده با SHA-256 قفل‌شده اعتبارسنجی می‌کند. پس از آن Gradle 9.6.0 نیز با checksum تعریف‌شده در Wrapper دریافت می‌شود.

## نسخه و سازگاری

- Android Gradle Plugin: 9.4.0
- Gradle: 9.6.0
- JDK: 17
- Android SDK: API 36
- Minimum Android: API 24
- ماژول‌ها: `:app` و `:core`
- نوع خروجی: Debug APK، بدون نیاز به Keystore

## نصب روی Redmi Note 11 Pro

- APK را به گوشی منتقل و اجرا کن.
- در صورت نمایش هشدار Android/MIUI، اجازهٔ نصب برنامه از همان منبع فایل را فعال کن.
- پس از نصب، بازی را ابتدا یک بار در حالت آفلاین اجرا کن.
- چک‌لیست نصب و تست دستگاه در `docs/release/BUILD-AND-INSTALL-FA.md` قرار دارد.

اگر پیام `App not installed` یا `INSTALL_FAILED_UPDATE_INCOMPATIBLE` ظاهر شد، احتمالاً نسخهٔ قبلی با امضای متفاوت نصب است؛ قبل از نصب Debug APK جدید، نسخهٔ قبلی را حذف کن. حذف برنامه می‌تواند داده‌های محلی آن را پاک کند.

## وضعیت اعتبارسنجی

این بسته شامل سورس و GitHub Actions است؛ APK از پیش ساخته‌شده داخل ZIP نیست. Build واقعی باید در GitHub Actions اجرا شود. تا قبل از سبزشدن Workflow، تولید APK و نصب روی گوشی تأییدشده محسوب نمی‌شوند.


## Gradle Wrapper binary

The official `gradle-wrapper.jar` is intentionally not embedded in this archive.
The checked-in `gradlew` / `gradlew.bat` launchers first execute the platform-specific
bootstrap script under `tools/ci/`. That script downloads the Gradle 9.6.0 Wrapper JAR
from the official Gradle distribution host over HTTPS and accepts it only when its
SHA-256 equals the pinned value in `gradle/wrapper/gradle-wrapper.jar.sha256`.
The Gradle distribution ZIP is independently checked by the Wrapper using
`distributionSha256Sum` in `gradle-wrapper.properties`. First build therefore requires
network access to the official Gradle host and the configured Maven/Google repositories.

This is source/CI packaging, not a prebuilt APK. A successful GitHub Actions run is still
required before claiming Android compilation or APK production.
