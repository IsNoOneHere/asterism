package com.asterism.projection;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleContractTest {
    @Test
    void javaStateMachineMatchesSharedLifecycleContract() throws Exception {
        var json = Files.readString(Path.of("..", "docs", "lifecycle-transitions.json"));
        var transitions = new ObjectMapper().readValue(json, new TypeReference<Map<String, List<String>>>() {
        });

        for (var from : LifecycleStatus.values()) {
            var allowed = transitions.get(from.name());
            assertThat(allowed).as("missing contract status " + from.name()).isNotNull();
            for (var to : LifecycleStatus.values()) {
                assertThat(LifecycleStateMachine.canMove(from, to))
                        .as(from.name() + " -> " + to.name())
                        .isEqualTo(from == to || allowed.contains(to.name()));
            }
        }
    }
}
