package com.neonmaze.core;

import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.LevelSystem.Objective;
import com.neonmaze.core.LevelSystem.ObjectiveType;
import com.neonmaze.core.ReplaySystem.ReplayMode;
import com.neonmaze.core.ReplaySystem.ReplayTrace;
import com.neonmaze.core.ReplaySystem.RunResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pure, deterministic progression rules for NEON MAZE.
 *
 * <p>This class owns Campaign Stars/Score/Unlock/Best rules, mode-specific
 * records, stable Optional masks, Achievement evaluation and earned Cosmetic
 * definitions. It performs no Android, database, file, clock or network I/O.</p>
 *
 * <p>Campaign order is the order supplied by {@link LevelSystem.Catalog}; the
 * catalog is the single source of truth for progression order. Persistent
 * Optional IDs use the explicit {@code optional-01} through
 * {@code optional-31} convention. A bit position is never inferred from list
 * order, hash iteration order or enum ordinal.</p>
 *
 * <p>Storage must apply a RunRecord at most once by enforcing its stable
 * {@code runId} uniqueness in the same database transaction that persists
 * the resulting ProgressDelta. This pure class deliberately does not retain
 * an unbounded history of processed run IDs.</p>
 *
 * <p>Compatible with the project's Android minSdk 24 baseline: no records,
 * {@code java.time}, {@code List.copyOf}, {@code HexFormat}, reflection or
 * Android APIs are used.</p>
 */
public final class ProgressRules {

    public static final int COMPLETION_BASE_SCORE = 1000;
    public static final int OPTIONAL_OBJECTIVE_SCORE = 250;
    public static final int MAX_TIME_BONUS_SCORE = 300;
    public static final int MAX_OPTIONAL_OBJECTIVES_PER_LEVEL = 31;

    public static final String LEVEL_01_ID = "level-01-awakening";
    public static final String LEVEL_02_ID = "level-02-precision-core";
    public static final String LEVEL_03_ID = "level-03-pulse-reactor";
    public static final String LEVEL_04_ID = "level-04-energy-vault";
    public static final String LEVEL_05_ID = "level-05-the-core";

    public static final String ACH_FIRST_TRACE = "first_trace";
    public static final String ACH_PRECISION_LEARNER = "precision_learner";
    public static final String ACH_GATE_RUNNER = "gate_runner";
    public static final String ACH_VAULT_FINDER = "vault_finder";
    public static final String ACH_CORE_COMPLETE = "core_complete";
    public static final String ACH_OBJECTIVE_HUNTER = "objective_hunter";
    public static final String ACH_PERSONAL_BEST = "personal_best";
    public static final String ACH_DAILY_SPARK = "daily_spark";

    public static final int COSMETIC_TRAIL = 0;
    public static final int COSMETIC_FRAME = 1;
    public static final int COSMETIC_EMBLEM = 2;

    private static final List<AchievementDef> ACHIEVEMENTS = buildAchievementDefinitions();
    private static final List<CosmeticDef> COSMETICS = buildCosmeticDefinitions();

    private ProgressRules() {
    }

    // =====================================================================
    // Run input
    // =====================================================================

    /** Immutable, storage-neutral description of one terminal or interrupted run. */
    public static final class RunRecord {
        private final String runId;
        private final String levelId;
        private final ReplayMode mode;
        private final RunResult result;
        private final long elapsedMs;
        private final boolean assisted;
        private final Set<String> collectedObjectiveIds;
        private final Set<String> collectedKeyIds;
        private final Set<String> passedGateIds;
        private final String dailyChallengeId;

        private RunRecord(Builder builder) {
            runId = requireStableText(builder.runId, "runId");
            levelId = requireStableText(builder.levelId, "levelId");
            mode = Objects.requireNonNull(builder.mode, "mode");
            result = Objects.requireNonNull(builder.result, "result");
            elapsedMs = Math.max(0L, builder.elapsedMs);
            assisted = builder.assisted;
            collectedObjectiveIds = immutableSortedSet(builder.collectedObjectiveIds,
                    "collectedObjectiveIds");
            collectedKeyIds = immutableSortedSet(builder.collectedKeyIds,
                    "collectedKeyIds");
            passedGateIds = immutableSortedSet(builder.passedGateIds, "passedGateIds");
            dailyChallengeId = builder.dailyChallengeId == null
                    ? "" : normalizeOptionalText(builder.dailyChallengeId);
            if (dailyChallengeId.length() > 96 || dailyChallengeId.indexOf('|') >= 0
                    || containsControl(dailyChallengeId)
                    || (!dailyChallengeId.isEmpty()
                    && !DailyChallenge.hasValidChallengeIdShape(dailyChallengeId))) {
                throw new IllegalArgumentException("dailyChallengeId has invalid format");
            }
            if (mode == ReplayMode.DAILY_CHALLENGE && result == RunResult.COMPLETED
                    && !DailyChallenge.hasValidChallengeIdShape(dailyChallengeId)) {
                throw new IllegalArgumentException(
                        "completed Daily Challenge requires a canonical DailyChallenge ID");
            }
            if (mode != ReplayMode.DAILY_CHALLENGE && !dailyChallengeId.isEmpty()) {
                throw new IllegalArgumentException(
                        "dailyChallengeId is only valid for DAILY_CHALLENGE mode");
            }
        }

        public String runId() { return runId; }
        public String levelId() { return levelId; }
        public ReplayMode mode() { return mode; }
        public RunResult result() { return result; }
        public long elapsedMs() { return elapsedMs; }
        public boolean assisted() { return assisted; }
        public Set<String> collectedObjectiveIds() { return collectedObjectiveIds; }
        public Set<String> collectedKeyIds() { return collectedKeyIds; }
        public Set<String> passedGateIds() { return passedGateIds; }
        public String dailyChallengeId() { return dailyChallengeId; }

        public static Builder builder() { return new Builder(); }

        /**
         * Builds a record from the Engine outcome. If Replay overflow made the
         * trace unavailable, callers may pass gate IDs observed from the
         * presentation event accumulator through the overload below.
         */
        public static RunRecord fromOutcome(String runId, LevelDefinition level,
                                            GameEngine.RunOutcome outcome,
                                            String dailyChallengeId) {
            return fromOutcome(runId, level, outcome, Collections.<String>emptySet(),
                    dailyChallengeId);
        }

