# Part 5 — کاتالوگ Motion و Storyboard اجرایی

**اصل معماری:** انیمیشن یک لایهٔ Presentation است. هیچ Cue نباید Collision، امتیاز، Timer، Replay یا State ماشین `GameEngine` را تغییر دهد.

## قرارداد اجرا

- این کاتالوگ، Motion دیداری را تعریف می‌کند. صدای رویداد و Haptics به `FeedbackSystem` موجود سپرده می‌شود؛ Part 5 آن را دوباره پیاده‌سازی نمی‌کند. Cue دیداری با همان Engine Event هم‌زمان می‌شود، اما خاموش‌کردن Audio/Haptics نباید هشدار دیداری یا معنای وضعیت را حذف کند.

- زمان‌بندی با `System.nanoTime()` در `GameMotionSystem` انجام می‌شود؛ فریم‌ها با `Choreographer` موجود `GameView` زمان‌بندی می‌شوند.
- `GameEngine.onFrame()` فقط هنگامی صدا زده می‌شود که State برابر `RUNNING` باشد؛ در `PAUSED` و Terminal، فریم‌های Presentation زمان بازی را جلو نمی‌برند.
- Cueهای کوتاه 520ms و Cue پایانی 720ms هستند. این مقدارها هدف طراحی/پیاده‌سازی‌اند و هنوز روی دستگاه اندازه‌گیری نشده‌اند.
- همهٔ Paint/Pathها در Renderer نگه‌داری و مجدداً استفاده می‌شوند. Surface texture یک Bitmap ثابت است که در زمان اتصال Engine به `RGB_565` Decode و تا عمر همان GameView reuse می‌شود؛ `prepareToDraw()` پیش از نخستین فریم فراخوانی می‌شود. Shaderهای پس‌زمینه هنگام اندازه‌گیری Surface یا تعویض Sector ساخته/به‌روزرسانی می‌شوند و `drawBackdrop()` فقط Shader آماده را مصرف می‌کند؛ هیچ ساخت Shader، IO، decode Bitmap، query هندسهٔ برخورد یا محاسبهٔ مسیر جدید در مسیر رسم انجام نمی‌شود.
- رخدادها از `GameEngine.drainEvents()` خوانده می‌شوند؛ تکرار فریم نباید Cue یا نتیجهٔ جدید بسازد.
- Interrupted، برخلاف Completed/Failed، Cue پایانی تأخیری ندارد.

## کاتالوگ انیمیشن

| Trigger | Cue | مدت | محل/لایه | رفتار | پایان/لغو |
|---|---|---:|---|---|---|
| اولین Start معتبر / ورود به RUNNING | `CUE_START` | 520ms | حلقهٔ اطراف Marker | حلقه با Ease-in-out گسترش و محو می‌شود | Cue بعدی جایگزین می‌کند؛ Reduced Motion حذف می‌کند |
| جمع‌آوری Objective | `CUE_OBJECTIVE` | 520ms | Marker در نقطهٔ رویداد | Pulse، حلقهٔ دوم و ذرات شعاعی با هویت Mint | یک Cue فعال؛ رویداد جدید Cue قبلی را جایگزین می‌کند |
| جمع‌آوری Key | `CUE_KEY` | 520ms | Marker در نقطهٔ رویداد | Pulse و ذرات Violet؛ صدای Key موجود حفظ می‌شود | مانند Objective |
| عبور Gate | `CUE_GATE` | 520ms | Marker در نقطهٔ عبور | حلقه و ذرات Amber برای تأکید بر عبور | مانند Objective |
| برخورد/مانع/Objective مسدود | `CUE_BLOCKED` | 520ms | Marker | Pulse مرجانی؛ علت Gate/Goal از Engine می‌آید | مانند Objective |
| Pause | `CUE_PAUSE` | 520ms | Marker | حلقهٔ خنثی، بدون جلو رفتن Timer | در زمان پایان cue دیگر فریم لازم نیست |
| Resume معتبر | `CUE_RESUME` | 520ms | Marker | Cue شروع مجدد؛ وضعیت Assisted فقط از Engine | رویداد Resume یک‌بار خوانده می‌شود |
| Gate فعال/بسته | Energy bead | در طول RUNNING | روی Barrier واقعی | ذرهٔ انرژی در امتداد پاره‌خط Barrier؛ وضعیت Open/Warning/Locked از Engine | Reduced Motion/Effects حرکت را حذف می‌کند؛ Gate State تغییر نمی‌کند |
| نزدیک‌شدن به مرز ممنوع | Boundary Warning پایدار تا دورشدن | تا خروج از ناحیهٔ هشدار | دور Marker | حلقه + مثلث/علامت تعجب؛ صوت فقط در صورت فعال‌بودن Feedback | با تغییر موقعیت و خروج از Margin خاموش می‌شود |
| Deadline در 10 ثانیهٔ پایانی | Timer emphasis | تا عبور از آستانه/تغییر ثانیه | HUD Timer | زمان عددی + تأکید دیداری؛ بدون flash/shake | با تغییر ثانیه به‌روز می‌شود؛ Reduced Motion نیازی به حرکت ندارد |
| `COMPLETED` | `TERMINAL_SUCCESS` | 720ms | Marker / حلقهٔ Mint | حلقهٔ منشوری، پرتوهای شعاعی و ذرات پخش‌شونده | سپس Outcome دقیقاً یک بار تحویل می‌شود |
| `FAILED` | `TERMINAL_FAILURE` | 720ms | Marker / حلقهٔ Coral | شکست مرجانی و نشانهٔ علت در محل Marker | سپس Outcome دقیقاً یک بار تحویل می‌شود |
| `INTERRUPTED` | بدون Cue Terminal | صفر | State/Accessibility | وقفه به‌عنوان شکست انیمیت نمی‌شود | نتیجهٔ Interrupted فوری است |

