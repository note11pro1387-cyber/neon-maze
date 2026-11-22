package com.neonmaze.core;

import com.neonmaze.core.LevelSystem.Catalog;
import com.neonmaze.core.LevelSystem.LevelDefinition;
import com.neonmaze.core.ReplaySystem.ReplayMode;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Deterministic, offline Daily Challenge selection.
 *
 * <p>The challenge date is Gregorian UTC. Selection uses the canonical
 * five-level Campaign Catalog hash and the versioned seed string defined by
 * the product contract. No local timezone, random generator, network service,
 * Android API or {@code java.time} type participates in selection.</p>
 *
 * <p>Daily Challenge completion is a separate mode record. This class only
 * selects the challenge and does not modify Campaign unlocks, stars or score.</p>
 */
public final class DailyChallenge {

    /** Daily selection is tied to the active deterministic game-rules version. */
    public static final int DEFAULT_RULES_VERSION = ReplaySystem.GAME_RULES_VERSION;
    public static final int CAMPAIGN_LEVEL_COUNT = 5;
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final String[] EXPECTED_LEVEL_IDS = {
            ProgressRules.LEVEL_01_ID,
            ProgressRules.LEVEL_02_ID,
            ProgressRules.LEVEL_03_ID,
            ProgressRules.LEVEL_04_ID,
            ProgressRules.LEVEL_05_ID
    };

    private DailyChallenge() {
    }

    /**
     * Immutable selected challenge. The referenced LevelDefinition belongs
     * to the immutable Catalog supplied to the selector.
     */
    public static final class Selection {
        private final int rulesVersion;
        private final String utcDate;
        private final String catalogHash;
        private final String challengeId;
        private final int levelNumber;
        private final String levelId;
        private final LevelDefinition level;
        private final long unsignedSeed;

        private Selection(int rulesVersion, String utcDate, String catalogHash,
                          String challengeId, int levelNumber, LevelDefinition level,
                          long unsignedSeed) {
            this.rulesVersion = rulesVersion;
            this.utcDate = utcDate;
            this.catalogHash = catalogHash;
            this.challengeId = challengeId;
            this.levelNumber = levelNumber;
            this.level = level;
            this.levelId = level.levelId();
            this.unsignedSeed = unsignedSeed;
        }

        public int rulesVersion() { return rulesVersion; }
        public String utcDate() { return utcDate; }
        public String catalogHash() { return catalogHash; }
        public String challengeId() { return challengeId; }
        public int levelNumber() { return levelNumber; }
        public String levelId() { return levelId; }
        public LevelDefinition level() { return level; }
        /** First four SHA-256 bytes interpreted as an unsigned 32-bit quantity in a long. */
        public long unsignedSeed() { return unsignedSeed; }
        public ReplayMode mode() { return ReplayMode.DAILY_CHALLENGE; }

        @Override
        public String toString() {
            return "DailyChallenge.Selection[id=" + challengeId
                    + ", level=" + levelId + ", date=" + utcDate + "]";
        }
    }

    /** Selects today's challenge from a wall-clock timestamp using UTC only. */
    public static Selection forTimestamp(long wallClockUtcMillis,
                                         int rulesVersion,
                                         Catalog catalog) {
        String date = utcDate(wallClockUtcMillis);
        return forUtcDate(date, rulesVersion, catalog);
    }

    /** Selects a challenge for a strict Gregorian {@code yyyy-MM-dd} UTC date. */
    public static Selection forUtcDate(String date,
                                       int rulesVersion,
                                       Catalog catalog) {
        if (rulesVersion <= 0 || rulesVersion > 0xffff) {
            throw new IllegalArgumentException("rulesVersion must be in [1,65535]");
        }
        String canonicalDate = canonicalUtcDate(date);
        String canonicalHash = canonicalCatalogHash(catalog);
        String seedText = "NMZR|D" + rulesVersion + "|" + canonicalDate
                + "|" + canonicalHash;
        byte[] digest = sha256(seedText.getBytes(UTF_8));
        long unsignedSeed = ((long) digest[0] & 0xffL) << 24
                | ((long) digest[1] & 0xffL) << 16
                | ((long) digest[2] & 0xffL) << 8
                | ((long) digest[3] & 0xffL);
        int zeroBasedIndex = (int) (unsignedSeed % CAMPAIGN_LEVEL_COUNT);
        LevelDefinition selectedLevel = catalog.get(zeroBasedIndex);
        int levelNumber = zeroBasedIndex + 1;
        String challengeId = "NMZR-D" + rulesVersion + "-" + canonicalDate
                + "-" + selectedLevel.levelId();
        return new Selection(rulesVersion, canonicalDate, canonicalHash,
                challengeId, levelNumber, selectedLevel, unsignedSeed);
    }

