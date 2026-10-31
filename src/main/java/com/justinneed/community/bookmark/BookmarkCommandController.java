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
        return ApiResponse.ok(service.save(actor, body.targetType(), body.targetIds(), body.saveType(),
                body.sourceMindmapId(), body.name()));
    }
    @DeleteMapping("/{bookmarkId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long actor, @PathVariable @Positive Long bookmarkId) {
        service.delete(actor, bookmarkId);
        return ApiResponse.ok(null);
    }
    public record SaveRequest(@NotNull(message = "대상 종류를 입력해 주세요.") TargetType targetType,
            @NotEmpty(message = "저장할 대상을 선택해 주세요.") @Size(max = 100, message = "저장할 대상은 최대 100개까지 선택할 수 있습니다.")
            List<@NotNull(message = "대상이 올바르지 않습니다.") @Positive(message = "대상이 올바르지 않습니다.") Long> targetIds,
            @NotNull(message = "저장 방식을 입력해 주세요.") SaveType saveType,
            @Positive(message = "원본 마인드맵이 올바르지 않습니다.") Long sourceMindmapId,
            String name) { }
}
