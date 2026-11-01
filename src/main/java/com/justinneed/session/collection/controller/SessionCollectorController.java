package com.justinneed.session.collection.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.session.collection.dto.CollectorEndRequest;
import com.justinneed.session.collection.dto.CollectorSessionResponse;
import com.justinneed.session.collection.dto.CollectorStartRequest;
import com.justinneed.session.collection.service.SessionCollectionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 확장 → Spring 세션 시작 · 종료. 둘 다 다시 보내도 같은 세션을 돌려준다
@RestController
@RequestMapping("/sessions/collector")
public class SessionCollectorController {

    private final SessionCollectionService collectionService;

    public SessionCollectorController(SessionCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    // 새로 만들면 201, 이미 있으면 200
    @PostMapping("/start")
    public ResponseEntity<ApiResponse<CollectorSessionResponse>> start(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CollectorStartRequest request
    ) {
        CollectorSessionResponse response = collectionService.start(userId, request);
        HttpStatus status = response.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }

    @PostMapping("/{collectorSessionId}/end")
    public ApiResponse<CollectorSessionResponse> end(
            @AuthenticationPrincipal Long userId,
            @PathVariable String collectorSessionId,
            @Valid @RequestBody CollectorEndRequest request
    ) {
        return ApiResponse.ok(collectionService.end(userId, collectorSessionId, request));
    }
}
