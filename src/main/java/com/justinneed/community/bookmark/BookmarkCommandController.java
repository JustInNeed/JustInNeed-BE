package com.justinneed.community.bookmark;

import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/bookmarks")
public class BookmarkCommandController {
    private final BookmarkCommandService service;
    public BookmarkCommandController(BookmarkCommandService service) { this.service = service; }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BookmarkCommandService.Saved> save(@AuthenticationPrincipal Long actor,
            @Valid @RequestBody SaveRequest body) {
        return ApiResponse.ok(service.save(actor, body.targetType(), body.targetIds(), body.saveType(), body.sourceMindmapId()));
    }
    @DeleteMapping("/{bookmarkId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long bookmarkId) {
        service.delete(actor, bookmarkId);
        return ApiResponse.ok(null);
    }
    public record SaveRequest(@NotNull TargetType targetType,
            @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> targetIds,
            @NotNull SaveType saveType, @Positive Long sourceMindmapId) { }
}
