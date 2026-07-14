package com.asterism.vision;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record UiObservation(
        @JsonProperty("page_title") String pageTitle,
        @JsonProperty("text_anchors") List<String> textAnchors,
        @JsonProperty("ui_elements") List<String> uiElements,
        @JsonProperty("error_messages") List<String> errorMessages,
        @JsonProperty("user_visible_summary") String userVisibleSummary) {

    public String contextText() {
        return "页面标题: " + text(pageTitle) + "；文字锚点: " + String.join("、", list(textAnchors))
                + "；界面元素: " + String.join("、", list(uiElements))
                + "；错误信息: " + String.join("、", list(errorMessages));
    }

    public List<String> anchors() {
        var values = new java.util.ArrayList<String>();
        if (pageTitle != null && !pageTitle.isBlank()) values.add(pageTitle);
        values.addAll(list(textAnchors));
        values.addAll(list(errorMessages));
        return values;
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private List<String> list(List<String> value) {
        return value == null ? List.of() : value;
    }
}
