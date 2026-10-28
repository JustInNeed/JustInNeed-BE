package com.justinneed.settings.extension.service;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void updatesOnlyRequestedSettingAndKeepsOneRowPerMember() {
        ExtensionSettingResponse popup = extensionSettingService.updatePopupVisible(1L, false);
        ExtensionSettingResponse notification = extensionSettingService.updateAutoEndNotification(1L, false);
        ExtensionSettingResponse manualScrap = extensionSettingService.updateManualScrap(1L, false);

        assertThat(popup.popupVisible()).isFalse();
        assertThat(popup.autoEndNotificationEnabled()).isNull();
        assertThat(notification.autoEndNotificationEnabled()).isFalse();
        assertThat(manualScrap.manualScrapEnabled()).isFalse();
        assertThat(extensionSettingRepository.count()).isEqualTo(1);
    }
}
