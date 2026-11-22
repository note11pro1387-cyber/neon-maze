package com.neonmaze.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Trace recording, bounded binary serialization, ghost playback, and path
 * review for NEON MAZE. Replay is visual playback, never physics replay.
 *
 * <h2>Wire format — NMZR schema 2</h2>
 * <p>All multibyte fields are big-endian. The fixed header is 96 bytes;
 * payload begins at byte 96. CRC32 is stored at byte offset 80 and is
 * computed over the complete header and payload while bytes 80..83 are
 * treated as zero. The payload contains fixed-width sample/event records,
 * a bounded UTF-8 detail dictionary, and the exact UTF-8 level ID.</p>
 *
 * <pre>
 * Header (96 bytes):
 *   0..3    magic "NMZR"
 *   4..5    schema version (u16)
 *   6..7    game rules version (u16)
 *   8..9    CRC-16/CCITT of the exact level ID (u16; quick identity check)
 *  10..11   level revision (u16)
 *  12..43   SHA-256 geometry hash (32 bytes)
 *     44    replay mode code (u8; explicit stable code)
 *     45    result code (u8; explicit stable code)
 *     46    flags: bit 0 assisted, bit 1 partial
 *     47    reserved, must be zero
 *  48..55   run UTC timestamp in milliseconds (i64)
 *  56..59   active duration in milliseconds (u32)
 *  60..63   score (u32)
 *     64    stars (u8, 0..3)
 *  65..67   reserved, must be zero
 *  68..71   sample count (u32)
 *  72..75   event count (u32)
 *  76..79   payload byte count (u32)
 *  80..83   CRC32 (u32; zeroed during calculation)
 *  84..95   reserved, must be zero
 *
 * Payload:
 *   samples: sampleCount × { u32 deltaMs, i16 xQ, i16 yQ }
 *   events:  eventCount × { u32 deltaMs, u8 type, u8 aux=0,
 *                            u16 detailIndex, i16 xReserved=0, i16 yReserved=0 }
 *   details: u16 count; repeated { u16 utf8Length, utf8Bytes }
 *   levelId: u16 utf8Length, utf8Bytes
 * </pre>
 *
 * <p>Coordinates are quantized at 1/16 World Unit. Values outside the
 * signed-16-bit quantization range are rejected; they are never saturated.
 * Event position is resolved by timestamp against the trace samples, so
 * the final four event bytes are reserved in schema 2. The header's
 * 16-bit level code is only a quick checksum; the exact level ID in the
 * payload and the full geometry hash remain authoritative.</p>
 *
 * <h2>Safety and compatibility</h2>
 * <ul>
 *   <li>Counts, lengths, and total payload size are checked before lists
 *       or strings are allocated.</li>
 *   <li>CRC is verified before payload decoding.</li>
 *   <li>Unknown versions, mode/result/event codes, flags, or non-zero
 *       reserved fields are rejected.</li>
 *   <li>Partial traces are retained for review but are not Ghost eligible.
 *       Only completed traces can produce a {@link GhostTimeline}.</li>
 *   <li>Compatibility requires exact level ID, revision, geometry hash,
 *       and game-rules version.</li>
 *   <li>Schema 1 candidate traces are not decoded by this schema 2 codec;
 *       callers must treat them as unavailable rather than attempting a
 *       best-effort parse.</li>
 * </ul>
 *
 * <h2>Android compatibility</h2>
 * <p>The Core source avoids Java records, {@code HexFormat}, and
 * {@code List.copyOf}; no dependency on core-library desugaring is required
 * by this file for its value types and immutable collections.</p>
 */
public final class ReplaySystem {

    private ReplaySystem() {
    }

    // =====================================================================
    // Constants and wire codes
    // =====================================================================

    /** Public compatibility constant. The codec uses a private immutable-by-convention copy. */
    public static final byte[] MAGIC = {'N', 'M', 'Z', 'R'};
    private static final byte[] MAGIC_BYTES = {'N', 'M', 'Z', 'R'};

    public static final int SCHEMA_VERSION = 2;
    public static final int GAME_RULES_VERSION = 2;
    public static final int HEADER_BYTES = 96;
    public static final int CRC_OFFSET = 80;
    public static final int MAX_SAMPLES = 80_000;
    public static final int MAX_EVENTS = 4_096;
    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_SAMPLE_DELTA_MS = 60_000;
    public static final int MAX_LEVEL_ID_BYTES = 256;
    public static final int MAX_EVENT_STRING_BYTES = 256;
    public static final int GEOMETRY_HASH_BYTES = 32;
    public static final double POSITION_SCALE = 16.0;
    public static final long MAX_UNSIGNED_INT = 0xFFFF_FFFFL;

