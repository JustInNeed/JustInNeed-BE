package com.justinneed.settings.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.settings.collection.domain.ExcludedUrl;
import com.justinneed.settings.collection.repository.ExcludedUrlRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("지금 적용 중인 수집 제외 URL (세션 원본 거르기용)")
class ExcludedUrlActiveUrlsTest {

    private final ExcludedUrlRepository repository = mock(ExcludedUrlRepository.class);
    private final ExcludedUrlService service = new ExcludedUrlService(repository, mock(MemberRepository.class));

    @Test
    @DisplayName("행이 없으면 기본 URL 5개가 모두 적용 중")
    void defaultsApplyWithoutRows() {
        when(repository.findByMemberId(1L)).thenReturn(List.of());

        assertThat(service.findActiveUrls(1L)).containsExactlyInAnyOrder(
                "instagram.com", "mail.google.com", "youtube.com/shorts", "twitter.com", "tiktok.com");
    }

    @Test
    @DisplayName("체크 해제한 URL 은 빠지고, 기본 URL 도 해제했으면 빠진다")
    void uncheckedUrlsAreLeftOut() {
        var instagram = new ExcludedUrl(1L, "instagram.com", "instagram.com", true);
        instagram.updateExcluded(false);
        var naver = new ExcludedUrl(1L, "naver.com/4", "naver.com", false);
        var off = new ExcludedUrl(1L, "example.com", "example.com", false);
        off.updateExcluded(false);
        when(repository.findByMemberId(1L)).thenReturn(List.of(instagram, naver, off));

        assertThat(service.findActiveUrls(1L)).containsExactlyInAnyOrder(
                "naver.com/4", "mail.google.com", "youtube.com/shorts", "twitter.com", "tiktok.com");
    }
}
