# Part 5 — Art Direction: PRISM CIRCUIT

**وضعیت:** پیاده‌سازی سورس در Part 5 انجام شده؛ Build و تأیید بصری روی دستگاه واقعی هنوز `UNVERIFIED` است.

## 1. هدف هنری

NEON MAZE باید در لحظهٔ ورود به Gameplay شبیه یک بازی مستقل و قابل‌شناسایی باشد، نه یک پنل مدیریتی یا وب‌اپلیکیشن با چند کارت روی صفحه. زبان انتخاب‌شده **PRISM CIRCUIT** است: یک میدان معمایی/آرکیدی با معماری فلزی تیره، ریل‌های ماشین‌کاری‌شده، انرژی هدایت‌شده در لبه‌ها، درگاه‌های رنگی و یک هستهٔ بازیکن نورانی.

مرجع الهام، اصول است نه کپی دارایی: وضوح فضایی و سطوح معماری Monument Valley، خوانایی مسیر در بازی‌های معمایی مینیمال، و کنتراست محیط‌های تاریک/نورانی در بازی‌های فضایی. هیچ Asset یا UI متعلق به بازی دیگر وارد پروژه نشده است.

## 2. ترکیب صحنه (Scene Composition)

ترتیب لایه‌های Renderer:

1. **Deep-space void:** پس‌زمینهٔ سرمه‌ای/مشکی با هالهٔ دوردست و ستاره‌های کم‌تعداد؛ بدون تصویر Bitmap یا بارگذاری شبکه‌ای.
2. **Collision-authoritative board:** سطح واقعی حاصل از `outerBoundary` و `holeBoundaries` با `EVEN_ODD`; محدودهٔ تزئینی جایگزین محدودهٔ برخورد نمی‌شود.
3. **Machined surface material:** تکسچر اختصاصی `drawable-nodpi/prism_floor.webp` با `BitmapShader` به‌صورت Cover روی Viewport نگاشت و فقط داخل Clip همان Polygon رسم می‌شود. تکسچر جزئیات متریال می‌دهد؛ هیچ خط آن Route یا Collision نیست.
4. **Chassis / bevel:** سایهٔ عمقی، بدنهٔ فولادی، لبهٔ انرژی، خط هسته و Rivetهای مرزی؛ تمام مسیرها از هندسهٔ همان Level ساخته می‌شوند.
5. **Floor inlay:** خطوط مدار و گره‌های ظریف که فقط داخل Polygon قابل‌عبور Clip می‌شوند؛ به‌هیچ‌وجه به‌عنوان مسیر مجاز یا نشانهٔ برخورد استفاده نمی‌شوند.
6. **Gameplay actors:** Safe Zone، Gate، Objective، Start، Exit، Trail، Ghost و Marker؛ هرکدام با معنای بصری مستقل.
7. **Transient feedback:** cueها، ذرات، پالس‌ها و هشدار مرز در بالاترین لایه.

## 3. Palette واحد

| توکن | HEX | کاربرد معنایی |
|---|---|---|
| Void | `#050712` | فضای خارج از میدان |
| Void blue | `#0E1830` | هالهٔ عمقی |
| Board | `#143045` | زمینهٔ اصلی برد |
| Steel | `#2B435B` | بدنه/ریل مکانیکی |
| Mint | `#3FF5DE` | Start، Trail و وضعیت باز |
| Mint core | `#DBFFFA` | هستهٔ Marker و تأکید روشن |
| Amber | `#FFC460` | Objective اجباری، هشدار Gate و Exit |
| Coral | `#FF5B7E` | مانع، خطا و شکست |
| Violet | `#AE7EFF` | Key، Objective اختیاری و Ghost |
| Neutral | `#7B97B1` | وضعیت خنثی/بسته |
| White | `#F6FBFF` | متن و جزئیات روشن |

قواعد رنگ: معنا فقط با Hue منتقل نمی‌شود؛ حالت Gate، Objective و هشدار مرز نماد/شکل مستقل دارند. High Contrast از رنگ روشن و Stroke برای حفظ مرزبندی استفاده می‌کند.

## 4. فرم و متریال

