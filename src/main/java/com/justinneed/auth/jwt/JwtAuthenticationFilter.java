package com.justinneed.auth.jwt;

import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.repository.RefreshTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;
    private final MemberRepository memberRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            TokenDenylist tokenDenylist,
            MemberRepository memberRepository,
            RefreshTokenRepository refreshTokenRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenDenylist = tokenDenylist;
        this.memberRepository = memberRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null
                && jwtTokenProvider.validate(token)
                && jwtTokenProvider.isAccessToken(token)
                && !tokenDenylist.isDenied(token)
                && isGroupActive(token)) {
            Long memberId = jwtTokenProvider.getMemberId(token);
            // 탈퇴는 그때 쓴 토큰만 denylist 에 넣으므로, 다른 기기에 남은 토큰은 여기서 막는다
            if (!memberRepository.existsByIdAndWithdrawnAtIsNotNull(memberId)) {
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                memberId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 로그아웃하면 같은 묶음(웹 + 확장 프로그램)의 refresh 행이 모두 지워진다. 묶음이 사라진 access 토큰은 확장이 로그아웃 알림을 못 받았어도 바로 막는다
     * (QA-045). 묶음 도입 전에 발급된 토큰(grp 없음)은 denylist 로만 판단한다.
     */
    private boolean isGroupActive(String token) {
        String groupId = jwtTokenProvider.getGroupId(token);
        return groupId == null || refreshTokenRepository.existsByGroupId(groupId);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}