    /** Returns the canonical Gregorian date for an epoch-millisecond timestamp in UTC. */
    public static String utcDate(long epochMillis) {
        GregorianCalendar calendar = new GregorianCalendar(UTC, Locale.ROOT);
        calendar.setTimeInMillis(epochMillis);
        int year = calendar.get(GregorianCalendar.YEAR);
        if (calendar.get(GregorianCalendar.ERA) != GregorianCalendar.AD || year < 1 || year > 9999) {
            throw new IllegalArgumentException("timestamp is outside supported Gregorian year range");
        }
        return fourDigits(year) + "-" + twoDigits(calendar.get(GregorianCalendar.MONTH) + 1)
                + "-" + twoDigits(calendar.get(GregorianCalendar.DAY_OF_MONTH));
    }

    /** Strictly validates and returns the canonical {@code yyyy-MM-dd} representation. */
    public static String canonicalUtcDate(String date) {
        if (date == null || date.length() != 10 || date.charAt(4) != '-'
                || date.charAt(7) != '-') {
            throw new IllegalArgumentException("UTC date must use yyyy-MM-dd");
        }
        for (int i = 0; i < date.length(); i++) {
            if (i == 4 || i == 7) continue;
            char ch = date.charAt(i);
            if (ch < '0' || ch > '9') {
                throw new IllegalArgumentException("UTC date must use ASCII decimal digits");
            }
        }
        // SimpleDateFormat is used only as a strict Gregorian parser; the
        // round-trip check prevents lenient or partial date acceptance.
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(UTC);
        format.setCalendar(new GregorianCalendar(UTC, Locale.ROOT));
        format.setLenient(false);
        try {
            Date parsed = format.parse(date);
            if (parsed == null || !date.equals(format.format(parsed))) {
                throw new IllegalArgumentException("UTC date is not a valid Gregorian date");
            }
            return date;
        } catch (ParseException exception) {
            throw new IllegalArgumentException("UTC date is not a valid Gregorian date", exception);
        }
    }

    /** Validates exactly 64 ASCII hexadecimal digits and canonicalizes to lowercase. */
    public static String canonicalCatalogHash(Catalog catalog) {
        if (catalog == null) throw new IllegalArgumentException("catalog must not be null");
        if (catalog.size() != CAMPAIGN_LEVEL_COUNT) {
            throw new IllegalArgumentException("Daily Challenge requires exactly five Campaign levels");
        }
        for (int i = 0; i < EXPECTED_LEVEL_IDS.length; i++) {
            if (!EXPECTED_LEVEL_IDS[i].equals(catalog.get(i).levelId())) {
                throw new IllegalArgumentException("Campaign Catalog order/ID mismatch at position "
                        + (i + 1));
            }
        }
        String hash = catalog.catalogHash();
        if (hash == null || hash.length() != 64) {
            throw new IllegalArgumentException("catalog hash must contain 64 hexadecimal digits");
        }
        StringBuilder canonical = new StringBuilder(64);
        for (int i = 0; i < hash.length(); i++) {
            char ch = hash.charAt(i);
            if (ch >= '0' && ch <= '9') {
                canonical.append(ch);
            } else if (ch >= 'a' && ch <= 'f') {
                canonical.append(ch);
            } else if (ch >= 'A' && ch <= 'F') {
                canonical.append((char) (ch + ('a' - 'A')));
            } else {
                throw new IllegalArgumentException("catalog hash contains a non-hexadecimal character");
            }
        }
        return canonical.toString();
    }

    /**
     * Checks whether a persisted Daily Challenge ID has the supported stable
     * shape. It does not claim the ID was selected for a particular Catalog;
     * callers compare the full ID with {@link Selection#challengeId()}.
     */
    public static boolean hasValidChallengeIdShape(String challengeId) {
        if (challengeId == null || !challengeId.startsWith("NMZR-D")) return false;
        int versionEnd = challengeId.indexOf('-', 6);
        if (versionEnd <= 6) return false;
        int dateStart = versionEnd + 1;
        int dateEnd = dateStart + 10;
        if (challengeId.length() <= dateEnd + 1
                || challengeId.charAt(dateEnd) != '-') return false;
        try {
            int version = Integer.parseInt(challengeId.substring(6, versionEnd));
            String date = challengeId.substring(dateStart, dateEnd);
            String levelId = challengeId.substring(dateEnd + 1);
            boolean knownLevel = false;
            for (String expectedId : EXPECTED_LEVEL_IDS) {
                if (expectedId.equals(levelId)) { knownLevel = true; break; }
            }
            return version >= 1 && version <= 0xffff && knownLevel
                    && canonicalUtcDate(date).equals(date)
                    && challengeId.equals("NMZR-D" + version + "-" + date + "-" + levelId);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Returns the Campaign Level ID embedded in a valid challenge ID. */
    public static String levelIdFromChallengeId(String challengeId) {
        if (!hasValidChallengeIdShape(challengeId)) {
            throw new IllegalArgumentException("invalid Daily Challenge ID");
        }
        int versionEnd = challengeId.indexOf('-', 6);
        int dateStart = versionEnd + 1;
        int dateEnd = dateStart + 10;
        return challengeId.substring(dateEnd + 1);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandatory in Java/Android; absence is a runtime platform failure.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String fourDigits(int value) {
        if (value >= 1000) return Integer.toString(value);
        if (value >= 100) return "0" + value;
        if (value >= 10) return "00" + value;
        return "000" + value;
    }

    private static String twoDigits(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
