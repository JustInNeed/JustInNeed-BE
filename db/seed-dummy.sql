
DO $$
DECLARE
    sid bigint;
BEGIN
    -- ── 세션 1: 로마 3박 4일 동선 짜기 (즐겨찾기) ──
    INSERT INTO sessions
        (user_id, title, started_at, ended_at, page_count, status,
         is_favorite, is_public, tags, sources, created_at, updated_at)
    VALUES
        (1, '로마 3박 4일 동선 짜기',
         TIMESTAMP '2026-04-26 21:14:00', TIMESTAMP '2026-04-26 22:48:00', 18, 'COMPLETED',
         true, false,
         '["로마","콜로세움","바티칸","먹거리"]'::jsonb,
         '[
            {"title":"tripadvisor.com","url":"https://www.tripadvisor.com/rome","excerpt":"콜로세움-포로 로마노-팔라티노 통합권 정보"},
            {"title":"reddit.com","url":"https://www.reddit.com/r/rome","excerpt":"현지인 추천 트라스테베레 식당 후기"},
            {"title":"theculturetrip.com","url":"https://theculturetrip.com/europe/italy/rome","excerpt":"바티칸 박물관 한산한 시간대 가이드"},
            {"title":"youtube.com","url":"https://www.youtube.com/watch?v=rome-walk","excerpt":"로마 도보 동선 브이로그"},
            {"title":"blog.naver.com","url":"https://blog.naver.com/rome-trip","excerpt":"카르보나라 정통 레시피와 맛집"}
         ]'::jsonb,
         now(), now())
    RETURNING id INTO sid;

    INSERT INTO summaries (session_id, markdown)
    VALUES (sid,
        E'로마 시내 주요 명소 동선을 검토했고, 콜로세움-포로 로마노-팔라티노 통합권을 사전 예매하는 것이 합리적임을 확인. 바티칸은 수요일 오전 일찍 입장하는 것이 인파를 피하기 좋음. 트라스테베레 지구의 현지 식당 3곳 (Da Enzo, Tonnarello, Roma Sparita) 후보로 정함.');

    -- ── 세션 2: 유럽 항공권 비교 (즐겨찾기 X) ──
    INSERT INTO sessions
        (user_id, title, started_at, ended_at, page_count, status,
         is_favorite, is_public, tags, sources, created_at, updated_at)
    VALUES
        (1, '유럽 항공권 비교 (인천→로마 IN, 프라하→인천 OUT)',
         TIMESTAMP '2026-04-25 14:00:00', TIMESTAMP '2026-04-25 15:19:00', 11, 'COMPLETED',
         false, false,
         '["항공권","스카이스캐너","예산"]'::jsonb,
         '[
            {"title":"skyscanner.co.kr","url":"https://www.skyscanner.co.kr","excerpt":"인천-로마 / 프라하-인천 멀티시티 최저가 비교"},
            {"title":"google.com","url":"https://www.google.com/flights","excerpt":"전체 기간 보기로 최저 출발일 탐색"},
            {"title":"blog.naver.com","url":"https://blog.naver.com/flight-tip","excerpt":"유럽 IN-OUT 다른 도시 발권 노하우"}
         ]'::jsonb,
         now(), now())
    RETURNING id INTO sid;

    INSERT INTO summaries (session_id, markdown)
    VALUES (sid,
        E'인천→로마 IN, 프라하→인천 OUT 멀티시티 발권이 단순 왕복보다 저렴한 구간을 확인. 스카이스캐너 전체 기간 보기로 5월 중순 평일 출발이 최저가. 경유 1회까지는 가격 대비 합리적이라고 판단.');

    -- ── 세션 3: 파리 미술관 4곳 비교 (즐겨찾기) ──
    INSERT INTO sessions
        (user_id, title, started_at, ended_at, page_count, status,
         is_favorite, is_public, tags, sources, created_at, updated_at)
    VALUES
        (1, '파리 미술관 4곳 비교 (루브르/오르세/오랑주리/퐁피두)',
         TIMESTAMP '2026-04-24 10:05:00', TIMESTAMP '2026-04-24 11:30:00', 14, 'COMPLETED',
         true, false,
         '["파리","미술관","루브르","오르세"]'::jsonb,
         '[
            {"title":"parismuseumpass.fr","url":"https://www.parismuseumpass.fr","excerpt":"파리 박물관 패스 2일권 55€ 적용 범위"},
            {"title":"louvre.fr","url":"https://www.louvre.fr","excerpt":"루브르 야간 개장(수·금) 및 예매 정보"},
            {"title":"musee-orsay.fr","url":"https://www.musee-orsay.fr","excerpt":"오르세 인상주의 컬렉션 하이라이트"}
         ]'::jsonb,
         now(), now())
    RETURNING id INTO sid;

    INSERT INTO summaries (session_id, markdown)
    VALUES (sid,
        E'파리 박물관 패스(2일권 55€)가 4곳 합산 가격(67€)보다 저렴. 단, 첫째 주 일요일은 일부 무료. 오르세 vs 루브르: 인상주의 → 오르세, 르네상스 → 루브르.');

    -- ── 해시태그 그룹 1개 ──
    INSERT INTO tag_groups (user_id, name, hashtags, position, created_at, updated_at)
    VALUES (1, '유럽 여행 준비',
            '["파리","로마","베네치아","콜로세움","항공권","숙소"]'::jsonb,
            0, now(), now());
END $$;
