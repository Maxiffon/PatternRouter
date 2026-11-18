package patternrouter.common;

/** Why a candidate list is empty or partial. Sent by ordinal: never reorder, only append. */
public enum ListNote {

    NONE,
    /** No encoded pattern in the terminal's output slot. */
    NO_PATTERN,
    /** The terminal is not on a network. */
    NO_GRID,
    /** No build permission in this network. */
    DENIED,
    /** No machine fits; every interface with free space is listed. */
    NO_MACHINE,
    /** Not a GT recipe, or not transferred from NEI. */
    UNKNOWN_RECIPE,
    /** No machine fits, and some hatch has no formed multiblock. */
    UNFORMED,
    /** Crafting pattern, no molecular assembler in the network. */
    NO_ASSEMBLER;

    private static final ListNote[] VALUES = values();

    public int wire() {
        return ordinal();
    }

    /** The note for a received value; null if none was received yet (-1), NONE for one this build doesn't know. */
    public static ListNote fromWire(int value) {
        if (value < 0) return null;
        return value < VALUES.length ? VALUES[value] : NONE;
    }
}
