# Part 5 — Gameplay Design Tokens: PRISM CIRCUIT

این نسخه با Art Direction اجرایی `PRISM CIRCUIT` هماهنگ شده است. هدف، ساخت یک فضای قابل‌شناسایی بازی است: برد فلزی چندلایه در Void تاریک، لبه‌های انرژی و پنل‌های HUD شبیه ابزار مکانیکی. رنگ‌ها و Motion فقط تزئین نیستند؛ هرکدام معنا و محدودیت مشخص دارند.

## رنگ و معنا

| Token | مقدار | معنا/کاربرد |
|---|---|---|
| `void` | `#050712` | فضای بیرون Board |
| `void-blue` | `#0E1830` | هالهٔ عمقی |
| `board` | `#143045` | سطح قابل‌بازی |
| `steel` | `#2B435B` | ریل و شاسی |
| `mint` | `#3FF5DE` | Trail/Start/وضعیت باز |
| `mint-core` | `#DBFFFA` | هستهٔ Marker/موفقیت |
| `amber` | `#FFC460` | Objective اجباری، Warning، Exit |
| `coral` | `#FF5B7E` | برخورد/خطا/شکست |
| `violet` | `#AE7EFF` | Key، Objective اختیاری و Ghost |
| `neutral` | `#7B97B1` | وضعیت خنثی/بسته |
| `text` | `#F6FBFF` | متن اصلی و آیکون روشن |

### Accent محیطی Sectorها

| Level | Accent | HEX |
|---|---|---|
| 01 Awakening | Mint | `#3FF5DE` |
| 02 Precision Core | Violet | `#AE7EFF` |
| 03 Pulse Reactor | Amber | `#FFC460` |
| 04 Energy Vault | Azure | `#6FA7FF` |
| 05 The Core | Coral | `#FF5B7E` |

رنگ به‌تنهایی هیچ وضعیت را تعریف نمی‌کند؛ نماد، شکل، متن و حالت Engine مکمل آن هستند. Palette پایه در `GameRenderer` و `GameHud` یکسان نگه داشته می‌شود. Accent محیطی هر Sector جداگانه انتخاب می‌شود و معنای ثابت Objective/Gate را تغییر نمی‌دهد.

## تایپوگرافی و سلسله‌مراتب

| نقش | مقدار | قرارداد |
|---|---:|---|
| Timer | 26sp، Condensed Bold، Tabular | خواندن سریع زمان؛ بدون Live Region هر ثانیه |
| Sector/Level | 10.5sp، Medium، فاصلهٔ حروف بیشتر | هویت مرحله و نام Level |
| Objective/Gate | 12.5sp، Medium | پنل دوخطی حداکثر، کوتاه و وضعیتی |
| State Banner | 12.5sp، Medium | حداکثر دو خط، Live Region polite |
| کنترل‌های آیکونی | حداقل 48×48dp | Hit Target مستقل از Glyph |

متن‌ها از `strings.xml` و `values-fa/strings.xml` می‌آیند؛ متن فارسی در Java هاردکد نمی‌شود.

## فرم و نماد

- Board: سایهٔ عمقی + بدنهٔ Steel + ریل انرژی + Core edge + Rivet.
- Start: Portal ring + Cross + برچسب بومی‌شده.
- Exit: Portal amber + Diamond + برچسب بومی‌شده.
- Objective اجباری: لوزی؛ اختیاری: حلقه؛ Key: کلید؛ جمع‌شده: Check.
- Gate: ریل Barrier با دندانه و Caps؛ Open با شکاف؛ Warning/Locked/Closed با Glyph مستقل.
- Player: Orb چندلایه با Rim، Specular و Cosmetic Frame/Emblem.
- Trail: Shadow، Glow، Mid و Core از مسیر واقعی Engine.
- HUD: پنل‌های برش‌گوشه با Gradient، Accent Rail و Calibration marks؛ از Cardهای گرد عمومی دوری می‌کند.

## فاصله و Layout

