/* 使用对象存储配置创建 MinIO 客户端。 */

package com.pony.knowledge.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通过 @Configuration 和 @Bean 将外部 SDK 的 MinioClient 纳入 Spring 容器，
 * 地址与凭证先由 Spring 配置注入，再交给客户端 Builder。MinioService 和能力探针等消费者
 * 通过依赖注入复用这个客户端；创建对象不检查桶或服务连通性，文件操作和能力探测由调用方执行。
 */
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    /**
     * 用服务端地址和访问凭证创建 MinIO 客户端 Bean。
     * 上游为 Spring 容器组件装配。
     * 下游为 MinioClient.builder。
     *
     * @return 本次计算或查询得到的 MinioClient 结果
     */
    @Bean
    public MinioClient minioClient(){
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
