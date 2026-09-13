package com.justinneed.settings.extension.service;

import com.justinneed.settings.extension.domain.ExtensionSetting;
import com.justinneed.settings.extension.dto.ExtensionSettingResponse;
import com.justinneed.settings.extension.repository.ExtensionSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExtensionSettingService {

    private final ExtensionSettingRepository extensionSettingRepository;

    public ExtensionSettingService(ExtensionSettingRepository extensionSettingRepository) {
        this.extensionSettingRepository = extensionSettingRepository;
    }

    public ExtensionSettingResponse updatePopupVisible(Long memberId, boolean enabled) {
        ExtensionSetting setting = getOrCreate(memberId);
        setting.updatePopupVisible(enabled);
        extensionSettingRepository.flush();
        return ExtensionSettingResponse.popup(memberId, setting.isPopupVisible(), setting.getUpdatedAt());
    }

    public ExtensionSettingResponse updateAutoEndNotification(Long memberId, boolean enabled) {
        ExtensionSetting setting = getOrCreate(memberId);
        setting.updateAutoEndNotification(enabled);
        extensionSettingRepository.flush();
        return ExtensionSettingResponse.autoEndNotification(
                memberId,
                setting.isAutoEndNotificationEnabled(),
                setting.getUpdatedAt()
        );
    }

    public ExtensionSettingResponse updateManualScrap(Long memberId, boolean enabled) {
        ExtensionSetting setting = getOrCreate(memberId);
        setting.updateManualScrap(enabled);
        extensionSettingRepository.flush();
        return ExtensionSettingResponse.manualScrap(memberId, setting.isManualScrapEnabled(), setting.getUpdatedAt());
    }

    private ExtensionSetting getOrCreate(Long memberId) {
        return extensionSettingRepository.findByMemberId(memberId)
                .orElseGet(() -> extensionSettingRepository.save(new ExtensionSetting(memberId)));
    }
}
