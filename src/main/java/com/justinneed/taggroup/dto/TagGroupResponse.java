package com.justinneed.taggroup.dto;

import com.justinneed.session.management.dto.SessionListResponse;
import com.justinneed.taggroup.domain.TagGroup;
import java.util.List;

public record TagGroupResponse(
        Long id,
        String name,
        List<String> hashtags,
        int position,
        List<SessionListResponse> sessions
) {

    public static TagGroupResponse from(TagGroup group, List<SessionListResponse> sessions) {
        return new TagGroupResponse(
                group.getId(),
                group.getName(),
                group.getHashtags(),
                group.getPosition(),
                sessions
        );
    }
}
