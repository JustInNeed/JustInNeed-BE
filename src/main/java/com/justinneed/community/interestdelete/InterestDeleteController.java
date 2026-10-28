package com.justinneed.community.interestdelete;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.community.edit.ProfileEditService;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/me/interests")
public class InterestDeleteController {
    private final InterestDeleteService service;
    private final ProfileEditService edit;

    public InterestDeleteController(InterestDeleteService service, ProfileEditService edit) {
        this.service = service;
        this.edit = edit;
    }

    @DeleteMapping({"/by-name/{hashtag}", "/{hashtag:[^0-9].*}"})
    public ApiResponse<List<String>> delete(
            @AuthenticationPrincipal Long userId, @PathVariable String hashtag) {
        return ApiResponse.ok(service.delete(userId, hashtag));
    }

    @DeleteMapping("/{interestId:[0-9]+}")
    public ApiResponse<ProfileEditService.DeletedInterest> deleteId(
            @AuthenticationPrincipal Long userId, @PathVariable Long interestId) {
        return ApiResponse.ok(edit.deleteInterest(userId, interestId));
    }
}
