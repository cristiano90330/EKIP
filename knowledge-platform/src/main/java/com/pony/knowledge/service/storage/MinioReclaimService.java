/* 定期扫描满足保护期的对象，并根据当前引用关系回收孤儿。 */

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
 * 定时回收数据库已无引用且超过宽限期的 MinIO 对象，补偿文档删除后对象清理失败留下的孤儿文件。
 * 功能默认关闭；数据库引用或 bucket 列表读取失败时整轮不删除，单个对象删除失败留待后续扫描。
 */
@Component
public class MinioReclaimService {

    private static final Logger log = LoggerFactory.getLogger(MinioReclaimService.class);

    private final MinioClient minioClient;

    private final DocumentMapper documentMapper;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${storage.reclaim.enabled:false}")
    private boolean enabled;

    @Value("${storage.reclaim.grace-period-hours:24}")
    private long gracePeriodHours;

    /**
     * 保存定期扫描满足保护期的对象所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param minioClient 访问对象存储服务的客户端
     * @param documentMapper DocumentMapper，负责提供文档查询、切片分页、写入锁定和对象引用查询
     */
    public MinioReclaimService(MinioClient minioClient, DocumentMapper documentMapper) {
        this.minioClient = minioClient;
        this.documentMapper = documentMapper;
    }

    /**
     * 在配置允许时回收超过保护期且未被数据库持有的对象。
     * 上游为 应用的定时调度器。
     * 下游为 DocumentMapper.selectAllFilePaths、MinioReclaimPlanner.findOrphans。
     */
    @Scheduled(fixedDelayString = "${storage.reclaim.scan-interval-ms:86400000}",
               initialDelayString = "${storage.reclaim.initial-delay-ms:300000}")
    public void reclaimOrphanObjects() {
        if (!enabled) {
            log.debug("孤儿对象回收已禁用（storage.reclaim.enabled=false），跳过");
            return;
        }
        Set<String> referenced;

        try {
            referenced = new HashSet<>(documentMapper.selectAllFilePaths());
        } catch (Exception e) {
            log.error("孤儿对象回收：读取 document.file_path 失败，本轮一个对象都不删", e);
            return;
        }

        ZonedDateTime cutoff = ZonedDateTime.now(ZoneId.systemDefault()).minusHours(gracePeriodHours);
        List<String> staleKeys;

        try {
            staleKeys = listKeysOlderThan(cutoff);
        } catch (Exception e) {
            log.error("孤儿对象回收：列 bucket 失败，本轮一个对象都不删", e);
            return;
        }

        Set<String> orphans = MinioReclaimPlanner.findOrphans(staleKeys, referenced);
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
        log.info("孤儿对象回收完成: 宽限期 {}h, 过宽限期的对象 {} 个, DB 引用 {} 条, 判定孤儿 {} 个, 删除 {} 个",
                gracePeriodHours, staleKeys.size(), referenced.size(), orphans.size(), deleted);
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
}