        /** Builds a record while accepting gate IDs captured outside the optional Replay trace. */
        public static RunRecord fromOutcome(String runId, LevelDefinition level,
                                            GameEngine.RunOutcome outcome,
                                            Collection<String> observedPassedGateIds,
                                            String dailyChallengeId) {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(outcome, "outcome");
            TreeSet<String> passed = new TreeSet<String>();
            if (observedPassedGateIds != null) passed.addAll(observedPassedGateIds);
            ReplayTrace trace = outcome.trace();
            if (trace != null) {
                for (ReplaySystem.TraceEvent event : trace.events()) {
                    if ("GATE_PASS".equals(event.type()) && !event.detail().isEmpty()) {
                        passed.add(event.detail());
                    }
                }
            }
            return builder()
                    .runId(runId)
                    .levelId(level.levelId())
                    .mode(outcome.mode())
                    .result(outcome.result())
                    .elapsedMs(outcome.elapsedMs())
                    .assisted(outcome.assisted())
                    .collectedObjectiveIds(outcome.collectedObjectiveIds())
                    .collectedKeyIds(outcome.collectedKeyIds())
                    .passedGateIds(passed)
                    .dailyChallengeId(dailyChallengeId)
                    .build();
        }

        public static final class Builder {
            private String runId;
            private String levelId;
            private ReplayMode mode;
            private RunResult result;
            private long elapsedMs;
            private boolean assisted;
            private Collection<String> collectedObjectiveIds = Collections.emptySet();
            private Collection<String> collectedKeyIds = Collections.emptySet();
            private Collection<String> passedGateIds = Collections.emptySet();
            private String dailyChallengeId;

            public Builder runId(String value) { runId = value; return this; }
            public Builder levelId(String value) { levelId = value; return this; }
            public Builder mode(ReplayMode value) { mode = value; return this; }
            public Builder result(RunResult value) { result = value; return this; }
            public Builder elapsedMs(long value) { elapsedMs = value; return this; }
            public Builder assisted(boolean value) { assisted = value; return this; }
            public Builder collectedObjectiveIds(Collection<String> value) {
                collectedObjectiveIds = value; return this;
            }
            public Builder collectedKeyIds(Collection<String> value) {
                collectedKeyIds = value; return this;
            }
            public Builder passedGateIds(Collection<String> value) {
                passedGateIds = value; return this;
            }
            public Builder dailyChallengeId(String value) { dailyChallengeId = value; return this; }
            public RunRecord build() { return new RunRecord(this); }
        }
    }

    // =====================================================================
    // Immutable progress values
    // =====================================================================

    /** Progress for one Campaign Level. A zero best time/score means no record. */
    public static final class LevelProgress {
        private final boolean completed;
        private final int stars;
        private final long bestUnassistedTimeMs;
        private final int bestUnassistedScore;
        private final int attempts;
        private final int optionalMask;
        private final int optionalFullMask;
        private final boolean hasOptionalObjectives;

        private LevelProgress(boolean completed, int stars, long bestUnassistedTimeMs,
                              int bestUnassistedScore, int attempts, int optionalMask,
                              int optionalFullMask, boolean hasOptionalObjectives) {
            this.completed = completed;
            this.stars = stars;
            this.bestUnassistedTimeMs = bestUnassistedTimeMs;
            this.bestUnassistedScore = bestUnassistedScore;
            this.attempts = attempts;
            if (stars < 0 || stars > 3 || bestUnassistedTimeMs < 0L
                    || bestUnassistedScore < 0 || attempts < 0
                    || optionalMask < 0 || optionalFullMask < 0
                    || (optionalMask & ~optionalFullMask) != 0
                    || hasOptionalObjectives != (optionalFullMask != 0)
                    || (!completed && stars != 0)
                    || (completed && stars == 0)) {
                throw new IllegalArgumentException("invalid LevelProgress values");
            }
            this.optionalMask = optionalMask;
            this.optionalFullMask = optionalFullMask;
            this.hasOptionalObjectives = hasOptionalObjectives;
        }

        /** Rehydrates a validated immutable value from local persistence. */
        public static LevelProgress restore(boolean completed, int stars,
                                            long bestUnassistedTimeMs,
                                            int bestUnassistedScore, int attempts,
                                            int optionalMask, int optionalFullMask,
                                            boolean hasOptionalObjectives) {
            return new LevelProgress(completed, stars, bestUnassistedTimeMs,
                    bestUnassistedScore, attempts, optionalMask, optionalFullMask,
                    hasOptionalObjectives);
        }

        private static LevelProgress empty(int optionalFullMask, boolean hasOptional) {
            return new LevelProgress(false, 0, 0L, 0, 0, 0,
                    optionalFullMask, hasOptional);
        }

        public boolean completed() { return completed; }
        public int stars() { return stars; }
        public long bestUnassistedTimeMs() { return bestUnassistedTimeMs; }
        public int bestUnassistedScore() { return bestUnassistedScore; }
        public int attempts() { return attempts; }
        public int optionalMask() { return optionalMask; }
        public int optionalFullMask() { return optionalFullMask; }
        public boolean hasOptionalObjectives() { return hasOptionalObjectives; }
        public boolean allOptionalsCollected() {
            return hasOptionalObjectives && (optionalMask & optionalFullMask) == optionalFullMask;
        }
    }

    /** Mode-specific best record, deliberately separate from Campaign stars and unlocks. */
    public static final class ModeBest {
        private final long bestElapsedMs;
        private final int completions;

        private ModeBest(long bestElapsedMs, int completions) {
            if (bestElapsedMs < 0L || completions < 0) {
                throw new IllegalArgumentException("invalid ModeBest values");
            }
            this.bestElapsedMs = bestElapsedMs;
            this.completions = completions;
        }

        /** Rehydrates a validated immutable value from local persistence. */
        public static ModeBest restore(long bestElapsedMs, int completions) {
            return new ModeBest(bestElapsedMs, completions);
        }

        public long bestElapsedMs() { return bestElapsedMs; }
        public int completions() { return completions; }
    }

    /** Immutable aggregate of locally persisted progress values. */
    public static final class ProgressSnapshot {
        private final Map<String, LevelProgress> campaignLevels;
        private final Map<String, ModeBest> modeRecords;
        private final Set<String> unlockedLevelIds;
        private final Set<String> completedDailyChallengeIds;
        private final Set<String> gatePassedLevelIds;
        private final Set<String> keyCollectedLevelIds;
        private final Set<String> unlockedAchievementIds;
        private final Set<String> unlockedCosmeticIds;
        private final int campaignCompletions;
        private final int successfulDailyCompletions;
        private final int personalBestCount;

