/* 定期扫描超过宽限期的对象，并按配置预览或删除孤儿对象。 */

package com.pony.knowledge.service.storage;

import com.pony.knowledge.mapper.DocumentMapper;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对照数据库引用和 MinIO 对象列表，补偿文档删除后遗留的孤儿文件。
 * 功能默认关闭。预览和删除使用相同的候选清单，单个对象删除失败留待后续扫描。
 */
@Component
public class MinioReclaimService {

    private static final Logger log = LoggerFactory.getLogger(MinioReclaimService.class);

    private final MinioClient minioClient;

    private final DocumentMapper documentMapper;

    private final String bucketName;

    private final ReclaimMode mode;

    private final long gracePeriodHours;

    /**
     * 由 Spring 注入扫描依赖和配置，启动时解析回收模式。
     *
     * @param minioClient 访问对象存储服务的客户端
     * @param documentMapper 提供文档对象引用的数据库查询
     * @param bucketName 文档对象所在的 MinIO 桶
     * @param configuredMode 显式回收模式，空值沿用旧开关
     * @param legacyEnabled 旧回收开关，启用时按原行为删除候选对象
     * @param gracePeriodHours 对象进入候选范围前的宽限期（小时）
     */
    public MinioReclaimService(MinioClient minioClient, DocumentMapper documentMapper,
                               @Value("${minio.bucket-name}") String bucketName,
                               @Value("${storage.reclaim.mode:}") String configuredMode,
                               @Value("${storage.reclaim.enabled:false}") boolean legacyEnabled,
                               @Value("${storage.reclaim.grace-period-hours:24}") long gracePeriodHours) {
        this.minioClient = minioClient;
        this.documentMapper = documentMapper;
        this.bucketName = bucketName;
        this.mode = ReclaimMode.resolve(configuredMode, legacyEnabled);
        this.gracePeriodHours = gracePeriodHours;
    }

    /**
     * 由定时调度器触发一次扫描，以日志输出预览或删除结果。
     * 下游读取 DocumentMapper 和 MinIO，候选判定交给 MinioReclaimPlanner。
     */
    @Scheduled(fixedDelayString = "${storage.reclaim.scan-interval-ms:86400000}",
               initialDelayString = "${storage.reclaim.initial-delay-ms:300000}")
    public void reclaimOrphanObjects() {
        if (mode == ReclaimMode.DISABLED) {
            log.debug("孤儿对象回收已关闭");
            return;
        }

        Set<String> referenced;

        try {
            referenced = new HashSet<>(documentMapper.selectAllFilePaths());
        } catch (Exception e) {
            log.error("读取 document.file_path 失败，跳过本轮回收扫描", e);
            return;
        }
        if (referenced.isEmpty()) {
            log.warn("数据库未返回文档对象引用，跳过本轮回收扫描");
            return;
        }

        ZonedDateTime cutoff = ZonedDateTime.now(ZoneId.systemDefault()).minusHours(gracePeriodHours);
        List<String> staleKeys;

        try {
            staleKeys = listKeysOlderThan(cutoff);
        } catch (Exception e) {
            log.error("读取 MinIO 对象列表失败，跳过本轮回收扫描", e);
            return;
        }

        Set<String> orphans = MinioReclaimPlanner.findOrphans(staleKeys, referenced);
        if (mode == ReclaimMode.DRY_RUN) {
            for (String key : orphans) {
                log.info("孤儿对象预览 object={} bucket={}", key, bucketName);
            }
            log.info("孤儿对象预览完成 bucket={} 宽限期={}h 候选数={}", bucketName, gracePeriodHours, orphans.size());
            return;
        }

        int deleted = deleteOrphans(orphans);
        log.info("孤儿对象回收完成: 宽限期 {}h, 过宽限期的对象 {} 个, DB 引用 {} 条, 判定孤儿 {} 个, 删除 {} 个",
                gracePeriodHours, staleKeys.size(), referenced.size(), orphans.size(), deleted);
    }

    /**
     * 在删除模式下逐个移除已确认的候选对象，单个失败时继续处理。
     * 由 reclaimOrphanObjects 调用，删除操作交给 MinIO 客户端。
     *
     * @param orphans 通过宽限期和引用检查的对象键
     * @return 成功删除的对象数
     */
    private int deleteOrphans(Set<String> orphans) {
        int deleted = 0;

        for (String key : orphans) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName).object(key).build());
                deleted++;
                log.info("孤儿对象已回收: object={}, bucket={}", key, bucketName);
            } catch (Exception e) {
                log.warn("孤儿对象删除失败（下轮再试）: object={}", key, e);
            }
        }
        return deleted;
    }

    /**
     * 扫描桶中早于给定时间的对象键。
     * 上游为 MinioReclaimService.reclaimOrphanObjects。
     *
     * @param cutoff 本次操作所用的 ZonedDateTime 数据
     * @return 与过滤条件匹配的 List&lt;String&gt; 数据集合
     */
    private List<String> listKeysOlderThan(ZonedDateTime cutoff) throws Exception {
        List<String> staleKeys = new ArrayList<>();
        Iterable<Result<Item>> objects = minioClient.listObjects(
                ListObjectsArgs.builder().bucket(bucketName).recursive(true).build());

        for (Result<Item> result : objects) {
            Item item = result.get();
            ZonedDateTime lastModified = item.lastModified();
            if (lastModified != null && lastModified.isBefore(cutoff)) {
                staleKeys.add(item.objectName());
            }
        }
        return staleKeys;
    }

    private enum ReclaimMode {
        DISABLED, DRY_RUN, DELETE;

        /**
         * 在服务装配时解析显式模式，缺省时兼容原有开关。
         *
         * @param configuredMode 三种模式之一，允许空值
         * @param legacyEnabled 缺省模式下是否沿用原有删除行为
         * @return 此服务使用的回收模式
         */
        private static ReclaimMode resolve(String configuredMode, boolean legacyEnabled) {
            if (configuredMode == null || configuredMode.isBlank()) {
                return legacyEnabled ? DELETE : DISABLED;
            }
            return switch (configuredMode.strip()) {
                case "disabled" -> DISABLED;
                case "dry-run" -> DRY_RUN;
                case "delete" -> DELETE;
                default -> throw new IllegalArgumentException("storage.reclaim.mode 必须为 disabled、dry-run 或 delete");
            };
        }
    }
}
