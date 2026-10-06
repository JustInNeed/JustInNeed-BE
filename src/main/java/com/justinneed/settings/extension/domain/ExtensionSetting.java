package com.justinneed.settings.extension.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "extension_settings",
        uniqueConstraints = @UniqueConstraint(name = "uk_extension_settings_member", columnNames = "member_id")
)
public class ExtensionSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "popup_visible", nullable = false)
    private boolean popupVisible = true;

    @Column(name = "auto_end_notification_enabled", nullable = false)
    private boolean autoEndNotificationEnabled = true;

    @Column(name = "manual_scrap_enabled", nullable = false)
    private boolean manualScrapEnabled = true;

    protected ExtensionSetting() {
    }

    public ExtensionSetting(Long memberId) {
        this.memberId = memberId;
    }

    public void updatePopupVisible(boolean enabled) {
        popupVisible = enabled;
    }

    public void updateAutoEndNotification(boolean enabled) {
        autoEndNotificationEnabled = enabled;
    }

    public void updateManualScrap(boolean enabled) {
        manualScrapEnabled = enabled;
    }

    public Long getMemberId() {
        return memberId;
    }

    public boolean isPopupVisible() {
        return popupVisible;
    }

    public boolean isAutoEndNotificationEnabled() {
        return autoEndNotificationEnabled;
    }

    public boolean isManualScrapEnabled() {
        return manualScrapEnabled;
    }
}