        private ProgressSnapshot(Map<String, LevelProgress> campaignLevels,
                                 Map<String, ModeBest> modeRecords,
                                 Collection<String> unlockedLevelIds,
                                 Collection<String> completedDailyChallengeIds,
                                 Collection<String> gatePassedLevelIds,
                                 Collection<String> keyCollectedLevelIds,
                                 Collection<String> unlockedAchievementIds,
                                 Collection<String> unlockedCosmeticIds,
                                 int campaignCompletions, int successfulDailyCompletions,
                                 int personalBestCount) {
            this.campaignLevels = immutableSortedMap(campaignLevels);
            this.modeRecords = immutableSortedMap(modeRecords);
            this.unlockedLevelIds = immutableSortedSet(unlockedLevelIds, "unlockedLevelIds");
            this.completedDailyChallengeIds = immutableSortedSet(completedDailyChallengeIds,
                    "completedDailyChallengeIds");
            this.gatePassedLevelIds = immutableSortedSet(gatePassedLevelIds,
                    "gatePassedLevelIds");
            this.keyCollectedLevelIds = immutableSortedSet(keyCollectedLevelIds,
                    "keyCollectedLevelIds");
            this.unlockedAchievementIds = immutableSortedSet(unlockedAchievementIds,
                    "unlockedAchievementIds");
            this.unlockedCosmeticIds = immutableSortedSet(unlockedCosmeticIds,
                    "unlockedCosmeticIds");
            if (campaignCompletions < 0 || successfulDailyCompletions < 0
                    || personalBestCount < 0
                    || successfulDailyCompletions != this.completedDailyChallengeIds.size()) {
                throw new IllegalArgumentException("inconsistent progress counters");
            }
            this.campaignCompletions = campaignCompletions;
            this.successfulDailyCompletions = successfulDailyCompletions;
            this.personalBestCount = personalBestCount;
        }

        /** Creates a fresh snapshot and unlocks only the first supplied Campaign Level. */
        public static ProgressSnapshot empty(Catalog catalog) {
            Objects.requireNonNull(catalog, "catalog");
            TreeSet<String> unlocked = new TreeSet<String>();
            if (!catalog.isEmpty()) unlocked.add(catalog.get(0).levelId());
            Set<String> defaults = new TreeSet<String>();
            for (CosmeticDef cosmetic : COSMETICS) {
                if (cosmetic.defaultUnlocked()) defaults.add(cosmetic.id());
            }
            return new ProgressSnapshot(Collections.<String, LevelProgress>emptyMap(),
                    Collections.<String, ModeBest>emptyMap(), unlocked,
                    Collections.<String>emptySet(), Collections.<String>emptySet(),
                    Collections.<String>emptySet(), Collections.<String>emptySet(),
                    defaults, 0, 0, 0);
        }

        public static ProgressSnapshot restore(Map<String, LevelProgress> campaignLevels,
                                               Map<String, ModeBest> modeRecords,
                                               Collection<String> unlockedLevelIds,
                                               Collection<String> completedDailyChallengeIds,
                                               Collection<String> gatePassedLevelIds,
                                               Collection<String> keyCollectedLevelIds,
                                               Collection<String> unlockedAchievementIds,
                                               Collection<String> unlockedCosmeticIds,
                                               int campaignCompletions,
                                               int successfulDailyCompletions,
                                               int personalBestCount) {
            return new ProgressSnapshot(campaignLevels, modeRecords, unlockedLevelIds,
                    completedDailyChallengeIds, gatePassedLevelIds, keyCollectedLevelIds,
                    unlockedAchievementIds,
                    unlockedCosmeticIds, campaignCompletions, successfulDailyCompletions,
                    personalBestCount);
        }

        public Map<String, LevelProgress> campaignLevels() { return campaignLevels; }
        public Map<String, ModeBest> modeRecords() { return modeRecords; }
        public Set<String> unlockedLevelIds() { return unlockedLevelIds; }
        public Set<String> completedDailyChallengeIds() { return completedDailyChallengeIds; }
        public Set<String> gatePassedLevelIds() { return gatePassedLevelIds; }
        public Set<String> keyCollectedLevelIds() { return keyCollectedLevelIds; }
        public Set<String> unlockedAchievementIds() { return unlockedAchievementIds; }
        public Set<String> unlockedCosmeticIds() { return unlockedCosmeticIds; }
        public int campaignCompletions() { return campaignCompletions; }
        public int successfulDailyCompletions() { return successfulDailyCompletions; }
        public int personalBestCount() { return personalBestCount; }

        public LevelProgress level(String levelId) { return campaignLevels.get(levelId); }

        public boolean isLevelUnlocked(String levelId) {
            return unlockedLevelIds.contains(levelId);
        }

        public boolean isAchievementUnlocked(String achievementId) {
            return unlockedAchievementIds.contains(achievementId);
        }

        public boolean isCosmeticUnlocked(String cosmeticId) {
            return unlockedCosmeticIds.contains(cosmeticId);
        }

        public ModeBest modeBest(String levelId, ReplayMode mode, String dailyChallengeId) {
            return modeRecords.get(modeRecordKey(levelId, mode, dailyChallengeId));
        }
    }

    /** Immutable summary passed to Achievement evaluation and UI. */
    public static final class StatsView {
        private final ProgressSnapshot snapshot;
        private final int objectiveHunterSatisfiedLevels;
        private final int objectiveHunterEligibleLevels;

        private StatsView(ProgressSnapshot snapshot, int satisfied, int eligible) {
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.objectiveHunterSatisfiedLevels = satisfied;
            this.objectiveHunterEligibleLevels = eligible;
        }

        public ProgressSnapshot snapshot() { return snapshot; }
        public int campaignCompletions() { return snapshot.campaignCompletions(); }
        public int successfulDailyCompletions() { return snapshot.successfulDailyCompletions(); }
        public int distinctDailyChallenges() { return snapshot.completedDailyChallengeIds().size(); }
        public int personalBestCount() { return snapshot.personalBestCount(); }
        public int objectiveHunterSatisfiedLevels() { return objectiveHunterSatisfiedLevels; }
        public int objectiveHunterEligibleLevels() { return objectiveHunterEligibleLevels; }
    }

    /** Complete immutable result of evaluating one run. */
    public static final class ProgressDelta {
        private final ProgressSnapshot snapshot;
        private final int scoreAwarded;
        private final int starsAwarded;
        private final boolean campaignCompletionAdded;
        private final boolean bestTimeImproved;
        private final boolean bestScoreImproved;
        private final boolean dailyCompletionAdded;
        private final Set<String> newlyUnlockedLevelIds;
        private final Set<String> newlyUnlockedAchievementIds;
        private final Set<String> newlyUnlockedCosmeticIds;

