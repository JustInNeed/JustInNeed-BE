package com.justinneed.settings.extension.service;

import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.extension.domain.ExtensionSetting;
import com.justinneed.settings.extension.dto.ExtensionSettingResponse;
import com.justinneed.settings.extension.repository.ExtensionSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ExtensionSettingService {

    private final ExtensionSettingRepository extensionSettingRepository;
    private final MemberRepository memberRepository;

    public ExtensionSettingService(ExtensionSettingRepository extensionSettingRepository,
            MemberRepository memberRepository) {
        this.extensionSettingRepository = extensionSettingRepository;
        this.memberRepository = memberRepository;
    }

    // 아직 저장한 적이 없으면 행을 만들지 않고 기본값(모두 켜짐)을 준다
    @Transactional(readOnly = true)
    public ExtensionSettingResponse getSettings(Long memberId) {
        ExtensionSetting setting = extensionSettingRepository.findByMemberId(memberId)
                .orElseGet(() -> new ExtensionSetting(memberId));
        return ExtensionSettingResponse.of(memberId, setting);
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

    // 회원 행을 잠근 뒤 읽는다. 행이 없을 때의 동시 생성과, 다른 토글을 동시에 저장할 때 값이 덮이는 것을 막는다
    private ExtensionSetting getOrCreate(Long memberId) {
        memberRepository.lockActiveById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
        return extensionSettingRepository.findByMemberId(memberId)
                .orElseGet(() -> extensionSettingRepository.save(new ExtensionSetting(memberId)));
    }
}
