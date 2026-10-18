-- 새 DB 를 db/schema 전체로 만들었음을 기록한다. 항상 마지막에 실행되어야 하므로 파일명 접두사를 9999 로 고정한다.
INSERT INTO schema_history (filename, applied_at, applied_by, verified_by, note)
VALUES ('fresh@dev', UTC_TIMESTAMP(6), CURRENT_USER(), 'initdb', 'docker-entrypoint-initdb.d로 생성');