    private static final int FLAG_ASSISTED = 0x01;
    private static final int FLAG_PARTIAL = 0x02;
    private static final int KNOWN_FLAGS = FLAG_ASSISTED | FLAG_PARTIAL;
    private static final int NO_DETAIL_INDEX = 0xFFFF;

    private static final int EVENT_START = 1;
    private static final int EVENT_OBJECTIVE = 2;
    private static final int EVENT_BLOCKED = 3;
    private static final int EVENT_GATE_PASS = 4;
    private static final int EVENT_PAUSE = 5;
    private static final int EVENT_RESUME = 6;
    private static final int EVENT_INTERRUPT = 7;
    private static final int EVENT_FAIL = 8;
    private static final int EVENT_COMPLETE = 9;

    // =====================================================================
    // Enumerations with stable wire codes (never use ordinal on disk)
    // =====================================================================

    public enum ReplayMode {
        CAMPAIGN(1),
        PRACTICE(2),
        TIME_ATTACK(3),
        GHOST_RACE(4),
        DAILY_CHALLENGE(5),
        TUTORIAL(6);

        private final int code;

        ReplayMode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static ReplayMode fromCode(int code) {
            for (ReplayMode value : values()) {
                if (value.code == code) return value;
            }
            return null;
        }
    }

    public enum RunResult {
        COMPLETED(1),
        FAILED(2),
        INTERRUPTED(3);

        private final int code;

        RunResult(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static RunResult fromCode(int code) {
            for (RunResult value : values()) {
                if (value.code == code) return value;
            }
            return null;
        }
    }

    public enum Compatibility {
        AVAILABLE,
        UNAVAILABLE
    }

    // =====================================================================
    // Immutable value types — Java/Android API 24 compatible
    // =====================================================================

    public static final class TraceSample {
        private final long tMs;
        private final double x;
        private final double y;

        public TraceSample(long tMs, double x, double y) {
            if (tMs < 0L) {
                throw new IllegalArgumentException("trace sample tMs must be >= 0");
            }
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("trace sample coordinates must be finite");
            }
            this.tMs = tMs;
            this.x = x;
            this.y = y;
        }

