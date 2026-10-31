package com.justinneed.settings.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.settings.collection.repository.ExcludedUrlRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExcludedUrlServiceTest {

    @Mock
    private ExcludedUrlRepository repository;

    @Mock
    private MemberRepository memberRepository;

    private ExcludedUrlService service;

    @BeforeEach
    void setUp() {
        service = new ExcludedUrlService(repository, memberRepository);
        when(memberRepository.lockActiveById(1L)).thenReturn(Optional.of(mock(Member.class)));
        when(repository.existsByMemberIdAndUrl(any(), any())).thenReturn(true);
    }

    @Test
    void createRejectsDuplicateUrlAfterNormalization() {
        assertThatThrownBy(() -> service.create(1L, "https://www.instagram.com/"))
                .isInstanceOf(CustomException.class);
    }
}
