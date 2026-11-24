# Part 5 — نقشهٔ تجربه و ماتریس وضعیت Gameplay

**وضعیت:** پیاده‌سازی در Source of Truth Part 3 انجام شده؛ بررسی Build و دستگاه واقعی هنوز اثبات نشده است.

## هدف تجربه

بازیکن باید در هر لحظه سه پاسخ فوری بگیرد: «الان در چه وضعیتی هستم؟»، «هدف/خطر بعدی چیست؟» و «برای اقدام بعدی چه ورودی‌ای لازم است؟». HUD نباید سطح قابل‌بازی یا محدودهٔ دریافت لمس را بپوشاند. دادهٔ نمایش‌داده‌شده از `GameEngine` و `LevelDefinition` می‌آید؛ HUD مالک منطق بازی نیست.

## جریان اصلی

```text
READY ── لمس Start معتبر ──> RUNNING
RUNNING ── Objective/Key/Gate/Blocked ──> RUNNING + بازخورد کوتاه
RUNNING ── Pause مجاز ──> PAUSED ── لمس نزدیک Anchor ──> RUNNING (Assisted)
PAUSED ── Restart ──> اجرای جدید با Engine جدید
READY/RUNNING/PAUSED ── Exit + تأیید ──> خروج از Run
RUNNING ── Goal معتبر ──> COMPLETED ── cue پایانی ──> RESULT ── ذخیره/نمایش وضعیت ذخیره
RUNNING ── Collision/Timeout/Fail ──> FAILED ── cue پایانی ──> RESULT ── ذخیره/نمایش وضعیت ذخیره
RUNNING/PAUSED ── Lifecycle interruption ──> INTERRUPTED؛ بدون Resume خودکار
```

## ماتریس وضعیت

| Engine state | پیام/اطلاعات HUD | کنترل‌های قابل‌نمایش | بازخورد دیداری | قاعدهٔ ورودی |
|---|---|---|---|---|
| `READY` | Timer، Objective/Gate در صورت وجود، راهنمای لمس Start | Exit؛ Pause مخفی | نشانهٔ Start/Goal و cue شروع پس از آغاز | فقط لمس داخل Start معتبر اجرا را آغاز می‌کند |
| `RUNNING` | زمان سپری‌شده یا باقی‌مانده، Objective، وضعیت Gate | Pause فقط برای Campaign/Practice؛ Exit | Trail پیوسته، Marker، هشدار نزدیک مرز و cue رویداد | Engine مرجع Collision و پیشروی است |
| `PAUSED` | راهنمای لمس نزدیک آخرین Marker برای Resume | Restart و Exit؛ Pause مخفی | cue مکث؛ وضعیت پایدار و بدون tick زمان | دکمهٔ Resume جعلی وجود ندارد؛ Resume واقعی از لمس Anchor عبور می‌کند |
| `COMPLETED` | پیام موفقیت کوتاه | کنترل‌های اجرای فعال مخفی | حلقهٔ پایانی موفقیت | نتیجه پس از پایان cue یا فوراً در Reduced Motion تحویل می‌شود |
| `FAILED` | پیام پایان/شکست کوتاه | کنترل‌های اجرای فعال مخفی | حلقهٔ پایانی شکست و علامت علت در محل Marker | نتیجه فقط یک‌بار تحویل می‌شود |
| `INTERRUPTED` | وضعیت وقفه برای دسترس‌پذیری/نتیجه | کنترل‌های اجرای فعال مخفی | بدون نمایش موفقیت/شکست جعلی | وقفهٔ سیستم، شکست محسوب نمی‌شود و خودکار ادامه نمی‌یابد |

## سلسله‌مراتب HUD

1. **Timer**: پررنگ‌ترین عنصر متنی؛ در حالت رقابت زمانی، زمان باقی‌مانده و در اجرای معمول، زمان سپری‌شده را نشان می‌دهد.
2. **Objective و Gate**: چیپ‌های وضعیت با متن و نماد؛ رنگ به‌تنهایی معنا را منتقل نمی‌کند.
3. **State Banner**: فقط در وضعیت‌های نیازمند راهنمایی (Ready، Paused، Terminal، Interrupted) ظاهر می‌شود.
4. **Pause / Restart / Exit**: هدف لمسی حداقل 48dp؛ Exit همیشه در حالت اجرای فعال قابل‌کشف است و تأیید می‌خواهد.
5. **Playfield**: اولویت فضایی اصلی؛ صحنهٔ چندلایهٔ PRISM CIRCUIT با متریال فلزی واقعی، ریل چندلایه، Floor Inlay و Energy rail؛ نوار بالایی Portrait در Font Scale پایه 190dp و Rail افقی با عرض پایهٔ 116dp از Viewport کم می‌شوند؛ در Font Scale بزرگ، چیدمان و inset به‌صورت تطبیقی گسترش می‌یابند تا متن فشرده نشود.

## اتصال به Result و Feedback موجود