- هدف لمس 48dp یا بیشتر؛ Window Insets واقعی رعایت می‌شود.
- Portrait: Timer در y=4dp با ارتفاع 48dp، Sector در y=54dp، Objective و Gate کنار هم در y=76dp با ارتفاع پایهٔ 44dp، Progress rail در y=125dp و Banner در y=141dp؛ Viewport در Font Scale پایه از safeTop+190dp آغاز می‌شود.
- Landscape: Rail پایه 116dp؛ Sector y=3dp، Timer y=24dp، Exit و Pause/Restart در y=78dp، Objective y=136dp، Progress rail y=186dp، Gate y=206dp و Banner y=262dp؛ Viewport از safeTop+54dp آغاز می‌شود و فقط سمت Rail دارای inset اضافی پایهٔ 128dp است.
- Landscape کوتاه‌تر از 520dp: Objective/Gate/Progress مخفی و Banner در `controlTop+58dp`، پایین‌تر از Hit Targetهای کنترل، قرار می‌گیرد.
- Restart دارای LayoutParams مستقل 48dp است و در PAUSED جای Pause را می‌گیرد؛ دکمهٔ Resume غیرواقعی به UI افزوده نشده است.
- Left-handed جای Rail و کنترل‌ها را عوض می‌کند، نه هندسهٔ Level را.

## رفتار تطبیقی Font Scale

- Layout پایه در Font Scale عادی با ابعاد فشردهٔ Portrait/Landscape بالا تعریف شده است.
- از Font Scale برابر 1.35 به بالا، Objective و Gate در Portrait به دو ردیف کامل تبدیل می‌شوند؛ inset بالای Playfield با ارتفاع HUD محاسبه می‌شود.
- Rail افقی متناسب با متن بزرگ‌تر می‌شود؛ در Landscape با ارتفاع کمتر از 520dp، چیپ‌ها و Progress مخفی می‌شوند و Banner بر اساس ارتفاع کنترل‌ها چیده می‌شود؛ Font Scale بزرگ در Landscape بلند موجب مخفی‌شدن بی‌دلیل اطلاعات نمی‌شود.
- این رفتار در سورس پیاده‌سازی شده، اما تأیید خوانایی واقعی در تنظیمات Display Size/Font Scale بالا همچنان به QA دستگاهی نیاز دارد.

## Motion Tokens

| Token | مقدار |
|---|---:|
| `motion.cue.duration` | 520ms |
| `motion.terminal.duration` | 720ms |
| `motion.easing` | Smoothstep / cubic ease-in-out |
| `motion.cue.layers` | حلقهٔ اصلی + حلقهٔ مکمل/ذرات؛ Reduced Effects لایه‌های اضافی را حذف می‌کند |
| `motion.gate-energy` | حرکت bead فقط در Presentation؛ state از Engine |
| `motion.reduced` | Cue و تأخیر Terminal حذف می‌شود |

## Accessibility

- `Reduced Motion`: حرکت‌های گذرا حذف می‌شوند؛ هیچ اطلاعات معنایی حذف نمی‌شود.
- `Reduced Effects`: Glow تزئینی و حلقهٔ ثانویه سبک/حذف می‌شود؛ Trail، ریل و نمادهای بازی حفظ می‌شوند.
- `High Contrast`: رنگ Board جامد و Stroke روشن؛ حتی پس از تغییر Viewport/چرخش صفحه حفظ می‌شود.
- `RTL/LTR`: متن locale و layout بومی Android حفظ می‌شود.
- `Left-handed`: جای Rail و کنترل‌ها عوض می‌شود.

## Performance

- Draw در هر فریم هیچ IO، Bitmap decode، collection سازی یا Collision query انجام نمی‌دهد.
- تکسچر 640×640 کف در زمان اتصال Engine یک‌بار به `RGB_565` Decode و تا عمر همان GameView reuse می‌شود؛ `BitmapShader` و Matrix در تغییر Viewport cache می‌شوند.
- Shaderهای محیطی در اندازه/Viewport cache می‌شوند؛ طول قطعات محیطی در `setLevel` محاسبه می‌شود.
- زمان انیمیشن monotonic است؛ فریم فقط هنگام RUNNING یا Cue گذرا زمان‌بندی می‌شود.
- تأیید Frame Pacing، Thermal، Battery و GPU Overdraw باید روی دستگاه واقعی انجام شود.
