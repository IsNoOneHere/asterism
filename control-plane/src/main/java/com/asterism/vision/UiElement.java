package com.asterism.vision;

public record UiElement(String type, String description) {
    public String contextText() {
        var label = type == null || type.isBlank() ? "element" : type;
        return label + ": " + (description == null ? "" : description);
    }
}
