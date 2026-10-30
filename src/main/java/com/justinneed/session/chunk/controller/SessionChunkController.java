package com.justinneed.session.chunk.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.session.chunk.dto.ChunkUploadRequest;
import com.justinneed.session.chunk.dto.ChunkUploadResponse;
import com.justinneed.session.chunk.service.SessionChunkService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions/chunks")
public class SessionChunkController {

    private final SessionChunkService chunkService;

    public SessionChunkController(SessionChunkService chunkService) {
        this.chunkService = chunkService;
    }

    // 처음 받은 청크는 201, 이미 받은 청크는 저장하지 않고 200
    @PostMapping
    public ResponseEntity<ApiResponse<ChunkUploadResponse>> upload(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ChunkUploadRequest request
    ) {
        ChunkUploadResponse response = chunkService.receive(userId, request);
        HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(ApiResponse.ok(response));
    }
}
