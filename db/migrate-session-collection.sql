-- 세션 수집 · AI 연동 이관 (Postgres). 여러 번 실행해도 안전하다.
-- ddl-auto: update 는 새 컬럼 · 테이블만 만들고, 옛 유니크 제약과 NOT NULL 은 지우지 못한다.
-- 그대로 두면 새 청크 insert 가 seg_id · n · is_final NOT NULL 에 걸려 실패한다.
--
-- 실행 시점: 새 앱을 띄우기 직전. 앱이 먼저 떠서 일부를 만들어 둔 뒤에 실행해도 된다.
-- 옛 청크 행은 지우지 않는다. 옛 형식이라 합치기에는 쓰이지 않고, 세션과 이어지지 않아 정리 대상도 아니다.
BEGIN;

-- 1. sessions: 수집기 세션 id
ALTER TABLE sessions ADD COLUMN IF NOT EXISTS collector_session_id varchar(64);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_sessions_user_collector') THEN
        ALTER TABLE sessions
            ADD CONSTRAINT uk_sessions_user_collector UNIQUE (user_id, collector_session_id);
    END IF;
END $$;

-- 2. session_raw_chunks: 중복 판단 키를 (user_id, collector_session_id, chunk_id) 로 변경
ALTER TABLE session_raw_chunks ADD COLUMN IF NOT EXISTS chunk_id varchar(128);

DO $$
BEGIN
    -- 옛 컬럼이 있는 DB 에서만. 새로 만든 DB 에는 없다
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = current_schema() AND table_name = 'session_raw_chunks' AND column_name = 'seg_id') THEN
        UPDATE session_raw_chunks SET chunk_id = seg_id || ':' || n WHERE chunk_id IS NULL;
        ALTER TABLE session_raw_chunks DROP CONSTRAINT IF EXISTS uk_session_raw_chunks_key;
        ALTER TABLE session_raw_chunks ALTER COLUMN seg_id DROP NOT NULL;
        ALTER TABLE session_raw_chunks ALTER COLUMN n DROP NOT NULL;
        ALTER TABLE session_raw_chunks ALTER COLUMN is_final DROP NOT NULL;
    END IF;
END $$;

ALTER TABLE session_raw_chunks ALTER COLUMN chunk_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_session_raw_chunks_chunk') THEN
        ALTER TABLE session_raw_chunks
            ADD CONSTRAINT uk_session_raw_chunks_chunk UNIQUE (user_id, collector_session_id, chunk_id);
    END IF;
END $$;

-- 3. session_collections: 수집 · AI 호출 진행 상태 (세션당 1행)
CREATE TABLE IF NOT EXISTS session_collections (
    session_id          bigint PRIMARY KEY,
    start_requested_at  timestamp(6),
    last_chunk_at       timestamp(6),
    end_requested_at    timestamp(6),
    requested_ended_at  timestamp(6),
    stop_reason         varchar(50),
    total_chunks        integer,
    merge_attempts      integer NOT NULL DEFAULT 0,
    ai_requested_at     timestamp(6),
    ai_last_called_at   timestamp(6),
    ai_resend_count     integer NOT NULL DEFAULT 0,
    ai_unanswered_count integer NOT NULL DEFAULT 0,
    input_fetched_at    timestamp(6),
    failed_at           timestamp(6)
);

-- 4. 이관 시점에 분석 중인 세션: 진행 기록을 만들어 재전송 · 대기 상한 규칙에 들어오게 한다.
-- AI 를 새 버전으로 바꾸면 옛 요청은 사라지므로, 재전송으로 다시 받거나 상한이 지나면 FAILED 가 된다
INSERT INTO session_collections (session_id, ai_requested_at, ai_last_called_at)
SELECT id, now(), now()
FROM sessions
WHERE status = 'ANALYZING' AND deleted_at IS NULL
ON CONFLICT (session_id) DO NOTHING;

COMMIT;

-- 선택: 이미 FAILED 인 세션에도 21일 보관을 적용하려면 아래를 실행한다. 원본이 실제로 삭제되므로 필요할 때만 실행한다.
-- 실행하지 않으면 그 세션들의 원본은 expires_at 이 없어 삭제되지 않고 재분석도 계속 할 수 있다.
--
-- INSERT INTO session_collections (session_id, failed_at)
-- SELECT id, updated_at FROM sessions WHERE status = 'FAILED'
-- ON CONFLICT (session_id) DO UPDATE SET failed_at = EXCLUDED.failed_at
-- WHERE session_collections.failed_at IS NULL;
--
-- UPDATE session_raw_payloads payload
-- SET expires_at = session.updated_at + interval '21 days'
-- FROM sessions session
-- WHERE session.id = payload.session_id AND session.status = 'FAILED' AND payload.expires_at IS NULL;
