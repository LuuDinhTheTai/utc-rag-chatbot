-- Run with psql -v ON_ERROR_STOP=1. Fixtures are rolled back; no persistent seed data.
BEGIN;

DO $$
DECLARE
    n integer;
BEGIN
    SELECT count(*) INTO n FROM pg_tables WHERE schemaname = 'public';
    IF n <> 48 THEN RAISE EXCEPTION 'Expected 48 tables, found %', n; END IF;
    IF NOT EXISTS (SELECT FROM pg_extension WHERE extname = 'vector') THEN
        RAISE EXCEPTION 'Missing vector extension';
    END IF;
    IF (SELECT format_type(atttypid, atttypmod) FROM pg_attribute
        WHERE attrelid = 'document_chunks'::regclass AND attname = 'embedding') <> 'vector(768)' THEN
        RAISE EXCEPTION 'Embedding must be vector(768)';
    END IF;
END $$;

DO $$
DECLARE
    u uuid := '00000000-0000-0000-0000-000000000001';
    c uuid := '00000000-0000-0000-0000-000000000002';
    m uuid := '00000000-0000-0000-0000-000000000003';
    a uuid := '00000000-0000-0000-0000-000000000004';
BEGIN
    INSERT INTO users VALUES (u, 'schema-test@example.invalid', 'test', 'test', 0, now());
    INSERT INTO role_assignments VALUES (-9001, u, 'test', NULL, now());
    BEGIN
        INSERT INTO role_assignments VALUES (-9002, u, 'test', NULL, now());
        RAISE EXCEPTION 'Duplicate role with NULL campus was accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;

    INSERT INTO conversations VALUES (c, u, NULL, NULL, NULL, now(), now());
    INSERT INTO messages VALUES (m, c, 1, 'test', 'test', now());
    BEGIN
        INSERT INTO messages VALUES (gen_random_uuid(), c, 1, 'test', 'test', now());
        RAISE EXCEPTION 'Duplicate message position was accepted';
    EXCEPTION WHEN unique_violation THEN NULL;
    END;
    INSERT INTO chat_feedback_entries VALUES (a, m, true, NULL, now());
    DELETE FROM messages WHERE id = m;
    IF EXISTS (SELECT FROM chat_feedback_entries WHERE answer_id = a AND message_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Feedback message reference was not cleared';
    END IF;

    BEGIN
        INSERT INTO schedule_events (id, title, starts_at) VALUES (-9001, 'test', now());
        RAISE EXCEPTION 'Schedule without a parent was accepted';
    EXCEPTION WHEN check_violation THEN NULL;
    END;
END $$;

DO $$
DECLARE
    a uuid := '00000000-0000-0000-0000-000000000005';
BEGIN
    INSERT INTO questionnaires VALUES (-9001, 'schema-test', 'test');
    INSERT INTO questionnaire_versions VALUES
        (-9001, -9001, 1, 'test', 'test', '{}'),
        (-9002, -9001, 2, 'test', 'test', '{}');
    INSERT INTO personality_questions VALUES
        (-9001, -9001, 1, 'test'), (-9002, -9002, 1, 'test');
    INSERT INTO personality_options VALUES
        (-9001, -9001, 1, 'test', '{}'), (-9002, -9002, 1, 'test', '{}');
    INSERT INTO personality_attempts
        (id, questionnaire_version_id, guest_token_hash, status, created_at)
        VALUES (a, -9001, 'test', 'test', now());
    BEGIN
        INSERT INTO personality_answers VALUES (a, -9002, -9002, -9002);
        RAISE EXCEPTION 'Answer from a different questionnaire version was accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    BEGIN
        INSERT INTO personality_answers VALUES (a, -9001, -9002, -9001);
        RAISE EXCEPTION 'Option belonging to another question was accepted';
    EXCEPTION WHEN foreign_key_violation THEN NULL;
    END;
    INSERT INTO personality_answers VALUES (a, -9001, -9001, -9001);
END $$;

DO $$
DECLARE
    v vector(768);
BEGIN
    v := array_fill(0::real, ARRAY[768])::vector;
    BEGIN
        v := array_fill(0::real, ARRAY[767])::vector;
        RAISE EXCEPTION 'Wrong embedding dimension was accepted';
    EXCEPTION WHEN data_exception THEN NULL;
    END;
END $$;

ROLLBACK;
SELECT 'Schema checks passed; fixtures rolled back.' AS result;
