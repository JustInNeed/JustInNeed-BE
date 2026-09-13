package com.justinneed.settings.collection.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.justinneed.global.exception.CustomException;
import com.justinneed.settings.collection.repository.ExcludedUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExcludedUrlServiceTest {

    @Mock
    private ExcludedUrlRepository repository;

    private ExcludedUrlService service;

    @BeforeEach
    void setUp() {
        service = new ExcludedUrlService(repository);
        when(repository.existsByMemberIdAndUrl(any(), any())).thenReturn(true);
    }

    @Test
    void createRejectsDuplicateUrlAfterNormalization() {
        assertThatThrownBy(() -> service.create(1L, "https://www.instagram.com/"))
                .isInstanceOf(CustomException.class);
    }
}
