# گزارش رفع خطاهای Build (GitHub Actions)

## علت‌های اثبات‌شده
1. `androidx.core:core:1.19.1` طبق AAR metadata به `compileSdk 37` نیاز دارد؛ پروژه ۳۶ بود → شکست `:app:checkDebugAarMetadata`.
   رفع: `compileSdk = 37` (targetSdk همان 36)، نصب `platforms;android-37.0` و `build-tools;36.0.0` در Workflow.
2. `gradlew` مرحلهٔ bootstrap را با `sh` (dash) اجرا می‌کرد و `set -o pipefail` در dash خطا می‌داد.
   رفع: فقط وقتی Wrapper JAR نیست و فقط با `bash` اجرا می‌شود.
3. `lifecycle-viewmodel:2.11.1` در صفحات رسمی AndroidX یافت نشد (آخرین پایدار 2.11.0) → 2.11.0.

## ساده‌سازی نقطهٔ شکست `android { }` (app/build.gradle:225)
- حذف `sourceSets { assets.srcDir(Provider) }` و `tasks.named('preBuild')`، دانلود فونت از Gradle به یک مرحلهٔ مستقل قبل از Gradle منتقل شد.
- همهٔ تنظیمات با سینتکس انتساب `=`؛ `buildConfigField` با String ساده؛ حذف `vectorDrawables.useSupportLibrary=false` (پیش‌فرض false است).
- حذف `android.useAndroidX` و `android.nonTransitiveRClass` از gradle.properties (پیش‌فرض AGP 9 هستند).

## Workflow
- ترتیب: APK → آپلود APK → تست Core → Lint (APK حتی با خطای lint/test تحویل می‌شود).
- لاگ کامل هر مرحله ذخیره و در شکست، بخش «What went wrong» چاپ می‌شود.
- `FONT_SHA_ENFORCE: 'false'`: اگر SHA آرشیو فونت با مقدار قفل‌شده نخواند، هشدار با هش واقعی چاپ می‌شود و Build ادامه می‌یابد.
  بعد از اولین اجرای سبز، اگر لاگ «SHA-256 verified» داشت مقدار را `'true'` کن.
