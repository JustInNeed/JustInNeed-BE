package com.justinneed.settings.integration.domain;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.Arrays;
import java.util.List;

public enum IntegrationProvider {
    NOTION(
            "notion",
            "Notion 연동 방법",
            List.of(
                    "Notion 계정 이메일을 입력합니다.",
                    "연동 요청 버튼을 선택합니다.",
                    "인증 메일 또는 연동 확인 절차를 완료합니다.",
                    "연동 상태가 연결됨으로 표시되는지 확인합니다."
            )
    ),
    GOOGLE_DOCS(
            "googleDocs",
            "Google Docs 연동 방법",
            List.of(
                    "Google 계정 이메일을 입력합니다.",
                    "연동 요청 버튼을 선택합니다.",
                    "Google 계정의 공유 권한을 확인합니다.",
                    "연동 상태가 연결됨으로 표시되는지 확인합니다."
            )
    ),
    KAKAO_TALK(
            "kakaoTalk",
            "KakaoTalk 연동 방법",
            List.of(
                    "Kakao 계정 이메일을 입력합니다.",
                    "연동 요청 버튼을 선택합니다.",
                    "Kakao 계정의 연동 동의를 완료합니다.",
                    "연동 상태가 연결됨으로 표시되는지 확인합니다."
            )
    );

    private final String apiName;
    private final String guideTitle;
    private final List<String> guideSteps;

    IntegrationProvider(String apiName, String guideTitle, List<String> guideSteps) {
        this.apiName = apiName;
        this.guideTitle = guideTitle;
        this.guideSteps = guideSteps;
    }

    public static IntegrationProvider fromApiName(String value) {
        return Arrays.stream(values())
                .filter(provider -> provider.apiName.equals(value))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INTEGRATION_PROVIDER));
    }

    public String getApiName() {
        return apiName;
    }

    public String getGuideTitle() {
        return guideTitle;
    }

    public List<String> getGuideSteps() {
        return guideSteps;
    }
}