- `ResultScreen` مالک وضعیت ذخیرهٔ نتیجه است و پیام‌های Pending، Error، Unavailable، Retry و Success را از مسیرهای محلی‌سازی‌شدهٔ موجود نمایش می‌دهد؛ Part 5 این منطق را در HUD کپی یا با مسیر ذخیرهٔ موازی جایگزین نمی‌کند.
- `FeedbackSystem` از قبل صدای رویداد و Haptics مربوط به Start، Objective، Key، Gate، Near-boundary، Pause، Resume و Terminal را با Cooldown مدیریت می‌کند. Cueهای دیداری Part 5 مکمل همان رویدادها هستند؛ لایهٔ صوتی/لمسی موازی جدیدی ساخته نشده است.
- Countdown پیش از Start افزوده نشده است: آغاز اجرا به لمس معتبر Start وابسته است و شمارش معکوس، مسیر تعاملی اصلی را بدون نیاز گیم‌پلی به تأخیر می‌اندازد.

## قراردادهای ورودی و خروج

- Pause تنها در Modeهایی که Engine اجازه می‌دهد نمایش داده می‌شود.
- Resume فقط با لمس در شعاع Anchor تعریف‌شده در Engine ممکن است؛ لمس خارج Anchor به `INTERRUPTED` می‌انجامد، نه به Resume ناقص.
- Restart یک `GameEngine` تازه می‌سازد و همان GameView را به Engine جدید متصل می‌کند؛ Run قبلی نباید نتیجهٔ ناخواسته ذخیره کند.
- Exit در HUD همیشه تأیید می‌خواهد؛ در Tutorial از تأیید اختصاصی Tutorial استفاده می‌شود.
- وقفهٔ Lifecycle هر Run فعال/مکث‌شده را به وضعیت Interrupted می‌برد؛ پس از بازگشت Activity، Run دوباره شروع نمی‌شود.

## جهت، چیدمان و دسترس‌پذیری

- فارسی و انگلیسی از منابع محلی‌شدهٔ موجود استفاده می‌کنند؛ چیدمان Android در RTL/LTR حفظ می‌شود.
- حالت چپ‌دست جای Pause/Exit و Rail را جابه‌جا می‌کند، بدون تغییر هندسهٔ بازی.
- Portrait در Font Scale پایه: Timer در `safeTop+4dp` با ارتفاع 48dp، Sector در `+54dp`، Objective/Gate کنار هم در `+76dp` با ارتفاع پایهٔ 44dp، Progress rail در `+125dp` و Banner در `+141dp`; Viewport از `safeTop+190dp` آغاز می‌شود. در Font Scale برابر یا بالاتر از 1.35، Objective و Gate در دو ردیف کامل قرار می‌گیرند و inset بالای Playfield از ارتفاع واقعی HUD محاسبه می‌شود. Start/Exit برچسب بومی‌شدهٔ روی صحنه دارند.
- Landscape: Rail پایه 116dp و Viewport از `safeTop+54dp` با inset کناری 128dp در سمت Rail آغاز می‌شود؛ اندازهٔ Rail با Font Scale افزایش می‌یابد. در ارتفاع کمتر از 520dp، Objective/Gate/Progress پنهان و State Banner زیر Hit Targetهای کنترل (`controlTop+58dp`) قرار می‌گیرد. Font Scale به‌تنهایی در نمایشگر Landscape بلند چیپ‌ها را مخفی نمی‌کند؛ Rail و اجزا با متن سیستم بزرگ می‌شوند.
- Restart دکمهٔ 48dp مستقلی است که فقط در PAUSED ظاهر می‌شود و در همان جای Pause می‌نشیند؛ در RUNNING هیچ هدف لمسی اضافه‌ای روی میدان قرار نمی‌گیرد.
- دکمه‌ها حداقل 48dp هستند؛ نمادها با متن/وضعیت تکمیل می‌شوند و وضعیت فقط با رنگ کدگذاری نمی‌شود.
- Live Region برای Objective، Gate و State Banner از نوع polite است؛ Timer هر ثانیه Live Region را تحریک نمی‌کند.
- High Contrast نمادها/مرزها را با سفید و Stroke قوی‌تر مشخص می‌کند و پس از تغییر Viewport حفظ می‌شود. Reduced Effects، Glow تزئینی را کاهش می‌دهد؛ Reduced Motion انیمیشن‌های گذرا و تأخیر نتیجه را حذف می‌کند.

## موارد نیازمند QA دستگاهی

- بررسی عدم هم‌پوشانی در تلفن کوتاه/بلند، بریدگی دوربین، تنظیمات Display Size و Font Scale بالا.
- TalkBack و Switch Access واقعی برای Start/Goal/Objective/Gate و Playfield.
- بررسی بصری در Portrait و Landscape، RTL و LTR و چپ‌دست.
- اندازه‌گیری زمان فریم و حافظه در دستگاه مرجع؛ این محیط فاقد Android SDK/ADB و ابزار اجرای Build است، بنابراین موارد فوق هنوز `UNVERIFIED` هستند.
