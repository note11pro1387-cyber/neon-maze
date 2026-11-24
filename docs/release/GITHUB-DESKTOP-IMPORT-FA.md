# انتقال کامل NEON MAZE V2 به GitHub Desktop

این ZIP یک **Repository Root Snapshot** است: پس از Extract، محتویات همین پوشه باید در ریشهٔ Working Directory شاخهٔ `v2/full-source` قرار بگیرند. پوشهٔ واسط `project/` وجود ندارد.

## فایل‌ها و دارایی‌های داخل بسته

- سورس کامل Android در `app/` و موتور مستقل بازی و تست‌های Core در `core/`
- تنظیمات ریشه و ماژول‌های Gradle، Launcherهای `gradlew` و `gradlew.bat` و Bootstrap رسمی Wrapper
- منابع فارسی و انگلیسی، آیکون‌ها، متریال Runtime و PNG منبع طراحی
- اسناد Gameplay، Art Direction، Motion، Release و ساخت/نصب
- Workflow ساخت Debug APK در `.github/workflows/build-apk.yml`
- `.gitignore` و `SOURCE-MANIFEST-SHA256.txt`
- اسکریپت محافظت‌شدهٔ PowerShell برای اعمال Snapshot در مخزن محلی GitHub Desktop

## روند ایمن

1. در GitHub Desktop مخزن `neon-maze` را باز کن و Fetch Origin بزن.
2. شاخهٔ `v2/full-source` را از `main` بساز و روی همان شاخه Checkout کن. `main` را تغییر نده.
3. ZIP را در یک پوشهٔ موقت Extract کن.
4. PowerShell را از ریشهٔ پوشهٔ Extract‌شده باز کن.
5. اسکریپت زیر را اجرا کن و مسیر Working Directory مخزن GitHub Desktop را به آن بده:

```powershell
.\tools\git\Apply-V2-Repository.ps1 -RepositoryPath "C:\PATH\TO\neon-maze"
```

اسکریپت قبل از تغییر، Branch را دقیقاً با `v2/full-source` تطبیق می‌دهد، Working Tree باید تمیز باشد، Manifest و SHA-256 همهٔ فایل‌های ورودی را اعتبارسنجی می‌کند، فایل‌های Tracked قدیمی V1 را که در V2 وجود ندارند حذف می‌کند، سپس تمام فایل‌ها را کپی و Hash مقصد را دوباره بررسی می‌کند. پوشهٔ `.git` را هرگز کپی، حذف یا بازنویسی نمی‌کند.

6. در GitHub Desktop تب Changes را بررسی کن. Commit message پیشنهادی:

`Publish complete NEON MAZE V2 source — Parts 1, 2, 3 and 5`

7. Commit to `v2/full-source` و سپس Push origin را انجام بده.
8. در GitHub Actions اجرای `Build APK` همان شاخه را بررسی کن. فقط بعد از موفقیت `:core:test`, `:app:lintDebug` و `:app:assembleDebug`، Artifact واقعی APK را دانلود کن.

## Git Metadata و موارد عمداً خارج از ZIP

این بسته شامل `.gitignore`، GitHub Actions Workflow، Manifest هش و اسناد نسخه است؛ اما پوشهٔ داخلی `.git/` را شامل نمی‌شود. `.git/` دیتابیس خصوصی Working Copy و تاریخچه/Remote محلی همان Checkout است. جایگزین‌کردن آن با `.git` یک ZIP دیگر می‌تواند شاخه‌ها، Remote و تاریخچهٔ مخزن واقعی را خراب کند. GitHub Desktop باید تاریخچهٔ مخزن متصل را حفظ کند.

همچنین APK، Keystore امضای انتشار، Token، فایل‌های محلی Gradle Cache و خروجی‌های موقت Build داخل Snapshot سورس نیستند. APK باید از Workflow ساخته شود و Keystore خصوصی نباید در مخزن عمومی قرار بگیرد.

## وضعیت اعتبارسنجی

این بسته Source Snapshot است، نه APK از پیش ساخته‌شده. وجود Workflow به معنی موفقیت Build نیست. Build واقعی و Gameplay QA روی گوشی باید جداگانه بررسی شوند.
