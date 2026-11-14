package com.justinneed.settings.collection.service;

import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.collection.domain.ExcludedUrl;
import com.justinneed.settings.collection.dto.response.ExcludedUrlCreateResponse;
import com.justinneed.settings.collection.dto.response.ExcludedUrlDeleteResponse;
import com.justinneed.settings.collection.dto.response.ExcludedUrlItem;
import com.justinneed.settings.collection.dto.response.ExcludedUrlListResponse;
import com.justinneed.settings.collection.dto.response.ExcludedUrlSelectionUpdateResponse;
import com.justinneed.settings.collection.repository.ExcludedUrlRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ExcludedUrlService {

    private static final int MAX_URL_COUNT = 100;
    private static final List<String> DEFAULT_URLS = List.of(
            "instagram.com",
            "mail.google.com",
            "youtube.com/shorts",
            "twitter.com",
            "tiktok.com"
    );

    private final ExcludedUrlRepository excludedUrlRepository;
    private final MemberRepository memberRepository;

    public ExcludedUrlService(ExcludedUrlRepository excludedUrlRepository, MemberRepository memberRepository) {
        this.excludedUrlRepository = excludedUrlRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional
    public ExcludedUrlListResponse getExcludedUrls(Long memberId) {
        ensureDefaultUrls(memberId);
        return new ExcludedUrlListResponse(
                200,
                excludedUrlRepository.findByMemberIdOrderByExcludedDescDomainAscUrlAsc(memberId).stream()
                        .map(ExcludedUrlItem::from)
                        .toList()
        );
    }

    /**
     * 지금 수집 제외가 적용된 URL (표시형). 세션 원본을 저장하기 전에 제외 페이지를 거를 때 쓴다.
     * 기본 URL 은 설정 화면을 한 번도 열지 않아 행이 없어도 체크된 상태이므로 포함한다. 행을 만들지는 않는다.
     */
    public List<String> findActiveUrls(Long memberId) {
        List<ExcludedUrl> rows = excludedUrlRepository.findByMemberId(memberId);
        Set<String> stored = new LinkedHashSet<>();
        List<String> active = new ArrayList<>();
        for (ExcludedUrl row : rows) {
            stored.add(row.getUrl());
            if (row.isExcluded()) {
                active.add(row.getUrl());
            }
        }
        for (String defaultUrl : DEFAULT_URLS) {
            String url = ExcludedUrlParser.parse(defaultUrl).url();
            if (!stored.contains(url)) {
                active.add(url);
            }
        }
        return active;
    }

    @Transactional
    public ExcludedUrlCreateResponse create(Long memberId, String rawUrl) {
        ExcludedUrlParser.ParsedUrl parsed = ExcludedUrlParser.parse(rawUrl);
        lockMember(memberId);
        addMissingDefaultUrls(memberId);
        if (excludedUrlRepository.existsByMemberIdAndUrl(memberId, parsed.url())) {
            throw new CustomException(ErrorCode.DUPLICATE_URL);
        }
        if (excludedUrlRepository.countByMemberId(memberId) >= MAX_URL_COUNT) {
            throw new CustomException(ErrorCode.EXCLUDED_URL_LIMIT_EXCEEDED);
        }

        ExcludedUrl saved = excludedUrlRepository.save(
                new ExcludedUrl(memberId, parsed.url(), parsed.domain(), false));
        return new ExcludedUrlCreateResponse(
                201,
                saved.getId(),
                saved.getUrl(),
                saved.getDomain(),
                saved.isDefaultUrl(),
                saved.isExcluded(),
                saved.getCreatedAt() == null ? LocalDateTime.now() : saved.getCreatedAt()
        );
    }

    @Transactional
    public ExcludedUrlSelectionUpdateResponse updateSelections(
            Long memberId, List<Long> requestedIds, boolean excluded
    ) {
        List<Long> ids = distinctIds(requestedIds);
        lockMember(memberId);
        List<ExcludedUrl> urls = findOwnedUrls(memberId, ids);
        urls.forEach(url -> url.updateExcluded(excluded));
        return new ExcludedUrlSelectionUpdateResponse(200, urls.size(), excluded, ids);
    }

    @Transactional
    public ExcludedUrlDeleteResponse delete(Long memberId, List<Long> requestedIds) {
        List<Long> ids = distinctIds(requestedIds);
        lockMember(memberId);
        List<ExcludedUrl> urls = findOwnedUrls(memberId, ids);
        List<Long> deletedIds = new ArrayList<>();
        List<Long> uncheckedDefaultIds = new ArrayList<>();

        for (ExcludedUrl url : urls) {
            if (url.isDefaultUrl()) {
                url.updateExcluded(false);
                uncheckedDefaultIds.add(url.getId());
            } else {
                excludedUrlRepository.delete(url);
                deletedIds.add(url.getId());
            }
        }

        return new ExcludedUrlDeleteResponse(
                200, deletedIds, uncheckedDefaultIds, deletedIds.size());
    }

    private List<ExcludedUrl> findOwnedUrls(Long memberId, List<Long> ids) {
        List<ExcludedUrl> urls = excludedUrlRepository.findAllByMemberIdAndIdIn(memberId, ids);
        if (urls.size() != ids.size()) {
            throw new CustomException(ErrorCode.EXCLUDED_URL_NOT_FOUND);
        }
        return urls;
    }

    private List<Long> distinctIds(List<Long> requestedIds) {
        Set<Long> ids = new LinkedHashSet<>(requestedIds);
        if (ids.contains(null)) {
            throw new CustomException(ErrorCode.EXCLUDED_URL_NOT_FOUND);
        }
        return List.copyOf(ids);
    }

    // 기본 URL 이 다 있으면 잠그지 않고 끝난다. 첫 조회가 동시에 와도 잠근 쪽만 넣는다
    private void ensureDefaultUrls(Long memberId) {
        if (excludedUrlRepository.countByMemberIdAndUrlIn(memberId, DEFAULT_URLS) == DEFAULT_URLS.size()) {
            return;
        }
        lockMember(memberId);
        addMissingDefaultUrls(memberId);
    }

    private void lockMember(Long memberId) {
        memberRepository.lockActiveById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
    }

    private void addMissingDefaultUrls(Long memberId) {
        for (String defaultUrl : DEFAULT_URLS) {
            ExcludedUrlParser.ParsedUrl parsed = ExcludedUrlParser.parse(defaultUrl);
            if (!excludedUrlRepository.existsByMemberIdAndUrl(memberId, parsed.url())) {
                excludedUrlRepository.save(
                        new ExcludedUrl(memberId, parsed.url(), parsed.domain(), true));
            }
        }
    }
}
