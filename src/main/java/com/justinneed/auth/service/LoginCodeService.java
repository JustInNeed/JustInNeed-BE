package com.justinneed.auth.service;

import com.justinneed.auth.domain.LoginCode;
import com.justinneed.auth.domain.Member;
import com.justinneed.auth.dto.ExtensionCodeResponse;
import com.justinneed.auth.dto.LoginTokenResponse;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.jwt.TokenHashes;
import com.justinneed.auth.repository.LoginCodeRepository;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인 성공 → FE 로 토큰을 넘기는 일회용 로그인 코드.
 * 토큰을 리다이렉트 URL 에 실으면 FE 호스팅 접근 로그 · 브라우저 기록에 남으므로,
 * URL 에는 코드만 담고 FE 가 POST /auth/token 으로 코드를 토큰과 바꾼다.
 * - 코드: 32바이트 난수(base64url, 패딩 없음). DB 에는 SHA-256 해시만 저장
 * - 60초 안에 한 번만 바꿀 수 있다 (바꾸는 순간 행 삭제)
 */
@Service
public class LoginCodeService {

    private static final long CODE_VALIDITY_SECONDS = 60;
    private static final int CODE_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final LoginCodeRepository loginCodeRepository;
    private final AuthTokenService authTokenService;
    private final MemberRepository memberRepository;
    private final JwtTokenProvider jwtTokenProvider;

    public LoginCodeService(LoginCodeRepository loginCodeRepository, AuthTokenService authTokenService,
            MemberRepository memberRepository, JwtTokenProvider jwtTokenProvider) {
        this.loginCodeRepository = loginCodeRepository;
        this.authTokenService = authTokenService;
        this.memberRepository = memberRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /** 로그인 성공 시: 코드 발급. 원문은 리다이렉트 URL 로만 나간다 */
    @Transactional
    public String issue(Long memberId, boolean needsNickname) {
        return issue(memberId, needsNickname, null);
    }

    /**
     * POST /auth/extension-code: 로그인한 웹이 확장에 넘길 코드 발급.
     * 확장은 POST /auth/token 으로 바꿔 자기 토큰을 받는다. 웹과 같은 묶음이라 로그아웃은 함께 되지만,
     * refresh 토큰은 따로라 재발급해도 서로 영향을 주지 않는다.
     * 묶음이 없는 토큰(도입 전 발급)이나 이미 로그아웃된 묶음이면 401: FE 가 재발급으로 새 묶음을 받거나 로그인으로 돌아간다
     */
    @Transactional
    public ExtensionCodeResponse issueForExtension(Long memberId, String accessToken) {
        String groupId = jwtTokenProvider.getGroupId(accessToken);
        if (!authTokenService.isActiveGroup(groupId)) {
            throw new CustomException(ErrorCode.LOGIN_EXPIRED);
        }
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.LOGIN_EXPIRED));
        String code = issue(memberId, member.getNickname() == null, groupId);
        return new ExtensionCodeResponse(code, CODE_VALIDITY_SECONDS);
    }

    private String issue(Long memberId, boolean needsNickname, String groupId) {
        byte[] random = new byte[CODE_BYTES];
        secureRandom.nextBytes(random);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(CODE_VALIDITY_SECONDS);
        loginCodeRepository.save(new LoginCode(TokenHashes.sha256(code), memberId, needsNickname, expiresAt, groupId));
        return code;
    }

    /** POST /auth/token: 코드 → access/refresh 토큰. 없거나 · 만료됐거나 · 이미 쓴 코드면 401 */
    @Transactional
    public LoginTokenResponse exchange(String code) {
        if (code == null || code.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_LOGIN_CODE);
        }
        String codeHash = TokenHashes.sha256(code);
        LoginCode loginCode = loginCodeRepository.findById(codeHash)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_LOGIN_CODE));

        // 코드를 받은 뒤 60초 안에 탈퇴한 경우
        if (memberRepository.existsByIdAndWithdrawnAtIsNotNull(loginCode.getMemberId())) {
            throw new CustomException(ErrorCode.INVALID_LOGIN_CODE);
        }

        // 코드를 받은 뒤 60초 안에 웹에서 로그아웃한 경우
        if (loginCode.getGroupId() != null && !authTokenService.isActiveGroup(loginCode.getGroupId())) {
            throw new CustomException(ErrorCode.INVALID_LOGIN_CODE);
        }

        // 같은 코드로 동시에 바꿔도 행을 실제로 지운 요청 하나만 토큰을 받는다 (만료 여부도 DB 기준으로 함께 확인)
        int used = loginCodeRepository.deleteUsable(codeHash, LocalDateTime.now());
        if (used != 1) {
            throw new CustomException(ErrorCode.INVALID_LOGIN_CODE);
        }

        TokenResponse tokens = authTokenService.issue(loginCode.getMemberId(), loginCode.getGroupId());
        return new LoginTokenResponse(tokens.accessToken(), tokens.refreshToken(), loginCode.isNeedsNickname());
    }
}
