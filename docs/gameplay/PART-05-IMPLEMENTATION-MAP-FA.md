# Part 5 — نقشهٔ پیاده‌سازی، قراردادها و Acceptance

## فایل‌ها و مسئولیت‌ها

| فایل | تغییر Part 5 | مالکیت/قرارداد |
|---|---|---|
| `app/.../GameMotionSystem.java` | جدید | زمان‌بندی مستقل Presentation؛ بدون وابستگی Android/Engine |
| `app/.../GameRenderer.java` | Art Direction `PRISM CIRCUIT`، صحنهٔ چندلایه، تکسچر صنعتی Clip‌شده، Accent مستقل پنج Sector، ریل و شاسی، Floor Inlay، Rivet، Energy pulse، Glyphهای Start/Exit/Objective/Gate، Trail چندلایه، Marker Orb، ذرات Cue و پرتو Terminal | فقط Canvas؛ Paint/Path/Shader/Matrix reuse؛ هندسهٔ Collision منبع حقیقت |
| `app/src/main/res/drawable-nodpi/prism_floor.webp` | متریال سطح 640×640 اختصاصی؛ جزئیات فلز/پنل با کنتراست کنترل‌شده | فقط Surface Texture؛ نه Maze geometry و نه Hit/Collision map |
| `app/.../GameHudPanelDrawable.java` | Drawable پنل Instrument-like با Clip Corner، Gradient، Accent Rail و Stroke داخلی | Cache هندسه در onBoundsChange؛ بدون Layout/Canvas خارجی |
| `app/.../GameHudGlyphDrawable.java` | نمادهای معنایی Objective/Gate در HUD (diamond/check/warning/lock/open/closed) | Drawable سبک با Paint/Path ثابت؛ وضعیت فقط از Engine/HUD |

| `app/.../GameHud.java` | State Banner، Restart واقعی، Sector/Level title، Timer Condensed، Objective Progress Rail دوگانهٔ Mandatory/Optional، پنل‌های وضعیت و Layout تطبیقی Portrait/Landscape/Font Scale | نمایش فقط State از Engine؛ رنگ مشترک با Renderer؛ کنترل حداقل 48dp؛ محاسبهٔ inset واقعی Playfield |
| `app/.../GameView.java` | اتصال Cueها به Events/State؛ فریم Presentation در Pause/Terminal؛ Outcome یکتا | `Engine.onFrame` تنها در RUNNING؛ Choreographer موجود |
| `app/.../ScreenUI.java` | اعمال Reduced Motion؛ اتصال Restart؛ تأیید Exit؛ حذف پیام Resume غیرواقعی | مالک ناوبری و ذخیرهٔ نتیجه؛ بدون دستکاری نتیجهٔ Engine |
| `values*/strings.xml` | رشته‌های Sector و برچسب Start/Exit در هر locale | برابری کلیدها و Placeholderهای locale حفظ شود |

## قراردادهای معماری محافظت‌شده

1. `:core` بدون وابستگی به Android باقی می‌ماند.
2. `GameEngine` مالک موقعیت Marker، Collision، Timer، Objective، Gate، State و Outcome است.
3. Renderer از `outerBoundary` و `holeBoundaries`/ناحیهٔ برخورد واقعی استفاده می‌کند؛ `routeGraph` به‌عنوان جایگزین هندسهٔ قابل‌عبور رسم نمی‌شود.
4. HUD فقط getterهای Engine و LevelDefinition را می‌خواند؛ هیچ تایمر یا State موازی نمی‌سازد.
5. انیمیشن بر زمان monotonic استوار است؛ تعداد فریم، نرخ Refresh و `onDraw` نباید Game Logic را تغییر دهند.
6. در `doFrame`، `GameEngine.onFrame` فقط در RUNNING مجاز است. Pause و Terminal صرفاً Presentation را رندر می‌کنند.
7. هیچ Dependency جدید، سرویس خارجی، شبکه، Bitmap decode یا IO به حلقهٔ Draw اضافه نشده است.
8. نتیجهٔ Terminal پس از Cue یک بار تحویل می‌شود؛ Interrupted بدون تأخیر تحویل می‌شود.
9. Restart اجرای جدید با Engine جدید می‌سازد؛ نتیجهٔ Engine قبلی نباید به‌عنوان نتیجهٔ Run تازه ثبت شود.
10. Pause/Resume همان رفتار واقعی Engine را حفظ می‌کند؛ Resume از لمس Anchor می‌آید، نه دکمهٔ ساختگی.
11. وضعیت ذخیرهٔ نتیجه (Pending/Error/Unavailable/Retry/Success) در `ResultScreen` موجود می‌ماند؛ Motion/UX Gameplay آن را دوباره‌سازی نمی‌کند.
12. Audio/Haptics موجود در `FeedbackSystem` حفظ می‌شود؛ Cueهای دیداری P5 با Engine Event همگام‌اند و جایگزین آن نیستند.

## ماتریس پذیرش Part 5