- **Board:** گرادیان سرد، تکسچر صنعتی کم‌کنتراست با Alpha کنترل‌شده، Clip داخلی و ریل چندلایه؛ Glow یک خط نازک مکمل است، نه جایگزین شکل.
- **Surface texture:** فایل WebP با ابعاد 640×640 در `RGB_565` Decode می‌شود؛ Shader و Matrix فقط هنگام ساخت Renderer/تغییر Viewport آماده می‌شوند. تکسچر زیر عناصر تعاملی می‌ماند و در High Contrast حذف می‌شود.
- **Gate:** ریل ضخیم، هستهٔ انرژی، دندانه‌های مکانیکی و Caps انتهایی. حالت Open شکاف فیزیکی قابل‌دیدن دارد؛ Warning/Locked/Closed با Glyph متفاوت می‌مانند.
- **Objective:** هاله و Orbit ظریف؛ اجباری لوزی، اختیاری حلقه، Key نشانهٔ کلید، جمع‌شده Check.
- **Start/Exit:** Portal ring، Orbit segment، Glyph و برچسب بومی‌شدهٔ روی برد (`START/EXIT` یا `شروع/خروج`).
- **Player Marker:** هستهٔ روشن، انعکاس کوچک، Rim و Arcهای مداری؛ Cosmetic Frame/Emblem موجود همچنان اعمال می‌شود.
- **Trail:** سایهٔ تماس، Glow، لایهٔ میانی و Core باریک؛ نقطهٔ انتهایی از آخرین مختصات واقعی Trail می‌آید.
- **HUD:** پنل‌های برش‌گوشه با گرادیان عمقی، ریل Accent، Stroke داخلی و نشانه‌های Calibration؛ از Corner radius کارت‌های معمول اپلیکیشن فاصله می‌گیرد.

## 5. امضای بصری پنج Sector

هویت کلی PRISM CIRCUIT ثابت می‌ماند، اما نور محیطی هر مرحله با مضمون Level تغییر می‌کند. این رنگ فقط برای نور محیط/لبه/هاله است و معنای Objective، Gate یا هشدار را override نمی‌کند.

| Level | هویت | Accent محیطی | حس صحنه |
|---|---|---|---|
| 01 — Awakening | Mint | `#3FF5DE` | ورود، کشف و مسیر روشن |
| 02 — Precision Core | Violet | `#AE7EFF` | دقت، تمرکز و انتخاب شاخه |
| 03 — Pulse Reactor | Amber | `#FFC460` | زمان‌بندی و انرژی پالسی |
| 04 — Energy Vault | Azure | `#6FA7FF` | دسترسی، کلید و خزانه |
| 05 — The Core | Coral | `#FF5B7E` | فشار، ریسک و هستهٔ نهایی |

این امضاها در `GameRenderer.setLevel()` انتخاب می‌شوند و Shader محیط فقط هنگام تغییر اندازه یا Sector بازسازی می‌شود؛ نه در هر Draw.

## 6. سلسله‌مراتب Gameplay HUD

- Timer با فونت Condensed Bold، رقم Tabular و بیشترین وزن بصری.
- برچسب Sector و نام Level برای هویت مکانی.
- Objective و Gate در پنل‌های معنایی و کوتاه؛ حداکثر عرض کنترل‌شده.
- پیشرفت Objective به Rail قطعه‌ای تبدیل می‌شود: ردیف Mint برای هدف‌های اجباری و ردیف Violet برای اختیاری‌ها؛ متن کنار آن همچنان خلاصهٔ دسترس‌پذیر را فراهم می‌کند.
- State Banner فقط در READY/PAUSED/Terminal/Interrupted؛ هنگام RUNNING فضای مسیر را آزاد می‌کند.
- Pause/Restart/Exit نمادهای مستقل در Hit Target حداقل 48dp.
- Portrait: Timer در `safeTop + 4dp` با ارتفاع 48dp؛ Sector در `+54dp`؛ Objective و Gate کنار هم در `+76dp` با ارتفاع پایهٔ 44dp؛ Progress rail در `+125dp`؛ Banner در `+141dp`. Viewport در اندازهٔ پایه از `safeTop + 190dp` آغاز می‌شود. Pause/Restart و Exit در دو گوشهٔ بالایی قرار می‌گیرند.
- Landscape: Rail پایه با عرض 116dp در سمت انتخاب‌شده؛ Sector در `+3dp`، Timer در `+24dp`، کنترل‌های Exit و Pause/Restart در `+78dp`، Objective در `+136dp`، Progress rail در `+186dp`، Gate در `+206dp` و Banner در `+262dp`. Viewport از `safeTop + 54dp` و با inset کناری 128dp در سمت Rail آغاز می‌شود. در Landscape کوتاه‌تر از 520dp، Objective/Gate مخفی می‌شوند؛ Banner زیر Hit Targetهای کنترل قرار می‌گیرد (`controlTop + 58dp`) تا با کنترل‌ها هم‌پوشانی نداشته باشد.

