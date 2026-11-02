package com.justinneed.session.chunk.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.dto.ChunkUploadRequest;
import com.justinneed.session.chunk.dto.ChunkUploadResponse;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 청크 수신. 같은 청크가 다시 오면 저장하지 않고 처음 받은 것을 그대로 돌려준다 (재전송해도 안전).
 * 동시에 같은 청크가 두 번 들어오면 유니크 제약에 걸린 쪽을 중복으로 처리한다 — 그래서 트랜잭션으로 묶지 않는다.
 * 받기 전 검사 (확장 #34):
 *   - payload.memberId 가 없으면 400, 토큰의 회원과 다르면 403 — 남의 기록이 이 회원 세션으로 저장되지 않게
 *   - 같은 (회원, 세션 id, n) 이 이미 있는데 내용이 다르면 409 — "이미 받음" 으로 답하면 확장이 지워서
 *     새 내용이 조용히 사라진다. 내용이 같을 때만 200 duplicate
 * 받은 뒤(처음 · 다시 온 것 모두) ChunkSessionService 가 세션을 만들고, 다 왔으면 합쳐 분석으로 넘긴다.
 *   그 처리가 실패하면 요청도 실패(500) — 확장이 다시 보내면 이미 받은 청크라도 처리를 다시 돈다.
 */
@Service
public class SessionChunkService {

    private final SessionRawChunkRepository chunkRepository;
    private final ChunkSessionService chunkSessionService;

    public SessionChunkService(SessionRawChunkRepository chunkRepository, ChunkSessionService chunkSessionService) {
        this.chunkRepository = chunkRepository;
        this.chunkSessionService = chunkSessionService;
    }

    public ChunkUploadResponse receive(Long userId, ChunkUploadRequest request) {
        var response = store(userId, request);
        chunkSessionService.advance(userId, request.sessionId());
        return response;
    }

    private ChunkUploadResponse store(Long userId, ChunkUploadRequest request) {
        checkMember(userId, request.payload());
        var existing = find(userId, request);
        if (existing != null) {
            return duplicateOf(existing, request);
        }
        try {
            SessionRawChunk saved = chunkRepository.saveAndFlush(new SessionRawChunk(
                    userId, request.sessionId(), request.n(),
                    request.lastChunk(), request.maxN(), request.stopReason(),
                    request.payload(), LocalDateTime.now()));
            return ChunkUploadResponse.of(saved, false);
        } catch (DataIntegrityViolationException race) {
            var winner = find(userId, request);
            if (winner == null) {
                throw race;
            }
            return duplicateOf(winner, request);
        }
    }

    private static void checkMember(Long userId, Map<String, Object> payload) {
        Object memberId = payload.get("memberId");
        if (!(memberId instanceof Number number) || !isWhole(number)) {
            throw new CustomException(ErrorCode.CHUNK_MEMBER_REQUIRED);
        }
        if (number.longValue() != userId) {
            throw new CustomException(ErrorCode.CHUNK_MEMBER_MISMATCH);
        }
    }

    private static boolean isWhole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger;
    }

    private static ChunkUploadResponse duplicateOf(SessionRawChunk stored, ChunkUploadRequest request) {
        boolean same = stored.isFinalChunk() == request.lastChunk()
                && Objects.equals(stored.getMaxN(), request.maxN())
                && Objects.equals(stored.getStopReason(), request.stopReason())
                && sameJson(stored.getPayload(), request.payload());
        if (!same) {
            throw new CustomException(ErrorCode.CHUNK_CONTENT_CONFLICT);
        }
        return ChunkUploadResponse.of(stored, true);
    }

    // JSON 값 비교. 숫자는 값으로 비교한다 — DB(jsonb)에서 읽은 숫자와 요청의 숫자가 다른 자바 타입(Integer · Long · Double)일 수 있다
    static boolean sameJson(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (var k : x.keySet()) {
                if (!sameJson(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!sameJson(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    private SessionRawChunk find(Long userId, ChunkUploadRequest request) {
        return chunkRepository.findByUserIdAndCollectorSessionIdAndN(
                userId, request.sessionId(), request.n()).orElse(null);
    }
}
