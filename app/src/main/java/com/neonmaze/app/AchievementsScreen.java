package com.neonmaze.app;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import com.neonmaze.core.ProgressRules;
import com.neonmaze.core.LevelSystem.Catalog;

import java.text.DateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Achievements and earned cosmetics are presented from Core definitions + LocalStore rows. */
public final class AchievementsScreen {
    public interface Actions {
        void onRetry();
        void onBack();
        void onEquipCosmetic(@NonNull String cosmeticId);
        void onUnequipEmblem();
    }

    private final Activity activity;
    private final UiKit ui;
    private final List<LocalStore.Achievement> stored;
    private final Map<String, LocalStore.LevelProgress> progress;
    private final boolean loading;
    private final boolean loadFailed;
    private final Catalog catalog;
    private final LocalStore.CosmeticLoadout loadout;
    private final Actions actions;

    public AchievementsScreen(@NonNull Activity activity, @NonNull UiKit ui,
                              List<LocalStore.Achievement> stored,
                              Map<String, LocalStore.LevelProgress> progress,
                              boolean loading, boolean loadFailed,
                              @NonNull Catalog catalog,
                              @NonNull LocalStore.CosmeticLoadout loadout, Actions actions) {
        this.activity = activity;
        this.ui = ui;
        this.stored = stored;
        this.progress = progress;
        this.loading = loading;
        this.loadFailed = loadFailed;
        this.catalog = catalog;
        this.loadout = loadout;
        this.actions = actions;
    }

