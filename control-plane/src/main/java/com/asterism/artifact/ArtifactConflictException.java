package com.asterism.artifact;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ArtifactConflictException extends RuntimeException {
    public ArtifactConflictException(String message) {
        super(message);
    }
}
