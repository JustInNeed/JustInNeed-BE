package com.justinneed.community.edit;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.pin.SharedSessionController;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class ProfileEditController {
    private final ProfileEditService service;
    private final ProfileSaveService profileSave;
    public ProfileEditController(ProfileEditService service, ProfileSaveService profileSave) {
        this.service = service; this.profileSave = profileSave;
    }

    @PatchMapping("/me/profile")
    public ApiResponse<ProfileEditService.BioView> bio(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody BioRequest request) {
        return ApiResponse.ok(service.bio(userId, request.bio()));
    }
    @PutMapping("/me/profile")
    public ApiResponse<Void> save(@AuthenticationPrincipal Long userId, @Valid @RequestBody ProfileRequest request) {
        profileSave.save(userId, request.bio(), request.visibility(), request.interests(),
                request.hiddenHashtagIds(), request.pinnedSessionIds());
        return ApiResponse.ok();
    }
    @PatchMapping("/me/hashtags/{hashtagId}/visibility")
    public ApiResponse<ProfileEditService.TagView> visibility(@AuthenticationPrincipal Long userId,
            @PathVariable Long hashtagId, @Valid @RequestBody PublicRequest request) {
        return ApiResponse.ok(service.visibility(userId, hashtagId, request.isPublic()));
    }
    @PostMapping("/me/pinned-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProfileEditService.PinView> pin(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody SharedSessionController.PinRequest request) {
        return ApiResponse.ok(service.pin(userId, request.sessionId()));
    }
    @DeleteMapping("/me/pinned-sessions/{sessionId}")
    public ApiResponse<ProfileEditService.PinView> unpin(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        return ApiResponse.ok(service.unpin(userId, sessionId));
    }
    @PatchMapping("/me/pinned-sessions/order")
    public ApiResponse<ProfileEditService.OrderView> order(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody SharedSessionController.OrderRequest request) {
        return ApiResponse.ok(service.reorder(userId, request.sessionIds()));
    }
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<ProfileEditService.SessionView> session(@AuthenticationPrincipal Long viewerId, @PathVariable Long sessionId) {
        return ApiResponse.ok(service.session(viewerId, sessionId));
    }
    public record BioRequest(@NotNull(message = "소개를 입력해 주세요.")
            @Size(max = CommunityProfile.BIO_MAX_LENGTH, message = "소개는 120자까지 입력할 수 있습니다.") String bio) { }
    public record ProfileRequest(@NotNull(message = "소개를 입력해 주세요.")
            @Size(max = CommunityProfile.BIO_MAX_LENGTH, message = "소개는 120자까지 입력할 수 있습니다.") String bio,
            @NotNull(message = "공개 범위를 입력해 주세요.") NodeVisibility visibility,
            @NotNull(message = "관심 해시태그 목록을 입력해 주세요.")
            List<@NotNull(message = "관심 해시태그가 올바르지 않습니다.") String> interests,
            @NotNull(message = "비공개 해시태그 목록을 입력해 주세요.")
            List<@NotNull(message = "해시태그가 올바르지 않습니다.") Long> hiddenHashtagIds,
            @NotNull(message = "고정할 세션 목록을 입력해 주세요.")
            List<@NotNull(message = "세션이 올바르지 않습니다.") Long> pinnedSessionIds) { }
    public record PublicRequest(@NotNull(message = "공개 여부를 입력해 주세요.") Boolean isPublic) { }
}