        public long tMs() { return tMs; }
        public double x() { return x; }
        public double y() { return y; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TraceSample)) return false;
            TraceSample that = (TraceSample) other;
            return tMs == that.tMs
                    && Double.compare(x, that.x) == 0
                    && Double.compare(y, that.y) == 0;
        }

        @Override
        public int hashCode() {
            int result = Long.valueOf(tMs).hashCode();
            long xBits = Double.doubleToLongBits(x);
            long yBits = Double.doubleToLongBits(y);
            result = 31 * result + (int) (xBits ^ (xBits >>> 32));
            result = 31 * result + (int) (yBits ^ (yBits >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "TraceSample[tMs=" + tMs + ", x=" + x + ", y=" + y + "]";
        }
    }

    public static final class TraceEvent {
        private final long tMs;
        private final String type;
        private final String detail;

        public TraceEvent(long tMs, String type, String detail) {
            if (tMs < 0L) {
                throw new IllegalArgumentException("trace event tMs must be >= 0");
            }
            this.type = requireNonEmpty(type, "event type");
            this.detail = Objects.requireNonNull(detail, "event detail");
            this.tMs = tMs;
        }

        public long tMs() { return tMs; }
        public String type() { return type; }
        public String detail() { return detail; }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TraceEvent)) return false;
            TraceEvent that = (TraceEvent) other;
            return tMs == that.tMs && type.equals(that.type) && detail.equals(that.detail);
        }

        @Override
        public int hashCode() {
            int result = Long.valueOf(tMs).hashCode();
            result = 31 * result + type.hashCode();
            result = 31 * result + detail.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "TraceEvent[tMs=" + tMs + ", type=" + type
                    + ", detail=" + detail + "]";
        }
    }

    // =====================================================================
    // Trace model and builder
    // =====================================================================

    public static final class ReplayTrace {
        private final int schemaVersion;
        private final int gameRulesVersion;
        private final String levelId;
        private final int levelRevision;
        private final byte[] geometryHash;
        private final ReplayMode mode;
        private final long runTimestampMs;
        private final long activeDurationMs;
        private final List<TraceSample> samples;
        private final List<TraceEvent> events;
        private final RunResult result;
        private final int score;
        private final int stars;
        private final boolean assisted;

        private ReplayTrace(Builder builder) {
            schemaVersion = builder.schemaVersion;
            gameRulesVersion = builder.gameRulesVersion;
            levelId = requireNonEmpty(builder.levelId, "levelId");
            levelRevision = builder.levelRevision;
            if (builder.geometryHash == null) {
                throw new IllegalArgumentException("geometryHash is required");
            }
            geometryHash = builder.geometryHash.clone();
            mode = Objects.requireNonNull(builder.mode, "mode");
            runTimestampMs = builder.runTimestampMs;
            activeDurationMs = builder.activeDurationMs;
            samples = Collections.unmodifiableList(new ArrayList<>(builder.samples));
            events = Collections.unmodifiableList(new ArrayList<>(builder.events));
            result = Objects.requireNonNull(builder.result, "result");
            score = builder.score;
            stars = builder.stars;
            assisted = builder.assisted;
            validate();
        }

        private void validate() {
            if (schemaVersion != SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
            }
            if (gameRulesVersion != GAME_RULES_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported gameRulesVersion: " + gameRulesVersion);
            }
            if (utf8(levelId).length == 0 || utf8(levelId).length > MAX_LEVEL_ID_BYTES) {
                throw new IllegalArgumentException("levelId byte length is invalid");
            }
            if (levelRevision < 1 || levelRevision > 0xFFFF) {
                throw new IllegalArgumentException("levelRevision must be in [1,65535]");
            }
            if (geometryHash.length != GEOMETRY_HASH_BYTES) {
                throw new IllegalArgumentException("geometryHash must contain 32 bytes");
            }
            if (runTimestampMs < 0L) {
                throw new IllegalArgumentException("runTimestampMs must be >= 0");
            }
            if (activeDurationMs < 0L || activeDurationMs > MAX_UNSIGNED_INT) {
                throw new IllegalArgumentException("activeDurationMs exceeds u32 range");
            }
            if (samples.isEmpty() || samples.size() > MAX_SAMPLES) {
                throw new IllegalArgumentException("trace must contain 1..MAX_SAMPLES samples");
            }
            if (events.size() > MAX_EVENTS) {
                throw new IllegalArgumentException("event count exceeds MAX_EVENTS");
            }
            if (score < 0) throw new IllegalArgumentException("score must be >= 0");
            if (stars < 0 || stars > 3) {
                throw new IllegalArgumentException("stars must be in [0,3]");
            }

            long previousSampleMs = 0L;
            for (int i = 0; i < samples.size(); i++) {
                TraceSample sample = Objects.requireNonNull(samples.get(i), "sample");
                long delta = sample.tMs() - previousSampleMs;
                if (delta < 0L || delta > MAX_SAMPLE_DELTA_MS) {
                    throw new IllegalArgumentException("invalid sample time delta at index " + i);
                }
                if (sample.tMs() > activeDurationMs) {
                    throw new IllegalArgumentException("sample exceeds active duration at index " + i);
                }
                if (i == 0 && sample.tMs() != 0L) {
                    throw new IllegalArgumentException("first sample must anchor run start at t=0");
                }
                previousSampleMs = sample.tMs();
            }

            long previousEventMs = 0L;
            for (int i = 0; i < events.size(); i++) {
                TraceEvent event = Objects.requireNonNull(events.get(i), "event");
                long delta = event.tMs() - previousEventMs;
                if (delta < 0L || delta > MAX_UNSIGNED_INT) {
                    throw new IllegalArgumentException("invalid event time delta at index " + i);
                }
                if (event.tMs() > activeDurationMs) {
                    throw new IllegalArgumentException("event exceeds active duration at index " + i);
                }
                if (eventTypeCode(event.type()) == 0) {
                    throw new IllegalArgumentException("unknown replay event type: " + event.type());
                }
                if (utf8(event.type()).length > MAX_EVENT_STRING_BYTES
                        || utf8(event.detail()).length > MAX_EVENT_STRING_BYTES) {
                    throw new IllegalArgumentException("event string exceeds 256 UTF-8 bytes");
                }
                previousEventMs = event.tMs();
            }
        }

        public int schemaVersion() { return schemaVersion; }
        public int gameRulesVersion() { return gameRulesVersion; }
        public String levelId() { return levelId; }
        public int levelRevision() { return levelRevision; }
        public byte[] geometryHash() { return geometryHash.clone(); }
        public String geometryHashHex() { return toHex(geometryHash); }
        public ReplayMode mode() { return mode; }
        public long runTimestampMs() { return runTimestampMs; }
        public long activeDurationMs() { return activeDurationMs; }
        public List<TraceSample> samples() { return samples; }
        public List<TraceEvent> events() { return events; }
        public RunResult result() { return result; }
        public int score() { return score; }
        public int stars() { return stars; }
        public boolean assisted() { return assisted; }
        public boolean isPartial() { return result == RunResult.INTERRUPTED; }
        public boolean isGhostEligible() { return result == RunResult.COMPLETED; }

        public TraceSample startPosition() {
            return samples.get(0);
        }

        public Compatibility checkCompatibility(LevelSystem.LevelDefinition level) {
            if (level == null
                    || !level.levelId().equals(levelId)
                    || level.levelRevision() != levelRevision
                    || geometryHash.length != GEOMETRY_HASH_BYTES
                    || GAME_RULES_VERSION != gameRulesVersion) {
                return Compatibility.UNAVAILABLE;
            }
            byte[] currentHash;
            try {
                currentHash = parseHex(level.geometryHash());
            } catch (IllegalArgumentException invalidHash) {
                return Compatibility.UNAVAILABLE;
            }
            if (currentHash.length != geometryHash.length) return Compatibility.UNAVAILABLE;
            for (int i = 0; i < geometryHash.length; i++) {
                if (geometryHash[i] != currentHash[i]) return Compatibility.UNAVAILABLE;
            }
            return Compatibility.AVAILABLE;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private int schemaVersion = SCHEMA_VERSION;
            private int gameRulesVersion = GAME_RULES_VERSION;
            private String levelId;
            private int levelRevision;
            private byte[] geometryHash;
            private ReplayMode mode;
            private long runTimestampMs;
            private long activeDurationMs;
            private final List<TraceSample> samples = new ArrayList<>();
            private final List<TraceEvent> events = new ArrayList<>();
            private RunResult result;
            private int score;
            private int stars;
            private boolean assisted;

            public Builder levelId(String value) { levelId = value; return this; }
            public Builder levelRevision(int value) { levelRevision = value; return this; }
            public Builder geometryHashHex(String value) {
                geometryHash = parseHex(value);
                return this;
            }
            public Builder geometryHash(byte[] value) {
                geometryHash = Objects.requireNonNull(value, "geometryHash").clone();
                return this;
            }
            public Builder mode(ReplayMode value) { mode = value; return this; }
            public Builder runTimestampMs(long value) { runTimestampMs = value; return this; }
            public Builder activeDurationMs(long value) { activeDurationMs = value; return this; }
            public Builder sample(long tMs, double x, double y) {
                samples.add(new TraceSample(tMs, x, y));
                return this;
            }
            public Builder event(long tMs, String type, String detail) {
                events.add(new TraceEvent(tMs, type, detail));
                return this;
            }
            public Builder result(RunResult value) { result = value; return this; }
            public Builder score(int value) { score = value; return this; }
            public Builder stars(int value) { stars = value; return this; }
            public Builder assisted(boolean value) { assisted = value; return this; }
            public ReplayTrace build() { return new ReplayTrace(this); }
        }
    }

    // =====================================================================
    // Binary codec
    // =====================================================================

    public static final class ReplayCodec {
        private ReplayCodec() {
        }

        public static byte[] encode(ReplayTrace trace) {
            Objects.requireNonNull(trace, "trace");
            try {
                byte[] payload = encodePayload(trace);
                if (payload.length > MAX_BYTES - HEADER_BYTES) {
                    throw new IllegalArgumentException("replay payload exceeds MAX_BYTES");
                }

                ByteBuffer output = ByteBuffer.allocate(HEADER_BYTES + payload.length)
                        .order(ByteOrder.BIG_ENDIAN);
                output.put(MAGIC_BYTES);
                output.putShort((short) SCHEMA_VERSION);
                output.putShort((short) GAME_RULES_VERSION);
                output.putShort((short) levelIdCode(trace.levelId()));
                output.putShort((short) trace.levelRevision());
                output.put(trace.geometryHash());
                output.put((byte) trace.mode().code());
                output.put((byte) trace.result().code());
                int flags = (trace.assisted() ? FLAG_ASSISTED : 0)
                        | (trace.isPartial() ? FLAG_PARTIAL : 0);
                output.put((byte) flags);
                output.put((byte) 0);
                output.putLong(trace.runTimestampMs());
                output.putInt((int) trace.activeDurationMs());
                output.putInt(trace.score());
                output.put((byte) trace.stars());
                output.put((byte) 0).put((byte) 0).put((byte) 0);
                output.putInt(trace.samples().size());
                output.putInt(trace.events().size());
                output.putInt(payload.length);
                output.putInt(0); // CRC field is zero while the CRC is calculated.
                for (int i = 0; i < 12; i++) output.put((byte) 0);
                if (output.position() != HEADER_BYTES) {
                    throw new IllegalStateException("internal replay header size mismatch");
                }
                output.put(payload);
                byte[] encoded = output.array();
                long crc = calculateCrcWithZeroedField(encoded);
                ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
                        .putInt(CRC_OFFSET, (int) crc);
                return encoded;
            } catch (IOException impossible) {
                throw new IllegalStateException("unexpected in-memory replay I/O failure", impossible);
            }
        }

        private static byte[] encodePayload(ReplayTrace trace) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            DataOutputStream out = new DataOutputStream(bytes);

            long previousSampleMs = 0L;
            for (TraceSample sample : trace.samples()) {
                long delta = sample.tMs() - previousSampleMs;
                if (delta < 0L || delta > MAX_SAMPLE_DELTA_MS) {
                    throw new IllegalArgumentException("sample delta outside u32 policy");
                }
                out.writeInt((int) delta);
                out.writeShort(quantize(sample.x()));
                out.writeShort(quantize(sample.y()));
                previousSampleMs = sample.tMs();
            }

            LinkedHashMap<String, Integer> detailIndexes = new LinkedHashMap<>();
            for (TraceEvent event : trace.events()) {
                if (!detailIndexes.containsKey(event.detail())) {
                    if (detailIndexes.size() >= MAX_EVENTS) {
                        throw new IllegalArgumentException("too many distinct event details");
                    }
                    detailIndexes.put(event.detail(), detailIndexes.size());
                }
            }

            long previousEventMs = 0L;
            for (TraceEvent event : trace.events()) {
                long delta = event.tMs() - previousEventMs;
                if (delta < 0L || delta > MAX_UNSIGNED_INT) {
                    throw new IllegalArgumentException("event delta outside u32 range");
                }
                Integer detailIndex = detailIndexes.get(event.detail());
                if (detailIndex == null || detailIndex.intValue() > 0xFFFE) {
                    throw new IllegalArgumentException("event detail index is invalid");
                }
                out.writeInt((int) delta);
                out.writeByte(eventTypeCode(event.type()));
                out.writeByte(0); // aux flags reserved in schema 2.
                out.writeShort(detailIndex.intValue());
                out.writeShort(0); // Event position is resolved by its sample-time anchor.
                out.writeShort(0);
                previousEventMs = event.tMs();
            }

            out.writeShort(detailIndexes.size());
            for (String detail : detailIndexes.keySet()) {
                writeBoundedString(out, detail, MAX_EVENT_STRING_BYTES);
            }
            writeBoundedString(out, trace.levelId(), MAX_LEVEL_ID_BYTES);
            out.flush();
            return bytes.toByteArray();
        }

        public static ReplayTrace decode(byte[] encoded) {
            if (encoded == null || encoded.length < HEADER_BYTES
                    || encoded.length > MAX_BYTES) return null;
            if (!hasMagic(encoded)) return null;

            long expectedCrc = readUnsignedInt(encoded, CRC_OFFSET);
            if (calculateCrcWithZeroedField(encoded) != expectedCrc) return null;

            try {
                ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
                input.position(4);
                int schemaVersion = readUnsignedShort(input);
                int gameRulesVersion = readUnsignedShort(input);
                int headerLevelIdCode = readUnsignedShort(input);
                int levelRevision = readUnsignedShort(input);
                byte[] geometryHash = new byte[GEOMETRY_HASH_BYTES];
                input.get(geometryHash);
                ReplayMode mode = ReplayMode.fromCode(input.get() & 0xFF);
                RunResult result = RunResult.fromCode(input.get() & 0xFF);
                int flags = input.get() & 0xFF;
                int reserved47 = input.get() & 0xFF;
                long runTimestampMs = input.getLong();
                long activeDurationMs = readUnsignedInt(input);
                long scoreLong = readUnsignedInt(input);
                int stars = input.get() & 0xFF;
                int reserved65 = input.get() & 0xFF;
                int reserved66 = input.get() & 0xFF;
                int reserved67 = input.get() & 0xFF;
                long sampleCountLong = readUnsignedInt(input);
                long eventCountLong = readUnsignedInt(input);
                long payloadBytesLong = readUnsignedInt(input);
                input.getInt(); // CRC already validated.
                boolean reservedBytesZero = true;
                for (int i = 84; i < HEADER_BYTES; i++) {
                    if (encoded[i] != 0) reservedBytesZero = false;
                }

                if (schemaVersion != SCHEMA_VERSION
                        || gameRulesVersion != GAME_RULES_VERSION
                        || mode == null || result == null
                        || (flags & ~KNOWN_FLAGS) != 0
                        || reserved47 != 0 || reserved65 != 0
                        || reserved66 != 0 || reserved67 != 0
                        || !reservedBytesZero
                        || runTimestampMs < 0L
                        || levelRevision < 1
                        || scoreLong > Integer.MAX_VALUE
                        || stars > 3
                        || sampleCountLong < 1 || sampleCountLong > MAX_SAMPLES
                        || eventCountLong > MAX_EVENTS
                        || payloadBytesLong > MAX_BYTES - HEADER_BYTES
                        || encoded.length != HEADER_BYTES + (int) payloadBytesLong) {
                    return null;
                }
                boolean assisted = (flags & FLAG_ASSISTED) != 0;
                boolean partial = (flags & FLAG_PARTIAL) != 0;
                if (partial != (result == RunResult.INTERRUPTED)) return null;

                int sampleCount = (int) sampleCountLong;
                int eventCount = (int) eventCountLong;
                long fixedBytes = (long) sampleCount * 8L + (long) eventCount * 12L;
                if (fixedBytes + 4L > payloadBytesLong) return null;

                input.position(HEADER_BYTES);
                ArrayList<TraceSample> samples = new ArrayList<>(sampleCount);
                long previousSampleMs = 0L;
                for (int i = 0; i < sampleCount; i++) {
                    long delta = readUnsignedInt(input);
                    if (delta > MAX_SAMPLE_DELTA_MS
                            || previousSampleMs > MAX_UNSIGNED_INT - delta) return null;
                    previousSampleMs += delta;
                    short xQ = input.getShort();
                    short yQ = input.getShort();
                    double x = xQ / POSITION_SCALE;
                    double y = yQ / POSITION_SCALE;
                    samples.add(new TraceSample(previousSampleMs, x, y));
                }
                if (samples.get(0).tMs() != 0L
                        || samples.get(sampleCount - 1).tMs() > activeDurationMs) return null;

                ArrayList<Integer> eventTypes = new ArrayList<>(eventCount);
                ArrayList<Integer> eventDetailIndexes = new ArrayList<>(eventCount);
                ArrayList<Long> eventTimes = new ArrayList<>(eventCount);
                long previousEventMs = 0L;
                for (int i = 0; i < eventCount; i++) {
                    long delta = readUnsignedInt(input);
                    if (previousEventMs > MAX_UNSIGNED_INT - delta) return null;
                    previousEventMs += delta;
                    int typeCode = input.get() & 0xFF;
                    int aux = input.get() & 0xFF;
                    int detailIndex = readUnsignedShort(input);
                    short xReserved = input.getShort();
                    short yReserved = input.getShort();
                    if (eventTypeName(typeCode) == null || aux != 0
                            || xReserved != 0 || yReserved != 0
                            || previousEventMs > activeDurationMs) return null;
                    eventTypes.add(typeCode);
                    eventDetailIndexes.add(detailIndex);
                    eventTimes.add(previousEventMs);
                }

                int detailCount = readUnsignedShort(input);
                if (detailCount > MAX_EVENTS || detailCount > eventCount) return null;
                ArrayList<String> details = new ArrayList<>(detailCount);
                for (int i = 0; i < detailCount; i++) {
                    details.add(readBoundedString(input, MAX_EVENT_STRING_BYTES));
                }
                String levelId = readBoundedString(input, MAX_LEVEL_ID_BYTES);
                if (input.position() != encoded.length) return null;
                if (levelIdCode(levelId) != headerLevelIdCode) return null;

                ReplayTrace.Builder builder = ReplayTrace.builder()
                        .levelId(levelId)
                        .levelRevision(levelRevision)
                        .geometryHash(geometryHash)
                        .mode(mode)
                        .runTimestampMs(runTimestampMs)
                        .activeDurationMs(activeDurationMs)
                        .result(result)
                        .score((int) scoreLong)
                        .stars(stars)
                        .assisted(assisted);
                for (TraceSample sample : samples) {
                    builder.sample(sample.tMs(), sample.x(), sample.y());
                }
                for (int i = 0; i < eventCount; i++) {
                    int detailIndex = eventDetailIndexes.get(i).intValue();
                    if (detailIndex == NO_DETAIL_INDEX) {
                        builder.event(eventTimes.get(i).longValue(),
                                eventTypeName(eventTypes.get(i).intValue()), "");
                    } else {
                        if (detailIndex < 0 || detailIndex >= details.size()) return null;
                        builder.event(eventTimes.get(i).longValue(),
                                eventTypeName(eventTypes.get(i).intValue()),
                                details.get(detailIndex));
                    }
                }
                return builder.build();
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        private static boolean hasMagic(byte[] bytes) {
            for (int i = 0; i < MAGIC_BYTES.length; i++) {
                if (bytes[i] != MAGIC_BYTES[i]) return false;
            }
            return true;
        }

        private static long calculateCrcWithZeroedField(byte[] bytes) {
            CRC32 crc = new CRC32();
            crc.update(bytes, 0, CRC_OFFSET);
            crc.update(new byte[4], 0, 4);
            crc.update(bytes, CRC_OFFSET + 4, bytes.length - (CRC_OFFSET + 4));
            return crc.getValue();
        }

        private static long readUnsignedInt(byte[] bytes, int offset) {
            return ((bytes[offset] & 0xFFL) << 24)
                    | ((bytes[offset + 1] & 0xFFL) << 16)
                    | ((bytes[offset + 2] & 0xFFL) << 8)
                    | (bytes[offset + 3] & 0xFFL);
        }

        private static long readUnsignedInt(ByteBuffer input) {
            return input.getInt() & MAX_UNSIGNED_INT;
        }

        private static int readUnsignedShort(ByteBuffer input) {
            return input.getShort() & 0xFFFF;
        }

        private static void writeBoundedString(DataOutputStream out, String value,
                                               int maxBytes) throws IOException {
            byte[] bytes = utf8(value);
            if (bytes.length > maxBytes || bytes.length > 0xFFFF) {
                throw new IllegalArgumentException("UTF-8 string exceeds limit");
            }
            out.writeShort(bytes.length);
            out.write(bytes);
        }

        private static String readBoundedString(ByteBuffer input, int maxBytes) {
            int length = readUnsignedShort(input);
            if (length > maxBytes || length > input.remaining()) {
                throw new IllegalArgumentException("invalid UTF-8 string length");
            }
            byte[] bytes = new byte[length];
            input.get(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            byte[] canonical = utf8(value);
            if (canonical.length != bytes.length) {
                throw new IllegalArgumentException("malformed UTF-8 string");
            }
            for (int i = 0; i < canonical.length; i++) {
                if (canonical[i] != bytes[i]) {
                    throw new IllegalArgumentException("malformed UTF-8 string");
                }
            }
            return value;
        }

        private static int quantize(double coordinate) {
            if (!Double.isFinite(coordinate)) {
                throw new IllegalArgumentException("non-finite replay coordinate");
            }
            double scaled = coordinate * POSITION_SCALE;
            if (!Double.isFinite(scaled)
                    || scaled < Short.MIN_VALUE - 0.5
                    || scaled > Short.MAX_VALUE + 0.5) {
                throw new IllegalArgumentException("replay coordinate exceeds i16 quantization range");
            }
            long rounded = Math.round(scaled);
            if (rounded < Short.MIN_VALUE || rounded > Short.MAX_VALUE) {
                throw new IllegalArgumentException("replay coordinate would saturate i16");
            }
            return (short) rounded;
        }
    }

    // =====================================================================
    // Ghost playback and path review
    // =====================================================================

    public static final class GhostTimeline {
        private final ReplayTrace trace;

        private GhostTimeline(ReplayTrace trace) {
            this.trace = trace;
        }

        public static GhostTimeline from(ReplayTrace trace,
                                         LevelSystem.LevelDefinition level) {
            Objects.requireNonNull(trace, "trace");
            Objects.requireNonNull(level, "level");
            if (trace.checkCompatibility(level) != Compatibility.AVAILABLE
                    || !trace.isGhostEligible() || trace.samples().isEmpty()) {
                return null;
            }
            return new GhostTimeline(trace);
        }

        public long durationMs() {
            return trace.activeDurationMs();
        }

        public TraceSample positionAt(long tMs) {
            List<TraceSample> samples = trace.samples();
            if (tMs <= samples.get(0).tMs()) return samples.get(0);
            int last = samples.size() - 1;
            if (tMs >= samples.get(last).tMs()) return samples.get(last);
            int low = 0;
            int high = last;
            while (low + 1 < high) {
                int middle = (low + high) >>> 1;
                if (samples.get(middle).tMs() <= tMs) low = middle;
                else high = middle;
            }
            TraceSample a = samples.get(low);
            TraceSample b = samples.get(high);
            long span = b.tMs() - a.tMs();
            if (span <= 0L) return a;
            double alpha = (double) (tMs - a.tMs()) / (double) span;
            return new TraceSample(tMs,
                    a.x() + (b.x() - a.x()) * alpha,
                    a.y() + (b.y() - a.y()) * alpha);
        }

        /**
         * Writes an interpolated position into a caller-owned two-element
         * buffer. This overload performs no allocation and is intended for
         * frame-by-frame rendering. {@code output[0]} receives X and
         * {@code output[1]} receives Y in canonical World Space.
         *
         * @throws IllegalArgumentException when the output buffer is null or
         *                                  has fewer than two elements
         */
        public void positionAt(long tMs, double[] output) {
            if (output == null || output.length < 2) {
                throw new IllegalArgumentException(
                        "Ghost position output must contain at least two elements");
            }
            List<TraceSample> samples = trace.samples();
            int last = samples.size() - 1;
            TraceSample firstSample = samples.get(0);
            TraceSample lastSample = samples.get(last);
            if (tMs <= firstSample.tMs()) {
                output[0] = firstSample.x();
                output[1] = firstSample.y();
                return;
            }
            if (tMs >= lastSample.tMs()) {
                output[0] = lastSample.x();
                output[1] = lastSample.y();
                return;
            }
            int low = 0;
            int high = last;
            while (low + 1 < high) {
                int middle = (low + high) >>> 1;
                if (samples.get(middle).tMs() <= tMs) low = middle;
                else high = middle;
            }
            TraceSample a = samples.get(low);
            TraceSample b = samples.get(high);
            long span = b.tMs() - a.tMs();
            if (span <= 0L) {
                output[0] = a.x();
                output[1] = a.y();
                return;
            }
            double alpha = (double) (tMs - a.tMs()) / (double) span;
            output[0] = a.x() + (b.x() - a.x()) * alpha;
            output[1] = a.y() + (b.y() - a.y()) * alpha;
        }
    }

    public static final class PathReview {
        private final List<TraceSample> window;
        private final long failureMs;

        private PathReview(List<TraceSample> window, long failureMs) {
            this.window = Collections.unmodifiableList(new ArrayList<>(window));
            this.failureMs = failureMs;
        }

        public static PathReview from(ReplayTrace trace, long failureMs,
                                      long windowBefore, long windowAfter) {
            Objects.requireNonNull(trace, "trace");
            if (failureMs < 0L) {
                throw new IllegalArgumentException("failureMs must be >= 0");
            }
            if (trace.result() != RunResult.FAILED
                    || failureMs > trace.activeDurationMs()) return null;
            long before = Math.max(0L, windowBefore);
            long after = Math.max(0L, windowAfter);
            long start = failureMs > before ? failureMs - before : 0L;
            long end = failureMs > Long.MAX_VALUE - after
                    ? Long.MAX_VALUE : failureMs + after;
            ArrayList<TraceSample> samples = new ArrayList<>();
            for (TraceSample sample : trace.samples()) {
                if (sample.tMs() >= start && sample.tMs() <= end) samples.add(sample);
            }
            return new PathReview(samples, failureMs);
        }

        public long failureMs() { return failureMs; }
        public List<TraceSample> window() { return window; }
    }

    // =====================================================================
    // Stable code / hash / numeric helpers
    // =====================================================================

    private static String requireNonEmpty(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return value;
    }

    private static byte[] utf8(String value) {
        Objects.requireNonNull(value, "value");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        // Java's UTF-8 encoder replaces unpaired UTF-16 surrogates by default.
        // Reject such input instead of silently changing a persisted ID/detail.
        if (!new String(encoded, StandardCharsets.UTF_8).equals(value)) {
            throw new IllegalArgumentException("string contains malformed UTF-16 surrogate data");
        }
        return encoded;
    }

    private static int levelIdCode(String levelId) {
        byte[] bytes = utf8(levelId);
        int crc = 0xFFFF;
        for (byte value : bytes) {
            crc ^= (value & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    private static int eventTypeCode(String type) {
        if ("START".equals(type)) return EVENT_START;
        if ("OBJECTIVE".equals(type)) return EVENT_OBJECTIVE;
        if ("BLOCKED".equals(type)) return EVENT_BLOCKED;
        if ("GATE_PASS".equals(type)) return EVENT_GATE_PASS;
        if ("PAUSE".equals(type)) return EVENT_PAUSE;
        if ("RESUME".equals(type)) return EVENT_RESUME;
        if ("INTERRUPT".equals(type)) return EVENT_INTERRUPT;
        if ("FAIL".equals(type)) return EVENT_FAIL;
        if ("COMPLETE".equals(type)) return EVENT_COMPLETE;
        return 0;
    }

    private static String eventTypeName(int code) {
        switch (code) {
            case EVENT_START: return "START";
            case EVENT_OBJECTIVE: return "OBJECTIVE";
            case EVENT_BLOCKED: return "BLOCKED";
            case EVENT_GATE_PASS: return "GATE_PASS";
            case EVENT_PAUSE: return "PAUSE";
            case EVENT_RESUME: return "RESUME";
            case EVENT_INTERRUPT: return "INTERRUPT";
            case EVENT_FAIL: return "FAIL";
            case EVENT_COMPLETE: return "COMPLETE";
            default: return null;
        }
    }

    private static byte[] parseHex(String value) {
        Objects.requireNonNull(value, "hex");
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("hex string must have even length");
        }
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = hexDigit(value.charAt(i * 2));
            int low = hexDigit(value.charAt(i * 2 + 1));
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid hex string");
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static int hexDigit(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        return -1;
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            output[i * 2] = digits[value >>> 4];
            output[i * 2 + 1] = digits[value & 0x0F];
        }
        return new String(output);
    }
}
