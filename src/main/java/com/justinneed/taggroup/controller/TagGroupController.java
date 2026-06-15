package com.justinneed.taggroup.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.taggroup.dto.TagGroupCreateRequest;
import com.justinneed.taggroup.dto.TagGroupOrderRequest;
import com.justinneed.taggroup.dto.TagGroupResponse;
import com.justinneed.taggroup.dto.TagGroupUpdateRequest;
import com.justinneed.taggroup.service.TagGroupService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tag-groups")
public class TagGroupController {

    private final TagGroupService tagGroupService;

    public TagGroupController(TagGroupService tagGroupService) {
        this.tagGroupService = tagGroupService;
    }

    @GetMapping
    public ApiResponse<List<TagGroupResponse>> getTagGroups(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return ApiResponse.ok(tagGroupService.getTagGroups(userId));
    }

    @PostMapping
    public ApiResponse<TagGroupResponse> createTagGroup(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @Valid @RequestBody TagGroupCreateRequest request
    ) {
        return ApiResponse.ok(tagGroupService.createTagGroup(userId, request));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TagGroupResponse> updateTagGroup(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody TagGroupUpdateRequest request
    ) {
        return ApiResponse.ok(tagGroupService.updateTagGroup(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTagGroup(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id
    ) {
        tagGroupService.deleteTagGroup(userId, id);
        return ApiResponse.ok();
    }

    @PatchMapping("/order")
    public ApiResponse<List<TagGroupResponse>> updateOrder(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @Valid @RequestBody TagGroupOrderRequest request
    ) {
        return ApiResponse.ok(tagGroupService.updateOrder(userId, request));
    }
}
