package dev.infinia.store.contract.type;

/**
 * The store's member-level ladder, branded Infinia Level: every account
 * carries one of five hive roles (the bee marks stay as the ladder's emblem), and listings may set a {@code minBeeLevel}
 * so only sufficiently senior bees can view and download them.
 *
 * <p>Level 0 ({@link #LARVA}) is the default for new accounts; anonymous
 * visitors are treated as below every gated level. A listing gate of 0 means
 * public (no restriction).</p>
 */
public enum BeeLevel {
    LARVA(0),
    WORKER(1),
    FORAGER(2),
    GUARD(3),
    QUEEN(4);

    public static final int MAX_LEVEL = QUEEN.level;

    public final int level;

    BeeLevel(int level) {
        this.level = level;
    }

    /** The enum value for a stored numeric level; unknown values clamp to LARVA. */
    public static BeeLevel of(int level) {
        for (BeeLevel value : values()) {
            if (value.level == level) {
                return value;
            }
        }
        return LARVA;
    }

    /** True when the numeric level is inside the ladder (0..{@link #MAX_LEVEL}). */
    public static boolean isValid(int level) {
        return level >= LARVA.level && level <= MAX_LEVEL;
    }
}
