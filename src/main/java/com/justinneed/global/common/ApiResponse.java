package com.justinneed.global.common;

import org.jspecify.annotations.Nullable;

public record ApiResponse<T>(boolean success, @Nullable T data, @Nullable String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
