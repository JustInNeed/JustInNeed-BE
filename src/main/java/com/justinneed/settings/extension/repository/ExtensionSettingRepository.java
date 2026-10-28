package com.justinneed.settings.extension.repository;

import com.justinneed.settings.extension.domain.ExtensionSetting;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtensionSettingRepository extends JpaRepository<ExtensionSetting, Long> {

    Optional<ExtensionSetting> findByMemberId(Long memberId);
}