        private ProgressDelta(ProgressSnapshot snapshot, int scoreAwarded, int starsAwarded,
                              boolean campaignCompletionAdded, boolean bestTimeImproved,
                              boolean bestScoreImproved, boolean dailyCompletionAdded,
                              Collection<String> newlyUnlockedLevelIds,
                              Collection<String> newlyUnlockedAchievementIds,
                              Collection<String> newlyUnlockedCosmeticIds) {
            this.snapshot = snapshot;
            this.scoreAwarded = scoreAwarded;
            this.starsAwarded = starsAwarded;
            this.campaignCompletionAdded = campaignCompletionAdded;
            this.bestTimeImproved = bestTimeImproved;
            this.bestScoreImproved = bestScoreImproved;
            this.dailyCompletionAdded = dailyCompletionAdded;
            this.newlyUnlockedLevelIds = immutableSortedSet(newlyUnlockedLevelIds,
                    "newlyUnlockedLevelIds");
            this.newlyUnlockedAchievementIds = immutableSortedSet(newlyUnlockedAchievementIds,
                    "newlyUnlockedAchievementIds");
            this.newlyUnlockedCosmeticIds = immutableSortedSet(newlyUnlockedCosmeticIds,
                    "newlyUnlockedCosmeticIds");
        }

        public ProgressSnapshot snapshot() { return snapshot; }
        public int scoreAwarded() { return scoreAwarded; }
        public int starsAwarded() { return starsAwarded; }
        public boolean campaignCompletionAdded() { return campaignCompletionAdded; }
        public boolean bestTimeImproved() { return bestTimeImproved; }
        public boolean bestScoreImproved() { return bestScoreImproved; }
        public boolean dailyCompletionAdded() { return dailyCompletionAdded; }
        public Set<String> newlyUnlockedLevelIds() { return newlyUnlockedLevelIds; }
        public Set<String> newlyUnlockedAchievementIds() { return newlyUnlockedAchievementIds; }
        public Set<String> newlyUnlockedCosmeticIds() { return newlyUnlockedCosmeticIds; }
    }

    // =====================================================================
    // Public pure rules
    // =====================================================================

    /** Campaign stars: 1 for completion, 2 at/below Par, 3 at/below Par with all Optionals. */
    public static int starsFor(LevelDefinition level, Collection<String> collectedObjectiveIds,
                               long elapsedMs, boolean completed) {
        Objects.requireNonNull(level, "level");
        if (!completed) return 0;
        long elapsed = Math.max(0L, elapsedMs);
        long par = requirePositivePar(level.parTimeMs());
        if (elapsed > par) return 1;
        return allOptionalsCollected(level, collectedObjectiveIds) ? 3 : 2;
    }

