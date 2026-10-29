package com.justinneed.surfing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
public final class SurfRequests {
    private SurfRequests() {}
    public enum EndReason { USER, BROWSER_CLOSED }
    public record End(@NotNull EndReason reason) {}
    public record Batch(@NotEmpty @Size(max=100) List<@NotNull @Valid Event> events) {}
    public record Event(@NotBlank @Size(max=64) String clientId, @NotNull SurfEvent.Type type,
        @NotBlank @Size(max=2048) String url, @Size(max=300) String title,
        @Size(max=20000) String content, @NotNull Instant occurredAt,
        @Min(0) @Max(60000) long activeMillis) {}
    public record Scrap(@NotBlank @Size(max=64) String clientId,
        @NotBlank @Size(max=2048) String url, @Size(max=300) String title,
        @NotBlank @Size(max=10000) String text) {}
}
