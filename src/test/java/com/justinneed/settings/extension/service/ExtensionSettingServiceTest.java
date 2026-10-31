package com.justinneed.settings.extension.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.settings.extension.dto.ExtensionSettingResponse;
import com.justinneed.settings.extension.repository.ExtensionSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ExtensionSettingServiceTest {

    @Autowired
    private ExtensionSettingService extensionSettingService;

    @Autowired
    private ExtensionSettingRepository extensionSettingRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void updatesOnlyRequestedSettingAndKeepsOneRowPerMember() {
        Long memberId = memberRepository.save(new Member(SocialProvider.KAKAO, "extension", "toggler", null)).getId();

        ExtensionSettingResponse popup = extensionSettingService.updatePopupVisible(memberId, false);
        ExtensionSettingResponse notification = extensionSettingService.updateAutoEndNotification(memberId, false);
        ExtensionSettingResponse manualScrap = extensionSettingService.updateManualScrap(memberId, false);

        assertThat(popup.popupVisible()).isFalse();
        assertThat(popup.autoEndNotificationEnabled()).isNull();
        assertThat(notification.autoEndNotificationEnabled()).isFalse();
        assertThat(manualScrap.manualScrapEnabled()).isFalse();
        assertThat(extensionSettingRepository.count()).isEqualTo(1);
    }
}
