-- V2 · 04 章 3.1 P1-8（2026-09-18）：retry_count 拆分为 auto_retry_count + manual_retry_count
-- 语义：auto_retry_count = 引擎侧认领次数（首次处理 + 自动退避重试 + 恢复重投 + 手动提交后的处理认领）；
--       manual_retry_count = 用户手动重试次数（POST /document/{id}/retry）。
--       attempt（前端「第几轮」）= auto + manual = 原 retry_count，展示语义不变。
-- 历史数据无法区分自动/手动，保守全部归入 auto（总认领次数不变，attempt 展示不受影响）。

ALTER TABLE document_task
    ADD COLUMN auto_retry_count   int NOT NULL DEFAULT 0,
    ADD COLUMN manual_retry_count int NOT NULL DEFAULT 0;

UPDATE document_task SET auto_retry_count = retry_count;

ALTER TABLE document_task DROP COLUMN retry_count;