| ID | معیار | روش اعتبارسنجی | وضعیت در این تحویل |
|---|---|---|---|
| P5-A01 | حالت Ready راهنمای شروع و Start/Exit قابل‌تشخیص دارد | بررسی کد/رشته‌ها | Implemented; static checked |
| P5-A17 | Playfield هویت هنری مستقل از کارت‌های اپلیکیشن دارد | Review Renderer/Art Direction | Implemented in source; visual device QA pending |
| P5-A18 | Board دارای لایه‌های Void، Chassis، Floor Inlay و Energy rail است | Review draw order/paths | Implemented; static review |
| P5-A19 | HUD از Instrument panels و Sector identity استفاده می‌کند | Review GameHud/Drawable | Implemented; static review |
| P5-A20 | Palette پایه بین Renderer و HUD یکسان است | Compare token constants | Implemented; static checked |
| P5-A21 | پنج Sector امضای نور محیطی متناسب با مضمون خود دارند | `levelAccent()` + shader rebuild guard | Implemented; javac/static checked |
| P5-A22 | پیشرفت هدف‌های اجباری/اختیاری از وضعیت واقعی Engine به صورت Rail قطعه‌ای دیده می‌شود | GameHud.update + ObjectiveProgressView | Implemented; Java parse/static checked |
| P5-A23 | متریال تکسچر فقط داخل Clip هندسهٔ واقعی برد رسم می‌شود و در مسیر Draw Decode ندارد | GameRenderer + GameView bitmap lifecycle | Implemented; static review; first-draw upload prewarmed |
| P5-A24 | HUD فشرده، Restart امن و Layout واقعی Portrait/Landscape دارد | LayoutParams و Insets در GameHud | Implemented in source; visual device QA pending |
| P5-A25 | HUD با Font Scale بزرگ در Portrait stack می‌شود و inset Playfield را متناسب می‌کند؛ Rail افقی نیز تطبیق می‌یابد | `fontScale`, `layoutHud()`, `playfieldTopInsetPx()` | Implemented in source; runtime readability unverified |
| P5-A02 | Timer/Objective/Gate با Engine همگام‌اند | مسیرهای getter و update | Implemented; static checked |
| P5-A03 | کنترل خروج دائمی، آشکار و تأییدشده است | GameHud/ScreenUI | Existing + preserved; static checked |
| P5-A04 | Pause/Resume دقیقاً مطابق قرارداد Engine است | Engine.pause/resume و HUD | Implemented; static checked |
| P5-A05 | Restart دکمهٔ واقعی و Listener متصل دارد | Listener chain و ScreenUI.restartCurrentRun | Implemented; static checked |
| P5-A06 | Objective/Key/Gate/Blocked cueهای مجزا دارند | Event dispatch و Motion state | Implemented; static checked |
| P5-A07 | هشدار نزدیک مرز دیداری و مستقل از صداست | updateNearBoundaryFeedback و Renderer | Implemented; static checked |
| P5-A08 | Terminal موفق/ناموفق یک Cue متفاوت و Outcome یکتا دارد | pendingOutcome/deliveredOutcome | Implemented; harness/static |
| P5-A09 | Reduced Motion تأخیر نتیجه و Cue را حذف می‌کند | GameMotionSystem و Settings binding | Implemented; harness/static |
| P5-A10 | Reduced Effects و High Contrast روی Renderer/HUD اثر می‌گذارند | setVisualOptions | Implemented; static checked |
| P5-A11 | Portrait/Landscape و Left-handed مسیر را نمی‌پوشانند | کد Insets/Layout | Implemented; device visual QA pending |
| P5-A12 | RTL/LTR رشته‌ها کلیدهای متناظر دارند | XML resource validation | PASS: کلیدها و Placeholderها در 359 رشتهٔ هر locale تطبیق دارند |
| P5-A13 | Draw/Frame allocations یا IO جدید ندارد | Review کد مسیر Draw/doFrame | Static review only |
| P5-A14 | TalkBack/Switch Access و خوانایی واقعی Font Scale روی دستگاه کاربردی‌اند | دستگاه واقعی | UNVERIFIED; layout adaptation is implemented but device verification is pending |
| P5-A15 | Frame pacing و مصرف حرارتی/باتری پذیرفتنی است | Profiler روی دستگاه | UNVERIFIED |
| P5-A16 | Android Build و APK موفق‌اند | Gradle/SDK | BLOCKED: wrapper JAR/Gradle/Android SDK در محیط حاضر نیست |

## محدودیت شواهد

این نقشه، قرارداد و وضعیت سورس را ثبت می‌کند؛ سند تأیید Build یا QA بصری نیست. Part 1 تا 3 دوباره اجرا نمی‌شوند. آزمون جامع Part 5 فقط پس از Freeze نهایی همین دور اصلاح، یک‌بار اجرا می‌شود. QA دستگاهی باید در محیط Android واقعی ادامه پیدا کند. ترتیب جاری به درخواست مالک `Part 1 → Part 2 → Part 3 → Part 5 → Part 4` است؛ Part 4 تا تأیید صریح مالک قفل می‌ماند.
