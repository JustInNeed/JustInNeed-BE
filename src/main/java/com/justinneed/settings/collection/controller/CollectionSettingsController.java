package com.justinneed.settings.collection.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.settings.collection.dto.request.ExcludedUrlCreateRequest;
import com.justinneed.settings.collection.dto.request.ExcludedUrlDeleteRequest;
import com.justinneed.settings.collection.dto.request.ExcludedUrlSelectionUpdateRequest;
import com.justinneed.settings.collection.dto.response.ExcludedUrlCreateResponse;
import com.justinneed.settings.collection.dto.response.ExcludedUrlDeleteResponse;
import com.justinneed.settings.collection.dto.response.ExcludedUrlListResponse;
import com.justinneed.settings.collection.dto.response.ExcludedUrlSelectionUpdateResponse;
import com.justinneed.settings.collection.service.ExcludedUrlService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings/collection/excluded-urls")
public class CollectionSettingsController {

    private final ExcludedUrlService excludedUrlService;

    public CollectionSettingsController(ExcludedUrlService excludedUrlService) {
        this.excludedUrlService = excludedUrlService;
    }

    @GetMapping
    public ApiResponse<ExcludedUrlListResponse> getExcludedUrls(
            @AuthenticationPrincipal Long memberId,
            @RequestParam(defaultValue = "true") boolean grouped,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return new ApiResponse<>(true,
                excludedUrlService.getExcludedUrls(memberId, grouped, page, size),
                "수집 제외 URL 목록을 조회했습니다.");
    }

    @PatchMapping("/selections")
    public ApiResponse<ExcludedUrlSelectionUpdateResponse> updateSelections(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ExcludedUrlSelectionUpdateRequest request
    ) {
        return new ApiResponse<>(true,
                excludedUrlService.updateSelections(memberId, request.excludedUrlIds(), request.isExcluded()),
                "URL 선택 상태가 변경되었습니다.");
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ExcludedUrlCreateResponse>> create(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ExcludedUrlCreateRequest request
    ) {
        ExcludedUrlCreateResponse response = excludedUrlService.create(memberId, request.url());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, response, "수집 제외 URL이 추가되었습니다."));
    }

    @DeleteMapping
    public ApiResponse<ExcludedUrlDeleteResponse> delete(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ExcludedUrlDeleteRequest request
    ) {
        return new ApiResponse<>(true,
                excludedUrlService.delete(memberId, request.excludedUrlIds()),
                "선택한 URL이 삭제되었습니다.");
    }
}