    /** Campaign score: 1000 + 250 per Optional + floor(300 * clamped time ratio). */
    public static int scoreFor(LevelDefinition level, Collection<String> collectedObjectiveIds,
                               long elapsedMs) {
        Objects.requireNonNull(level, "level");
        long par = requirePositivePar(level.parTimeMs());
        long elapsed = Math.max(0L, elapsedMs);
        int optionalCount = countCollectedOptionals(level, collectedObjectiveIds);
        long timeRemaining = elapsed >= par ? 0L : par - elapsed;
        double ratio = (double) timeRemaining / (double) par;
        int timeBonus = (int) Math.floor(MAX_TIME_BONUS_SCORE * clamp01(ratio));
        long total = (long) COMPLETION_BASE_SCORE
                + (long) OPTIONAL_OBJECTIVE_SCORE * optionalCount + timeBonus;
        if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) total;
    }

    /** Explicit stable bit mapping; accepts only optional-01 through optional-31. */
    public static int optionalBitIndex(String objectiveId) {
        if (objectiveId == null || objectiveId.length() != 11
                || !objectiveId.startsWith("optional-")) {
            throw new IllegalArgumentException(
                    "Optional ID must use optional-01 through optional-31: " + objectiveId);
        }
        char tens = objectiveId.charAt(9);
        char ones = objectiveId.charAt(10);
        if (tens < '0' || tens > '9' || ones < '0' || ones > '9') {
            throw new IllegalArgumentException("Optional ID suffix must be two decimal digits");
        }
        int number = (tens - '0') * 10 + (ones - '0');
        if (number < 1 || number > MAX_OPTIONAL_OBJECTIVES_PER_LEVEL) {
            throw new IllegalArgumentException("Optional ID index must be in [01,31]");
        }
        return number - 1;
    }

    public static int fullOptionalMask(LevelDefinition level) {
        Objects.requireNonNull(level, "level");
        int mask = 0;
        for (Objective objective : level.objectives()) {
            if (objective.type() == ObjectiveType.OPTIONAL) {
                mask |= 1 << optionalBitIndex(objective.id());
            }
        }
        return mask;
    }

    public static int collectedOptionalMask(LevelDefinition level,
                                            Collection<String> collectedObjectiveIds) {
        Objects.requireNonNull(level, "level");
        Set<String> collected = validatedObjectiveIds(level, collectedObjectiveIds);
        int mask = 0;
        for (Objective objective : level.objectives()) {
            if (objective.type() == ObjectiveType.OPTIONAL && collected.contains(objective.id())) {
                mask |= 1 << optionalBitIndex(objective.id());
            }
        }
        return mask;
    }

    public static boolean allOptionalsCollected(LevelDefinition level,
                                                Collection<String> collectedObjectiveIds) {
        Objects.requireNonNull(level, "level");
        int full = fullOptionalMask(level);
        int collected = collectedOptionalMask(level, collectedObjectiveIds);
        return (collected & full) == full;
    }

    /**
     * Evaluates a run against a prior snapshot and ordered campaign catalog.
     * The operation is deterministic and has no side effects.
     */
    public static ProgressDelta evaluate(Catalog catalog, ProgressSnapshot prior,
                                         RunRecord run) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(prior, "prior");
        Objects.requireNonNull(run, "run");
        validatePriorSnapshot(catalog, prior);
        if (!catalog.contains(run.levelId())) {
            throw new IllegalArgumentException("run references unknown level: " + run.levelId());
        }
        LevelDefinition level = catalog.byId(run.levelId());
        validateRunReferences(level, run);

        TreeMap<String, LevelProgress> campaignLevels = new TreeMap<String, LevelProgress>(
                prior.campaignLevels());
        TreeMap<String, ModeBest> modeRecords = new TreeMap<String, ModeBest>(prior.modeRecords());
        TreeSet<String> unlockedLevels = new TreeSet<String>(prior.unlockedLevelIds());
        TreeSet<String> dailyIds = new TreeSet<String>(prior.completedDailyChallengeIds());
        TreeSet<String> gatePassed = new TreeSet<String>(prior.gatePassedLevelIds());
        TreeSet<String> keysCollected = new TreeSet<String>(prior.keyCollectedLevelIds());
        TreeSet<String> unlockedAchievements = new TreeSet<String>(prior.unlockedAchievementIds());
        TreeSet<String> unlockedCosmetics = new TreeSet<String>(prior.unlockedCosmeticIds());

        int scoreAwarded = 0;
        int starsAwarded = 0;
        boolean campaignCompletionAdded = false;
        boolean bestTimeImproved = false;
        boolean bestScoreImproved = false;
        boolean dailyCompletionAdded = false;
        boolean personalBestIncrementMarker = false;
        int campaignCompletionsWorking = prior.campaignCompletions();
        TreeSet<String> newlyUnlockedLevels = new TreeSet<String>();
        TreeSet<String> newlyUnlockedAchievements = new TreeSet<String>();
        TreeSet<String> newlyUnlockedCosmetics = new TreeSet<String>();

        if (run.mode() == ReplayMode.CAMPAIGN) {
            LevelProgress previous = campaignLevels.get(run.levelId());
            int fullMask = fullOptionalMask(level);
            boolean hasOptionals = fullMask != 0;
            if (previous == null) previous = LevelProgress.empty(fullMask, hasOptionals);
            int attempts = safeIncrement(previous.attempts());

            boolean completed = run.result() == RunResult.COMPLETED;
            int runOptionalMask = completed
                    ? collectedOptionalMask(level, run.collectedObjectiveIds()) : 0;
            int mergedMask = previous.optionalMask() | runOptionalMask;
            int stars = completed ? starsFor(level, run.collectedObjectiveIds(),
                    run.elapsedMs(), true) : 0;
            int bestStars = Math.max(previous.stars(), stars);
            starsAwarded = stars;
            boolean nowCompleted = previous.completed() || completed;
            long bestTime = previous.bestUnassistedTimeMs();
            int bestScore = previous.bestUnassistedScore();

            if (completed) {
                scoreAwarded = scoreFor(level, run.collectedObjectiveIds(), run.elapsedMs());
                campaignCompletionAdded = true;
                int index = indexOfLevel(catalog, run.levelId());
                if (index >= 0 && index + 1 < catalog.size()) {
                    String nextId = catalog.get(index + 1).levelId();
                    if (unlockedLevels.add(nextId)) newlyUnlockedLevels.add(nextId);
                }
            }

            if (completed && !run.assisted() && run.elapsedMs() > 0L) {
                if (bestTime == 0L || run.elapsedMs() < bestTime) {
                    bestTime = run.elapsedMs();
                    bestTimeImproved = true;
                    // The count records distinct level PBs, not the number of PB updates.
                    if (previous.bestUnassistedTimeMs() == 0L) {
                        personalBestIncrementMarker = true;
                    }
                }
            }
            if (completed && !run.assisted() && scoreAwarded > bestScore) {
                bestScore = scoreAwarded;
                bestScoreImproved = true;
            }

            campaignLevels.put(run.levelId(), new LevelProgress(nowCompleted, bestStars,
                    bestTime, bestScore, attempts, mergedMask, fullMask, hasOptionals));
            if (completed) {
                campaignCompletionsWorking = safeIncrement(campaignCompletionsWorking);
            }
            if (!run.passedGateIds().isEmpty() && LEVEL_03_ID.equals(run.levelId())) {
                gatePassed.add(run.levelId());
            }
            if (completed && !run.collectedKeyIds().isEmpty()
                    && LEVEL_04_ID.equals(run.levelId())) {
                keysCollected.add(run.levelId());
            }
        } else if (isModeRecordEligible(run.mode()) && run.result() == RunResult.COMPLETED) {
            String key = modeRecordKey(run.levelId(), run.mode(), run.dailyChallengeId());
            ModeBest old = modeRecords.get(key);
            long oldTime = old == null ? 0L : old.bestElapsedMs();
            int completions = old == null ? 0 : old.completions();
            boolean betterTime = run.elapsedMs() > 0L && (oldTime == 0L || run.elapsedMs() < oldTime);
            long bestTime = betterTime ? run.elapsedMs() : oldTime;
            modeRecords.put(key, new ModeBest(bestTime, safeIncrement(completions)));
            bestTimeImproved = betterTime;
            if (run.mode() == ReplayMode.DAILY_CHALLENGE) {
                dailyCompletionAdded = dailyIds.add(run.dailyChallengeId());
            }
        }

        int campaignCompletions = campaignCompletionsWorking;
        int personalBestCount = safeAdd(prior.personalBestCount(),
                personalBestIncrementMarker ? 1 : 0);

        ProgressSnapshot preliminary = new ProgressSnapshot(campaignLevels, modeRecords,
                unlockedLevels, dailyIds, gatePassed, keysCollected, unlockedAchievements,
                unlockedCosmetics, campaignCompletions, successfulDailyCount(dailyIds), personalBestCount);
        StatsView stats = statsView(catalog, preliminary);

        for (AchievementDef definition : ACHIEVEMENTS) {
            if (!unlockedAchievements.contains(definition.id())
                    && achievementUnlocked(definition.id(), stats, catalog)) {
                unlockedAchievements.add(definition.id());
                newlyUnlockedAchievements.add(definition.id());
            }
        }

        ProgressSnapshot afterAchievements = new ProgressSnapshot(campaignLevels, modeRecords,
                unlockedLevels, dailyIds, gatePassed, keysCollected, unlockedAchievements,
                unlockedCosmetics, campaignCompletions, successfulDailyCount(dailyIds), personalBestCount);
        StatsView finalStats = statsView(catalog, afterAchievements);
        for (CosmeticDef cosmetic : COSMETICS) {
            if (!unlockedCosmetics.contains(cosmetic.id())
                    && cosmeticUnlocked(cosmetic, finalStats)) {
                unlockedCosmetics.add(cosmetic.id());
                newlyUnlockedCosmetics.add(cosmetic.id());
            }
        }

        ProgressSnapshot result = new ProgressSnapshot(campaignLevels, modeRecords,
                unlockedLevels, dailyIds, gatePassed, keysCollected, unlockedAchievements,
                unlockedCosmetics, campaignCompletions, successfulDailyCount(dailyIds), personalBestCount);
        if (result.unlockedLevelIds().isEmpty() && !catalog.isEmpty()) {
            // Recover a missing first-level unlock from older/empty snapshots.
            unlockedLevels.add(catalog.get(0).levelId());
            result = new ProgressSnapshot(campaignLevels, modeRecords, unlockedLevels,
                    dailyIds, gatePassed, keysCollected, unlockedAchievements, unlockedCosmetics,
                    campaignCompletions, successfulDailyCount(dailyIds), personalBestCount);
        }
        return new ProgressDelta(result, scoreAwarded, starsAwarded,
                campaignCompletionAdded, bestTimeImproved, bestScoreImproved,
                dailyCompletionAdded, newlyUnlockedLevels, newlyUnlockedAchievements,
                newlyUnlockedCosmetics);
    }

    public static StatsView statsView(Catalog catalog, ProgressSnapshot snapshot) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(snapshot, "snapshot");
        int eligible = 0;
        int satisfied = 0;
        for (LevelDefinition level : catalog.all()) {
            int fullMask = fullOptionalMask(level);
            if (fullMask == 0) continue;
            eligible++;
            LevelProgress progress = snapshot.level(level.levelId());
            if (progress != null && progress.completed()
                    && (progress.optionalMask() & fullMask) == fullMask) {
                satisfied++;
            }
        }
        return new StatsView(snapshot, satisfied, eligible);
    }

    public static boolean achievementUnlocked(String achievementId, StatsView stats,
                                              Catalog catalog) {
        if (achievementId == null || stats == null || catalog == null) return false;
        ProgressSnapshot snapshot = stats.snapshot();
        switch (achievementId) {
            case ACH_FIRST_TRACE:
                return completed(snapshot, LEVEL_01_ID);
            case ACH_PRECISION_LEARNER:
                return completed(snapshot, LEVEL_02_ID);
            case ACH_GATE_RUNNER:
                return snapshot.gatePassedLevelIds().contains(LEVEL_03_ID);
            case ACH_VAULT_FINDER:
                return completed(snapshot, LEVEL_04_ID)
                        && snapshot.keyCollectedLevelIds().contains(LEVEL_04_ID);
            case ACH_CORE_COMPLETE:
                return completed(snapshot, LEVEL_05_ID);
            case ACH_OBJECTIVE_HUNTER:
                return stats.objectiveHunterEligibleLevels() >= 3
                        && stats.objectiveHunterSatisfiedLevels()
                        == stats.objectiveHunterEligibleLevels();
            case ACH_PERSONAL_BEST:
                return snapshot.personalBestCount() > 0;
            case ACH_DAILY_SPARK:
                return snapshot.completedDailyChallengeIds().size() >= 3;
            default:
                return false;
        }
    }

    public static int achievementProgress(String achievementId, StatsView stats,
                                          Catalog catalog) {
        if (achievementId == null || stats == null || catalog == null) return 0;
        ProgressSnapshot snapshot = stats.snapshot();
        switch (achievementId) {
            case ACH_FIRST_TRACE: return completed(snapshot, LEVEL_01_ID) ? 1 : 0;
            case ACH_PRECISION_LEARNER: return completed(snapshot, LEVEL_02_ID) ? 1 : 0;
            case ACH_GATE_RUNNER: return snapshot.gatePassedLevelIds().contains(LEVEL_03_ID) ? 1 : 0;
            case ACH_VAULT_FINDER: return completed(snapshot, LEVEL_04_ID)
                    && snapshot.keyCollectedLevelIds().contains(LEVEL_04_ID) ? 1 : 0;
            case ACH_CORE_COMPLETE: return completed(snapshot, LEVEL_05_ID) ? 1 : 0;
            case ACH_OBJECTIVE_HUNTER: return stats.objectiveHunterSatisfiedLevels();
            case ACH_PERSONAL_BEST: return snapshot.personalBestCount() > 0 ? 1 : 0;
            case ACH_DAILY_SPARK: return Math.min(3, snapshot.completedDailyChallengeIds().size());
            default: return 0;
        }
    }

    public static List<AchievementDef> achievements() { return ACHIEVEMENTS; }
    public static List<CosmeticDef> cosmetics() { return COSMETICS; }

    public static boolean cosmeticUnlocked(CosmeticDef cosmetic, StatsView stats) {
        if (cosmetic == null || stats == null) return false;
        if (cosmetic.defaultUnlocked()) return true;
        if (!cosmetic.achievementId().isEmpty()) {
            return stats.snapshot().unlockedAchievementIds().contains(cosmetic.achievementId());
        }
        return cosmetic.completionsNeeded() > 0
                && stats.campaignCompletions() >= cosmetic.completionsNeeded();
    }

    /** Returns the next Campaign ID or the empty string when the level is last/unknown. */
    public static String nextLevelId(Catalog catalog, String levelId) {
        Objects.requireNonNull(catalog, "catalog");
        int index = indexOfLevel(catalog, levelId);
        return index >= 0 && index + 1 < catalog.size()
                ? catalog.get(index + 1).levelId() : "";
    }

    public static boolean isCampaignProgressMode(ReplayMode mode) {
        return mode == ReplayMode.CAMPAIGN;
    }

    // =====================================================================
    // Definitions
    // =====================================================================

    public static final class AchievementDef {
        private final String id;
        private final String nameKey;
        private final String descriptionKey;
        private final int target;

        private AchievementDef(String id, String nameKey, String descriptionKey, int target) {
            this.id = id;
            this.nameKey = nameKey;
            this.descriptionKey = descriptionKey;
            this.target = target;
        }
        public String id() { return id; }
        public String nameKey() { return nameKey; }
        public String descriptionKey() { return descriptionKey; }
        public int target() { return target; }
    }

    public static final class CosmeticDef {
        private final String id;
        private final int kind;
        private final String nameKey;
        private final String achievementId;
        private final int completionsNeeded;
        private final boolean defaultUnlocked;

        private CosmeticDef(String id, int kind, String nameKey, String achievementId,
                            int completionsNeeded, boolean defaultUnlocked) {
            this.id = id;
            this.kind = kind;
            this.nameKey = nameKey;
            this.achievementId = achievementId == null ? "" : achievementId;
            this.completionsNeeded = completionsNeeded;
            this.defaultUnlocked = defaultUnlocked;
        }
        public String id() { return id; }
        public int kind() { return kind; }
        public String nameKey() { return nameKey; }
        public String achievementId() { return achievementId; }
        public int completionsNeeded() { return completionsNeeded; }
        public boolean defaultUnlocked() { return defaultUnlocked; }
    }

    private static List<AchievementDef> buildAchievementDefinitions() {
        ArrayList<AchievementDef> values = new ArrayList<AchievementDef>();
        values.add(new AchievementDef(ACH_FIRST_TRACE, "achievement.first_trace.name",
                "achievement.first_trace.desc", 1));
        values.add(new AchievementDef(ACH_PRECISION_LEARNER, "achievement.precision_learner.name",
                "achievement.precision_learner.desc", 1));
        values.add(new AchievementDef(ACH_GATE_RUNNER, "achievement.gate_runner.name",
                "achievement.gate_runner.desc", 1));
        values.add(new AchievementDef(ACH_VAULT_FINDER, "achievement.vault_finder.name",
                "achievement.vault_finder.desc", 1));
        values.add(new AchievementDef(ACH_CORE_COMPLETE, "achievement.core_complete.name",
                "achievement.core_complete.desc", 1));
        values.add(new AchievementDef(ACH_OBJECTIVE_HUNTER, "achievement.objective_hunter.name",
                "achievement.objective_hunter.desc", 3));
        values.add(new AchievementDef(ACH_PERSONAL_BEST, "achievement.personal_best.name",
                "achievement.personal_best.desc", 1));
        values.add(new AchievementDef(ACH_DAILY_SPARK, "achievement.daily_spark.name",
                "achievement.daily_spark.desc", 3));
        return Collections.unmodifiableList(values);
    }

    private static List<CosmeticDef> buildCosmeticDefinitions() {
        ArrayList<CosmeticDef> values = new ArrayList<CosmeticDef>();
        values.add(new CosmeticDef("trail.mint", COSMETIC_TRAIL, "cosmetic.trail.mint", null, 0, true));
        values.add(new CosmeticDef("trail.violet", COSMETIC_TRAIL, "cosmetic.trail.violet", ACH_PRECISION_LEARNER, 0, false));
        values.add(new CosmeticDef("trail.amber", COSMETIC_TRAIL, "cosmetic.trail.amber", ACH_GATE_RUNNER, 0, false));
        values.add(new CosmeticDef("trail.coral", COSMETIC_TRAIL, "cosmetic.trail.coral", ACH_CORE_COMPLETE, 0, false));
        values.add(new CosmeticDef("trail.ice", COSMETIC_TRAIL, "cosmetic.trail.ice", ACH_OBJECTIVE_HUNTER, 0, false));
        values.add(new CosmeticDef("frame.none", COSMETIC_FRAME, "cosmetic.frame.none", null, 0, true));
        values.add(new CosmeticDef("frame.ring", COSMETIC_FRAME, "cosmetic.frame.ring", ACH_FIRST_TRACE, 0, false));
        values.add(new CosmeticDef("frame.hex", COSMETIC_FRAME, "cosmetic.frame.hex", ACH_VAULT_FINDER, 0, false));
        values.add(new CosmeticDef("frame.pulse", COSMETIC_FRAME, "cosmetic.frame.pulse", ACH_DAILY_SPARK, 0, false));
        values.add(new CosmeticDef("frame.prism", COSMETIC_FRAME, "cosmetic.frame.prism", null, 10, false));
        values.add(new CosmeticDef("emblem.first_trace", COSMETIC_EMBLEM, "cosmetic.emblem.first_trace", ACH_FIRST_TRACE, 0, false));
        values.add(new CosmeticDef("emblem.precision_learner", COSMETIC_EMBLEM, "cosmetic.emblem.precision_learner", ACH_PRECISION_LEARNER, 0, false));
        values.add(new CosmeticDef("emblem.gate_runner", COSMETIC_EMBLEM, "cosmetic.emblem.gate_runner", ACH_GATE_RUNNER, 0, false));
        values.add(new CosmeticDef("emblem.vault_finder", COSMETIC_EMBLEM, "cosmetic.emblem.vault_finder", ACH_VAULT_FINDER, 0, false));
        values.add(new CosmeticDef("emblem.core_complete", COSMETIC_EMBLEM, "cosmetic.emblem.core_complete", ACH_CORE_COMPLETE, 0, false));
        values.add(new CosmeticDef("emblem.objective_hunter", COSMETIC_EMBLEM, "cosmetic.emblem.objective_hunter", ACH_OBJECTIVE_HUNTER, 0, false));
        values.add(new CosmeticDef("emblem.personal_best", COSMETIC_EMBLEM, "cosmetic.emblem.personal_best", ACH_PERSONAL_BEST, 0, false));
        values.add(new CosmeticDef("emblem.daily_spark", COSMETIC_EMBLEM, "cosmetic.emblem.daily_spark", ACH_DAILY_SPARK, 0, false));
        return Collections.unmodifiableList(values);
    }

    // =====================================================================
    // Internal helpers
    // =====================================================================

    private static void validatePriorSnapshot(Catalog catalog, ProgressSnapshot snapshot) {
        Set<String> catalogIds = new HashSet<String>();
        for (LevelDefinition level : catalog.all()) catalogIds.add(level.levelId());
        if (!catalogIds.containsAll(snapshot.unlockedLevelIds())) {
            throw new IllegalArgumentException("snapshot unlocks a level outside the catalog");
        }
        if (!catalog.isEmpty() && !snapshot.unlockedLevelIds().contains(catalog.get(0).levelId())) {
            throw new IllegalArgumentException("snapshot must keep the first Campaign level unlocked");
        }
        for (String dailyId : snapshot.completedDailyChallengeIds()) {
            if (!DailyChallenge.hasValidChallengeIdShape(dailyId)) {
                throw new IllegalArgumentException("snapshot contains an invalid Daily Challenge ID");
            }
        }
        for (Map.Entry<String, ModeBest> entry : snapshot.modeRecords().entrySet()) {
            boolean matched = false;
            for (LevelDefinition level : catalog.all()) {
                if (!catalog.contains(level.levelId())) continue;
                for (ReplayMode mode : new ReplayMode[] {
                        ReplayMode.TIME_ATTACK, ReplayMode.GHOST_RACE,
                        ReplayMode.DAILY_CHALLENGE }) {
                    String prefix = level.levelId() + "|" + mode.code() + "|";
                    if (!entry.getKey().startsWith(prefix)) continue;
                    String suffix = entry.getKey().substring(prefix.length());
                    if (mode == ReplayMode.DAILY_CHALLENGE) {
                        matched = DailyChallenge.hasValidChallengeIdShape(suffix)
                                && level.levelId().equals(
                                DailyChallenge.levelIdFromChallengeId(suffix));
                    } else {
                        matched = suffix.isEmpty();
                    }
                    if (matched && entry.getValue().completions() > 0) break;
                    matched = false;
                }
                if (matched) break;
            }
            if (!matched) {
                throw new IllegalArgumentException("snapshot contains an invalid mode record key/value");
            }
        }
        for (Map.Entry<String, LevelProgress> entry : snapshot.campaignLevels().entrySet()) {
            if (!catalog.contains(entry.getKey())) {
                throw new IllegalArgumentException("snapshot contains progress for an unknown level");
            }
            LevelDefinition level = catalog.byId(entry.getKey());
            LevelProgress progress = entry.getValue();
            int currentFullMask = fullOptionalMask(level);
            if (progress.optionalFullMask() != currentFullMask
                    || progress.hasOptionalObjectives() != (currentFullMask != 0)
                    || (progress.optionalMask() & ~currentFullMask) != 0) {
                throw new IllegalArgumentException("snapshot Optional mask is incompatible with level "
                        + entry.getKey());
            }
        }
        for (String levelId : snapshot.gatePassedLevelIds()) {
            if (!catalog.contains(levelId) || catalog.byId(levelId).gates().isEmpty()) {
                throw new IllegalArgumentException("snapshot contains an invalid gate-progress level");
            }
        }
        for (String levelId : snapshot.keyCollectedLevelIds()) {
            if (!catalog.contains(levelId)) {
                throw new IllegalArgumentException("snapshot contains key progress for an unknown level");
            }
            boolean hasKeyGrant = false;
            for (Objective objective : catalog.byId(levelId).objectives()) {
                if (objective.grantsKeyId() != null) { hasKeyGrant = true; break; }
            }
            if (!hasKeyGrant) {
                throw new IllegalArgumentException("snapshot contains key progress for a level without a key");
            }
        }
        Set<String> knownAchievements = new HashSet<String>();
        for (AchievementDef definition : ACHIEVEMENTS) knownAchievements.add(definition.id());
        if (!knownAchievements.containsAll(snapshot.unlockedAchievementIds())) {
            throw new IllegalArgumentException("snapshot contains an unknown Achievement ID");
        }
        Set<String> knownCosmetics = new HashSet<String>();
        for (CosmeticDef cosmetic : COSMETICS) knownCosmetics.add(cosmetic.id());
        if (!knownCosmetics.containsAll(snapshot.unlockedCosmeticIds())) {
            throw new IllegalArgumentException("snapshot contains an unknown Cosmetic ID");
        }
        if (snapshot.personalBestCount() > catalog.size()) {
            throw new IllegalArgumentException("personalBestCount exceeds distinct Campaign levels");
        }
        if (snapshot.campaignCompletions() < 0
                || snapshot.successfulDailyCompletions() != snapshot.completedDailyChallengeIds().size()) {
            throw new IllegalArgumentException("snapshot counters are inconsistent");
        }
    }

    private static boolean completed(ProgressSnapshot snapshot, String levelId) {
        LevelProgress progress = snapshot.level(levelId);
        return progress != null && progress.completed();
    }

    private static boolean isModeRecordEligible(ReplayMode mode) {
        return mode == ReplayMode.TIME_ATTACK || mode == ReplayMode.GHOST_RACE
                || mode == ReplayMode.DAILY_CHALLENGE;
    }

    private static String modeRecordKey(String levelId, ReplayMode mode, String dailyChallengeId) {
        String challenge = mode == ReplayMode.DAILY_CHALLENGE
                ? normalizeOptionalText(dailyChallengeId) : "";
        return levelId + "|" + mode.code() + "|" + challenge;
    }

    private static int indexOfLevel(Catalog catalog, String levelId) {
        for (int i = 0; i < catalog.size(); i++) {
            if (catalog.get(i).levelId().equals(levelId)) return i;
        }
        return -1;
    }

    private static int countCollectedOptionals(LevelDefinition level,
                                               Collection<String> collectedObjectiveIds) {
        Set<String> collected = validatedObjectiveIds(level, collectedObjectiveIds);
        int count = 0;
        for (Objective objective : level.objectives()) {
            if (objective.type() == ObjectiveType.OPTIONAL && collected.contains(objective.id())) {
                count++;
            }
        }
        return count;
    }

    private static void validateRunReferences(LevelDefinition level, RunRecord run) {
        if (run.mode() == ReplayMode.DAILY_CHALLENGE
                && run.result() == RunResult.COMPLETED
                && !level.levelId().equals(
                DailyChallenge.levelIdFromChallengeId(run.dailyChallengeId()))) {
            throw new IllegalArgumentException(
                    "Daily Challenge ID selects a different Campaign level");
        }
        Set<String> knownObjectives = new HashSet<String>();
        for (Objective objective : level.objectives()) knownObjectives.add(objective.id());
        if (!knownObjectives.containsAll(run.collectedObjectiveIds())) {
            throw new IllegalArgumentException("RunRecord contains an objective not present in the level");
        }
        Set<String> knownKeys = new HashSet<String>();
        for (Objective objective : level.objectives()) {
            if (objective.grantsKeyId() != null) knownKeys.add(objective.grantsKeyId());
        }
        if (!knownKeys.containsAll(run.collectedKeyIds())) {
            throw new IllegalArgumentException("RunRecord contains a key not present in the level");
        }
        for (String keyId : run.collectedKeyIds()) {
            boolean backedByCollectedObjective = false;
            for (Objective objective : level.objectives()) {
                if (keyId.equals(objective.grantsKeyId())
                        && run.collectedObjectiveIds().contains(objective.id())) {
                    backedByCollectedObjective = true;
                    break;
                }
            }
            if (!backedByCollectedObjective) {
                throw new IllegalArgumentException("RunRecord key is not backed by its collected objective: "
                        + keyId);
            }
        }
        Set<String> knownGates = new HashSet<String>();
        for (LevelSystem.Gate gate : level.gates()) knownGates.add(gate.id());
        if (!knownGates.containsAll(run.passedGateIds())) {
            throw new IllegalArgumentException("RunRecord contains a gate not present in the level");
        }
    }

    private static int safeIncrement(int value) {
        return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;
    }

    private static int safeAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        if (right < 0 && left < Integer.MIN_VALUE - right) return Integer.MIN_VALUE;
        return left + right;
    }

    private static int successfulDailyCount(Set<String> dailyIds) {
        return dailyIds.size();
    }

    private static long requirePositivePar(long par) {
        if (par <= 0L) throw new IllegalArgumentException("parTimeMs must be > 0");
        return par;
    }

    private static double clamp01(double value) {
        return value <= 0.0 ? 0.0 : value >= 1.0 ? 1.0 : value;
    }

    private static String requireStableText(String value, String label) {
        if (value == null || value.trim().isEmpty() || value.length() > 128
                || !value.equals(value.trim()) || containsControl(value)) {
            throw new IllegalArgumentException(label + " is blank or not a stable identifier");
        }
        return value;
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.trim();
    }

    private static Set<String> validatedObjectiveIds(LevelDefinition level,
                                                     Collection<String> source) {
        Set<String> collected = safeStringSet(source, "collectedObjectiveIds");
        Set<String> known = new HashSet<String>();
        for (Objective objective : level.objectives()) known.add(objective.id());
        if (!known.containsAll(collected)) {
            throw new IllegalArgumentException("collectedObjectiveIds contains unknown level objective");
        }
        return collected;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static Set<String> safeStringSet(Collection<String> source, String label) {
        if (source == null) throw new IllegalArgumentException(label + " must not be null");
        TreeSet<String> copy = new TreeSet<String>();
        for (String value : source) {
            if (value == null || value.trim().isEmpty() || !value.equals(value.trim())
                    || containsControl(value) || value.length() > 128) {
                throw new IllegalArgumentException(label + " contains an invalid identifier");
            }
            copy.add(value);
        }
        return copy;
    }

    private static Set<String> immutableSortedSet(Collection<String> source, String label) {
        return Collections.unmodifiableSet(safeStringSet(source, label));
    }

    private static <V> Map<String, V> immutableSortedMap(Map<String, V> source) {
        Objects.requireNonNull(source, "map");
        TreeMap<String, V> copy = new TreeMap<String, V>();
        for (Map.Entry<String, V> entry : source.entrySet()) {
            String key = requireStableText(entry.getKey(), "map key");
            V value = Objects.requireNonNull(entry.getValue(), "map value");
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
