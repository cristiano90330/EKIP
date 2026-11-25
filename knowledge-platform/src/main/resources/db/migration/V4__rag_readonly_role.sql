-- 只读角色（决策文档 §4 第 5 项「重切边界」）：Python 侧 /rag/query 直连 PG 做检索。
--
-- 安全模型不变：授权仍在 Java（KnowledgeBaseAccessService），Python 只拿到这个只读角色、
-- 在 Java 传入的 knowledgeBaseIds 范围内读 document_chunk，绝不写库、绝不自判权限。
-- 连接层再加 default_transaction_read_only=on 作纵深防御（见 ai/app/retrieval/db.py）。
--
-- ⚠️ 密码是本地开发默认值（与 docker-compose 里 123456 / minioadmin 同一套「本地开发裸凭据」约定）。
--    生产部署必须换：ALTER ROLE rag_readonly PASSWORD '<强口令>'，并把 AI 容器的
--    PG_READONLY_USER / PG_READONLY_PASSWORD 指过去。默认 Python 侧回落 POSTGRES_*（postgres 超管），
--    本地开发零配置即可跑通，只读由「应用层只发 SELECT + 会话级只读」兜底。
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'rag_readonly') THEN
        CREATE ROLE rag_readonly LOGIN PASSWORD 'rag_readonly_dev';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO rag_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO rag_readonly;
-- 未来 Flyway 迁移建的新表（由 postgres 用户创建）自动对 rag_readonly 授 SELECT。
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT SELECT ON TABLES TO rag_readonly;
