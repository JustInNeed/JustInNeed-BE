package com.justinneed.surfing;
import com.justinneed.global.common.ApiResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice(basePackages={"com.justinneed.surfing","com.justinneed.analytics"}) @Order(0)
public class SurfErrors {
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> header(Exception e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("X-Extension-Token header required"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(e.getReason()));
    }
    public static ResponseStatusException bad(String reason) { return new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,reason); }
    public static ResponseStatusException missing() { return new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Surf session not found"); }
    public static ResponseStatusException conflict(String reason) { return new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,reason); }
}