    public View create() {
        ScrollView scroll = ui.scrollPage();
        LinearLayout page = ui.pageContent(scroll);
        ui.addPageHeader(page, activity.getString(R.string.screen_achievements_eyebrow),
                activity.getString(R.string.achievements_title),
                activity.getString(R.string.achievements_subtitle));
        if (loading || loadFailed) {
            ui.addText(page, activity.getString(loadFailed ? R.string.achievements_load_error
                            : R.string.home_loading),
                    13, loadFailed ? UiKit.AMBER : UiKit.TEXT_SECONDARY, true, 0, 0);
            if (loadFailed) ui.addButton(page, ui.button(activity.getString(R.string.home_retry),
                    UiKit.ButtonStyle.SECONDARY, actions::onRetry), true);
            ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                    UiKit.ButtonStyle.QUIET, actions::onBack), true);
            return scroll;
        }
        Map<String, LocalStore.Achievement> byId = new HashMap<>();
        if (stored != null) for (LocalStore.Achievement item : stored) {
            byId.put(item.achievementId, item);
        }
        List<ProgressRules.AchievementDef> definitions = ProgressRules.achievements();
        if (definitions.isEmpty()) {
            ui.addText(page, activity.getString(R.string.achievements_empty),
                    14, UiKit.TEXT_SECONDARY, false, 0, 0);
        }
        for (ProgressRules.AchievementDef definition : definitions) {
            LocalStore.Achievement row = byId.get(definition.id());
            boolean unlocked = row != null && row.unlockedAt != null;
            int progress = row == null ? 0 : Math.max(0, row.progressValue);
            LinearLayout card = ui.card();
            LinearLayout titleRow = ui.row();
            titleRow.addView(ui.icon(UiKit.Icon.TROPHY,
                    unlocked ? UiKit.MINT : UiKit.TEXT_SECONDARY, 20, null),
                    new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
            android.widget.TextView title = ui.text(
                    activity.getString(achievementName(definition.id())),
                    16, unlocked ? UiKit.MINT : UiKit.TEXT_PRIMARY, true);
            LinearLayout.LayoutParams titleParams = ui.wrap();
            titleParams.setMarginStart(ui.dp(9));
            titleRow.addView(title, titleParams);
            card.addView(titleRow, ui.matchWrap());
            ui.addText(card, activity.getString(achievementDescription(definition.id())),
                    12, UiKit.TEXT_SECONDARY, false, 5, 0);
            LinearLayout status = ui.row();
            ui.addText(status, activity.getString(unlocked
                    ? R.string.achievement_unlocked : R.string.achievement_locked),
                    11, unlocked ? UiKit.MINT : UiKit.AMBER, true, 0, 0);
            if (!unlocked) {
                android.widget.TextView count = ui.text(activity.getString(
                        R.string.achievement_progress_value, Math.min(progress, definition.target()),
                        definition.target()), 11, UiKit.TEXT_SECONDARY, true);
                LinearLayout.LayoutParams p = ui.wrap();
                p.setMarginStart(ui.dp(10));
                status.addView(count, p);
            } else if (row != null && row.unlockedAt != null) {
                java.util.Locale locale = "en".equals(ui.context().getResources().getConfiguration().getLocales().get(0).getLanguage())
                        ? java.util.Locale.ENGLISH : new java.util.Locale("fa");
                String date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                        .format(new java.util.Date(row.unlockedAt));
                android.widget.TextView dateView = ui.text(date, 10, UiKit.TEXT_SECONDARY, false);
                LinearLayout.LayoutParams p = ui.wrap();
                p.setMarginStart(ui.dp(10));
                status.addView(dateView, p);
            }
            card.addView(status, ui.matchWrap());
            ui.addCard(page, card, 8);
        }

        int campaignCompletions = 0;
        if (progress != null) for (com.neonmaze.core.LevelSystem.LevelDefinition level : catalog.all()) {
            LocalStore.LevelProgress row = progress.get(level.levelId());
            if (row != null) campaignCompletions += Math.max(0, row.completions);
        }
        ui.addSection(page, activity.getString(R.string.achievements_cosmetics), null);
        for (ProgressRules.CosmeticDef cosmetic : ProgressRules.cosmetics()) {
            boolean unlocked = cosmetic.defaultUnlocked();
            if (!unlocked && !cosmetic.achievementId().isEmpty()) {
                LocalStore.Achievement row = byId.get(cosmetic.achievementId());
                unlocked = row != null && row.unlockedAt != null;
            } else if (!unlocked && cosmetic.completionsNeeded() > 0) {
                unlocked = campaignCompletions >= cosmetic.completionsNeeded();
            }
            LinearLayout card = ui.row();
            View glyph = new CosmeticPreview(activity, ui, cosmetic);
            LinearLayout.LayoutParams glyphParams = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46));
            glyphParams.gravity = android.view.Gravity.CENTER_VERTICAL;
            card.addView(glyph, glyphParams);
            LinearLayout info = ui.column();
            info.setPaddingRelative(ui.dp(12), 0, 0, 0);
            ui.addText(info, activity.getString(cosmeticName(cosmetic.id())), 13,
                    unlocked ? UiKit.TEXT_PRIMARY : UiKit.TEXT_SECONDARY, true, 0, 0);
            boolean equipped = cosmetic.id().equals(loadout.forKind(cosmetic.kind()));
            ui.addText(info, activity.getString(!unlocked ? R.string.achievements_cosmetic_locked
                            : equipped ? R.string.cosmetic_equipped : R.string.cosmetic_available),
                    10, equipped ? UiKit.MINT : UiKit.TEXT_SECONDARY, false, 3, 0);
            String unlockRule = cosmetic.defaultUnlocked()
                    ? activity.getString(R.string.cosmetic_unlock_default)
                    : !cosmetic.achievementId().isEmpty()
                    ? activity.getString(R.string.cosmetic_unlock_achievement,
                    activity.getString(achievementName(cosmetic.achievementId())))
                    : activity.getString(R.string.cosmetic_unlock_completions,
                    cosmetic.completionsNeeded());
            ui.addText(info, unlockRule, 10, UiKit.TEXT_SECONDARY, false, 3, 0);
            card.addView(info, new LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (unlocked && !equipped) {
                ui.addButton(card, ui.button(activity.getString(R.string.cosmetic_equip),
                        UiKit.ButtonStyle.SECONDARY, () -> actions.onEquipCosmetic(cosmetic.id())), false);
            } else if (equipped && cosmetic.kind() == ProgressRules.COSMETIC_EMBLEM) {
                ui.addButton(card, ui.button(activity.getString(R.string.cosmetic_unequip),
                        UiKit.ButtonStyle.QUIET, actions::onUnequipEmblem), false);
            }
            ui.addCard(page, card, 5);
        }
        ui.addButton(page, ui.button(activity.getString(R.string.nav_back),
                UiKit.ButtonStyle.QUIET, actions::onBack), true);
        return scroll;
    }

    /** Small deterministic preview mirrors the equipped trail/frame/emblem families. */
    private static final class CosmeticPreview extends View {
        private final UiKit ui;
        private final ProgressRules.CosmeticDef definition;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CosmeticPreview(Activity activity, UiKit ui, ProgressRules.CosmeticDef definition) {
            super(activity);
            this.ui = ui;
            this.definition = definition;
            setMinimumWidth(ui.dp(46)); setMinimumHeight(ui.dp(46));
            setContentDescription(activity.getString(cosmeticName(definition.id())));
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() * .5f, cy = getHeight() * .5f;
            float radius = Math.min(getWidth(), getHeight()) * .23f;
            int color = colorFor(definition.id(), definition.kind());
            fill.setStyle(Paint.Style.FILL); fill.setColor(UiKit.SURFACE_ELEVATED);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), ui.dp(10), ui.dp(10), fill);
            stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND); stroke.setStrokeWidth(ui.dp(2.2f));
            stroke.setColor(color);
            path.reset();
            if (definition.kind() == ProgressRules.COSMETIC_TRAIL) {
                path.moveTo(cx - radius * 1.5f, cy + radius * .5f);
                path.cubicTo(cx - radius * .4f, cy - radius * 1.7f,
                        cx + radius * .25f, cy + radius * 1.6f, cx + radius * 1.5f, cy - radius * .45f);
                canvas.drawPath(path, stroke);
                fill.setColor(color); canvas.drawCircle(cx + radius * 1.5f, cy - radius * .45f, ui.dp(2.4f), fill);
            } else if (definition.kind() == ProgressRules.COSMETIC_FRAME) {
                if (definition.id().endsWith("hex")) drawPolygon(path, cx, cy, radius * 1.65f, 6, (float) (-Math.PI / 6), stroke, canvas);
                else {
                    float scale = definition.id().endsWith("pulse") ? 2.0f
                            : definition.id().endsWith("prism") ? 1.8f : 1.55f;
                    canvas.drawCircle(cx, cy, radius * scale, stroke);
                    if (definition.id().endsWith("prism")) drawPolygon(path,cx,cy,radius*2.0f,4,(float)(Math.PI/4),stroke,canvas);
                }
                fill.setColor(UiKit.TEXT_PRIMARY); canvas.drawCircle(cx, cy, radius * .65f, fill);
            } else {
                if (definition.id().endsWith("precision_learner")) drawPolygon(path,cx,cy,radius*1.45f,6,(float)(-Math.PI/6),stroke,canvas);
                else if (definition.id().endsWith("vault_finder")) drawPolygon(path,cx,cy,radius*1.45f,4,(float)(Math.PI/4),stroke,canvas);
                else if (definition.id().endsWith("objective_hunter")) {
                    canvas.drawLine(cx-radius,cy,cx+radius,cy,stroke); canvas.drawLine(cx,cy-radius,cx,cy+radius,stroke);
                } else if (definition.id().endsWith("gate_runner")) {
                    path.moveTo(cx-radius,cy-radius*.5f); path.lineTo(cx,cy+radius*.35f); path.lineTo(cx+radius,cy-radius*.5f); canvas.drawPath(path,stroke);
                } else drawStar(path,cx,cy,radius*1.45f,stroke,canvas);
            }
        }

        private static int colorFor(String id, int kind) {
            if (kind == ProgressRules.COSMETIC_TRAIL) {
                if (id.endsWith("violet")) return 0xFFA78BFA;
                if (id.endsWith("amber")) return 0xFFFFC86B;
                if (id.endsWith("coral")) return 0xFFFF6B7A;
                if (id.endsWith("ice")) return 0xFFA4DDFF;
                return 0xFF56E6D5;
            }
            return kind == ProgressRules.COSMETIC_FRAME ? 0xFFA78BFA : 0xFFFFC86B;
        }

        private void drawPolygon(Path target,float cx,float cy,float r,int sides,float rotation,Paint paint,Canvas canvas) {
            target.reset();
            for(int i=0;i<sides;i++){double a=rotation+2*Math.PI*i/sides;float x=cx+(float)Math.cos(a)*r;float y=cy+(float)Math.sin(a)*r;if(i==0)target.moveTo(x,y);else target.lineTo(x,y);}
            target.close(); canvas.drawPath(target,paint);
        }
        private void drawStar(Path target,float cx,float cy,float r,Paint paint,Canvas canvas) {
            target.reset();
            for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float rr=i%2==0?r:r*.43f;float x=cx+(float)Math.cos(a)*rr;float y=cy+(float)Math.sin(a)*rr;if(i==0)target.moveTo(x,y);else target.lineTo(x,y);}
            target.close(); canvas.drawPath(target,paint);
        }
    }

    private static int achievementName(String id) {
        switch (id) {
            case ProgressRules.ACH_FIRST_TRACE: return R.string.achievement_first_trace_name;
            case ProgressRules.ACH_PRECISION_LEARNER: return R.string.achievement_precision_learner_name;
            case ProgressRules.ACH_GATE_RUNNER: return R.string.achievement_gate_runner_name;
            case ProgressRules.ACH_VAULT_FINDER: return R.string.achievement_vault_finder_name;
            case ProgressRules.ACH_CORE_COMPLETE: return R.string.achievement_core_complete_name;
            case ProgressRules.ACH_OBJECTIVE_HUNTER: return R.string.achievement_objective_hunter_name;
            case ProgressRules.ACH_PERSONAL_BEST: return R.string.achievement_personal_best_name;
            case ProgressRules.ACH_DAILY_SPARK: return R.string.achievement_daily_spark_name;
            default: return R.string.achievements_title;
        }
    }

    private static int achievementDescription(String id) {
        switch (id) {
            case ProgressRules.ACH_FIRST_TRACE: return R.string.achievement_first_trace_desc;
            case ProgressRules.ACH_PRECISION_LEARNER: return R.string.achievement_precision_learner_desc;
            case ProgressRules.ACH_GATE_RUNNER: return R.string.achievement_gate_runner_desc;
            case ProgressRules.ACH_VAULT_FINDER: return R.string.achievement_vault_finder_desc;
            case ProgressRules.ACH_CORE_COMPLETE: return R.string.achievement_core_complete_desc;
            case ProgressRules.ACH_OBJECTIVE_HUNTER: return R.string.achievement_objective_hunter_desc;
            case ProgressRules.ACH_PERSONAL_BEST: return R.string.achievement_personal_best_desc;
            case ProgressRules.ACH_DAILY_SPARK: return R.string.achievement_daily_spark_desc;
            default: return R.string.achievements_subtitle;
        }
    }

    static int cosmeticName(String id) {
        switch (id) {
            case "trail.mint": return R.string.cosmetic_trail_mint;
            case "trail.violet": return R.string.cosmetic_trail_violet;
            case "trail.amber": return R.string.cosmetic_trail_amber;
            case "trail.coral": return R.string.cosmetic_trail_coral;
            case "trail.ice": return R.string.cosmetic_trail_ice;
            case "frame.none": return R.string.cosmetic_frame_none;
            case "frame.ring": return R.string.cosmetic_frame_ring;
            case "frame.hex": return R.string.cosmetic_frame_hex;
            case "frame.pulse": return R.string.cosmetic_frame_pulse;
            case "frame.prism": return R.string.cosmetic_frame_prism;
            case "emblem.first_trace": return R.string.cosmetic_emblem_first_trace;
            case "emblem.precision_learner": return R.string.cosmetic_emblem_precision_learner;
            case "emblem.gate_runner": return R.string.cosmetic_emblem_gate_runner;
            case "emblem.vault_finder": return R.string.cosmetic_emblem_vault_finder;
            case "emblem.core_complete": return R.string.cosmetic_emblem_core_complete;
            case "emblem.objective_hunter": return R.string.cosmetic_emblem_objective_hunter;
            case "emblem.personal_best": return R.string.cosmetic_emblem_personal_best;
            case "emblem.daily_spark": return R.string.cosmetic_emblem_daily_spark;
            default: return R.string.common_locked;
        }
    }
}
