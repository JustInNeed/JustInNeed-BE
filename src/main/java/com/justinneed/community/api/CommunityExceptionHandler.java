package com.justinneed.community.api;

import com.justinneed.global.common.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.justinneed.community")
public class CommunityExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(ApiResponse.error(error.getReason()));
    }
    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiResponse<Void>> validation(Exception error) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid community request parameters"));
    }
}