## 7. Motion Choreography

| رویداد | حرکت | حس موردنظر |
|---|---|---|
| اجرای Start | حلقهٔ گسترش‌یافته و ذرات شعاعی | ورود به میدان |
| جمع‌آوری Objective | حلقه + الماس/ذرات با رنگ معنایی | پاداش فوری |
| جمع‌آوری Key | ذرات بنفش و نشان کلید | دریافت ابزار/دسترسی |
| عبور Gate | Pulse رویداد و انرژی روی Barrier | عبور از مکانیزم |
| Gate فعال/بسته | Energy bead در امتداد Barrier | زنده‌بودن مکانیزم، بدون تغییر State |
| نزدیک مرز | حلقه + مثلث و علامت | خطر خوانا، بدون لرزش اجباری |
| Terminal Success | منشور چهارگوش و پرتوهای شعاعی | حل معما |
| Terminal Failure | شکست مرجانی و علامت دقیق محل | بازخورد خطای صریح |

حرکت بر اساس زمان monotonic است، نه تعداد فریم. Engine تنها مالک Marker، Trail، Collision، Timer، Gate و Outcome است. Tween مستقل برای جابه‌جایی Marker ممنوع است.

## 8. قرارداد Performance و Accessibility

- هیچ Dependency یا Bitmap decode جدیدی افزوده نشده است.
- `Paint`، `Path`، `RectF` و Shaderهای ثابت reuse/cache می‌شوند؛ دادهٔ طول اضلاع Perimeter بیرون از Draw محاسبه می‌شود.
- Draw فقط Presentation است: IO، String formatting، ساخت collection یا Collision query جدید ممنوع.
- Reduced Motion حرکت‌های گذرا را حذف می‌کند؛ Reduced Effects Glow تزئینی را کاهش می‌دهد؛ High Contrast در برابر تغییر Viewport حفظ می‌شود.
- متن Start/Exit از resources محلی‌شده می‌آید؛ هیچ متن فارسی در Java هاردکد نشده است.
- QA واقعی TalkBack، Font Scale، Frame Pacing، GPU Overdraw، حرارت و باتری هنوز روی دستگاه لازم است.

## 9. Acceptance هنری Part 5

- [x] Game world دارای Void، Board، Chassis، Floor Detail و لایه‌های بازی است.
- [x] Gate/Objective/Start/Exit/Marker/Trail زبان بصری قابل‌تفکیک دارند.
- [x] HUD از پنل‌های Instrument-like استفاده می‌کند، نه کارت‌های تخت عمومی.
- [x] Cueهای رویدادی و Terminal از ذرات/پرتو و لایه‌های بصری استفاده می‌کنند.
- [x] هندسهٔ برخورد مرجع قطعی شکل برد باقی مانده است.
- [x] رنگ‌های پایه میان Renderer و HUD همسان شده‌اند.
- [x] هر پنج Level امضای نوری اختصاصی دارند، بدون تغییر رنگ‌های معنایی Gameplay.
- [x] Progress rail هدف‌های اجباری/اختیاری از وضعیت واقعی Engine تغذیه می‌شود.
- [x] Restart دارای Listener و LayoutParams مستقل 48dp است و فقط هنگام PAUSED جای کنترل Pause را می‌گیرد.
- [x] Portrait در Font Scale پایه از 260dp به 190dp و Rail افقی به عرض پایهٔ 116dp فشرده شده تا سطح قابل‌بازی بیشتر شود؛ با بزرگ‌شدن Font Scale، inset دوباره محاسبه می‌شود.
- [x] تکسچر متریال واقعی کف داخل Clip هندسهٔ Collision رسم می‌شود؛ خارج از برد هیچ‌جا نمایش داده نمی‌شود.
- [ ] تأیید بصری روی چند اندازه/نسبت تصویر واقعی.
- [ ] Android Build و اجرای واقعی روی دستگاه.
- [ ] پروفایل GPU/Frame pacing/Thermal.