## تصمیم برای Motionهای وابسته به Engine یا سیستم میزبان

- **Marker و Trail:** مسیر/نقطهٔ Marker مستقیماً از Engine رسم می‌شود؛ Tween مستقل، interpolation نمایشی یا جابه‌جایی مصنوعی به آن اضافه نمی‌شود، چون می‌تواند اختلاف دیداری با موقعیت Collision و ورودی ایجاد کند. بازخورد لمس و رویدادها با Cueهای Marker-centric نشان داده می‌شوند.
- **Goal activation:** وضعیت معتبر Goal در Engine به `COMPLETED` منتهی می‌شود و Cue موفقیت 720ms را فعال می‌کند؛ انیمیشن جداگانه‌ای که پیش از تأیید Engine موفقیت را القا کند وجود ندارد.
- **Timer / نزدیک‌شدن به Deadline:** Countdown عددی از زمان Engine خوانده می‌شود؛ در 10 ثانیهٔ پایانی تأکید دیداری متنی/رنگی فعال می‌شود. Flash، shake یا pulse مکرر به Timer اضافه نشده تا خوانایی عدد و دسترس‌پذیری حفظ شود؛ عدد و ContentDescription مستقل از رنگ باقی می‌مانند.
- **Exit confirmation:** تأیید خروج همان Dialog موجود در `ScreenUI` است و انتقال آن به سیستم پنجره/پلتفرم واگذار می‌شود؛ Motion سفارشی جداگانه‌ای روی کنترل تخریبی Exit اضافه نشده است.
- **Result save:** Pending/Error/Unavailable/Retry/Success در `ResultScreen` باقی می‌مانند و از این کاتالوگ تکرار نمی‌شوند.
- **Pre-start countdown:** اضافه نشده؛ لمس معتبر Start شروع مستقیم اجراست و پیش‌درآمد زمانی، تأخیر بی‌دلیل به تعامل اصلی اضافه می‌کرد.

## Easing و کاهش اثر

- Cue کوتاه از تابع Smoothstep (`t² × (3−2t)`) استفاده می‌کند؛ مقیاس به زمان monotonic وابسته است و به تعداد فریم وابسته نیست.
- Alpha در طول Cue به‌شکل خطی کاهش می‌یابد؛ حلقهٔ دوم فقط در صورتی رسم می‌شود که Reduced Effects خاموش باشد.
- Reduced Effects: Glow/حلقهٔ دوم غیرضروری حذف یا سبک می‌شود؛ نمادهای اصلی و هشدارهای معنایی می‌مانند.
- Reduced Motion: Cueهای گذرا ساخته نمی‌شوند و Outcome پایانی بدون تأخیر تحویل می‌شود. اطلاعات وضعیت، آیکون‌ها و متن همچنان نمایش داده می‌شوند. صدای/Haptics از تنظیمات مستقل موجود خود پیروی می‌کنند.
- High Contrast: مرز هشدار و کنتراست نمادها با Stroke/رنگ روشن‌تر نمایش داده می‌شود.

## Storyboard متنی

### Start → حرکت
1. صفحه در READY با علامت Start، Goal، Timer و دستور لمس Start حاضر است.
2. لمس معتبر Start، Engine را به RUNNING می‌برد؛ Trail/Marker از Engine رسم می‌شوند و Cue شروع 520ms روی Marker قرار می‌گیرد.
3. در حرکت، مسیر ثبت‌شدهٔ واقعی با لایهٔ Trail رسم می‌شود؛ لمس کاربر فقط از Input Adapter به Engine می‌رود.
4. نزدیک مرز، حلقهٔ هشدار و مثلث روشن می‌شوند؛ هشدار با دورشدن از Margin خاموش می‌شود.

### Objective / Key / Gate
1. رویداد Engine از صف Events دریافت می‌شود.
2. براساس نوع رویداد Cue متناظر انتخاب می‌شود؛ Key از Objective معمولی قابل‌تشخیص است.
3. HUD با State واقعی Objective/Gate تازه می‌شود؛ رنگ با نماد و متن همراه است.

### Pause / Resume
1. Pause مجاز Engine را PAUSED می‌کند؛ Timer دیگر Tick نمی‌خورد.
2. بنر مکث صریحاً می‌گوید لمس نزدیک آخرین Marker لازم است؛ دکمهٔ Restart واقعی و Exit نمایش داده می‌شود.
3. لمس Anchor مجاز Engine را RUNNING می‌کند؛ لمس خارج Anchor Interrupted می‌شود و Resume جعلی رخ نمی‌دهد.

### Terminal
1. Engine به COMPLETED یا FAILED می‌رسد و Tick متوقف می‌شود.
2. حلقهٔ پایان روی Marker تا 720ms اجرا می‌شود.
3. پس از اتمام، Listener یک Outcome یکتا دریافت می‌کند؛ در Reduced Motion تحویل فوری است.

## محدودیت‌ها و QA لازم

- Cue فعلی در مختصات Marker هنگام رویداد است؛ هیچ انیمیشن موازی‌ای روی مسیر یا Gate به‌عنوان وضعیت جعلی رسم نمی‌شود.
- حرکت Marker همچنان از Engine می‌آید، نه از Tween.
- بررسی Frame Pacing، Battery و GPU Overdraw روی دستگاه انجام نشده و `UNVERIFIED` می‌ماند.
