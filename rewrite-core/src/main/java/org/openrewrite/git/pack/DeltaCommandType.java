package org.openrewrite.git.pack;

import static java.util.Arrays.stream;

public enum DeltaCommandType {
    INSERT(0),
    COPY(1);

    private final int type;

    DeltaCommandType(int type) {
        this.type = type;
    }

    public static DeltaCommandType fromType(int type) {
        return stream(DeltaCommandType.values()).filter(v -> v.type == type)
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("Unknown type " + type));
    }
}
