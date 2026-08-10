package com.asterism.artifact;

public enum ArtifactType {
    PRODUCT,
    PLANNING,
    CODING,
    VALIDATION,
    RELEASE;

    /** 五类产物的父子关系由系统固定，不能由 Agent 决定。 */
    public ArtifactType parentType() {
        return switch (this) {
            case PRODUCT -> null;
            case PLANNING -> PRODUCT;
            case CODING -> PLANNING;
            case VALIDATION -> CODING;
            case RELEASE -> VALIDATION;
        };
    }
}
