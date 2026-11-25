/* 按保留天数定期清除过期审计记录。 */

package com.pony.knowledge.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * 审计日志保留策略：每日清扫 {@code created_at} 早于保留期的行。
 *
 * <p>为什么必须有它：{@code audit_log} 是 append-only 的，没有任何业务路径会删它 ——
 * 没有清扫就是一张只涨不降的表，半年后查询与备份都会被它拖慢。
 *
 * <p>为什么不放进 cron 或消息队列：单实例、每日一次、一条带索引的 DELETE 而已，
 * 借用 AsyncConfig 已注册的 {@code @Scheduled} 调度器即可（与 MinioReclaimService、
 * TaskRecoveryService 同一套路），不新增运行组件。
 *
 * <p>{@code audit.retention.days <= 0} 表示关闭清扫（合规场景要求整库外归档时用），
 * 关闭态只打一行 debug，不碰库。
 */
@Component
public class AuditLogRetention {

    private static final Logger log = LoggerFactory.getLogger(AuditLogRetention.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${audit.retention.days:90}")
    private int retentionDays;

    /**
     * 保存按保留天数定期清除过期审计记录所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param jdbcTemplate 负责数据库 SQL 的 JDBC 组件
     */
    public AuditLogRetention(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 计算保留期对应的截止时间并删除更早的审计记录。
     * 上游为 应用的定时调度器。
     */
    @Scheduled(fixedDelayString = "${audit.retention.scan-interval-ms:86400000}",
               initialDelayString = "${audit.retention.initial-delay-ms:600000}")
    public void purgeExpired() {
        if (retentionDays <= 0) {
            log.debug("审计日志清扫已禁用（audit.retention.days={}），跳过", retentionDays);
            return;
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofDays(retentionDays)));
        try {
            int deleted = jdbcTemplate.update("DELETE FROM audit_log WHERE created_at < ?", cutoff);
            if (deleted > 0) {
                log.info("审计日志清扫完成：保留 {} 天（{} 之前），删除 {} 行", retentionDays, cutoff, deleted);
            } else {
                log.debug("审计日志清扫：无过期行（截止 {}）", cutoff);
            }
        } catch (Exception e) {
            // 清扫失败不是业务故障：留待下一轮，不阻塞任何请求路径。
            log.warn("审计日志清扫失败，下一轮再试（截止 {}）", cutoff, e);
        }
    }
}
