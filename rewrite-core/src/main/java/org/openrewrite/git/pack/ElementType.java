package org.openrewrite.git.pack;

import static java.util.Arrays.stream;

public enum ElementType {
    OBJ_COMMIT(0b001),
    OBJ_TREE(0b010),
    OBJ_BLOB(0b011),
    OBJ_TAG(0b100),
    OBJ_REF_DELTA(0b111);

    private final int type;

    ElementType(int type) {
        this.type = type;
    }

    public static ElementType fromType(int type) {
        return stream(ElementType.values()).filter(v -> v.type == type)
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("Unknown type " + type));
    }
}
