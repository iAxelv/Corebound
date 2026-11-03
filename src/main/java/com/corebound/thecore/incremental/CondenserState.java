package com.corebound.thecore.incremental;

public enum CondenserState {
    ACTIVE,
    IDLE,
    DISABLED;

    public CondenserState nextManualState() {
        return this == DISABLED ? ACTIVE : DISABLED;
    }
}
