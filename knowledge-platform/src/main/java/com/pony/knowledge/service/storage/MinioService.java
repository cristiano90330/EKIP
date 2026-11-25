/* 上传对象并保存路径，按路径删除原文件。 */

package com.pony.knowledge.service.storage;

import com.pony.knowledge.config.MinioConfig;
import com.pony.knowledge.utils.FileExtensionUtil;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** 原始文档对象存储适配器；上传使用随机对象键，数据库保存该键而不是本地路径。 */
@Service
public class MinioService {

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    /**
     * 保存上传对象并保存路径所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     *
     * @param minioClient 访问对象存储服务的客户端
     */
    public MinioService(MinioClient minioClient){ this.minioClient = minioClient;}

    /**
     * 将 multipart 数据写入配置桶中，返回实际对象存储路径。
     * 上游为 DocumentServiceImpl.upload。
     * 下游为 MinioService.objectNameOf。
     *
     * @param file 上传的原文件
     * @return 已保存原文件在配置桶内的对象键
     */
    public String upload(MultipartFile file) throws Exception{

        String objectName = objectNameOf(file.getOriginalFilename());

        minioClient.putObject(
                PutObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .stream(file.getInputStream(), file.getSize(), -1)
                        .contentType(file.getContentType())
                        .build()
        );

        return objectName;
    }

    /**
     * 为新上传文件生成包含安全路径片段的唯一对象名。
     * 上游为 MinioService.upload。
     * 下游为 FileExtensionUtil.of。
     *
     * @param originalFilename 本方法计算或判断使用的 originalFilename 值
     * @return 用于本次上传的对象键
     */
    static String objectNameOf(String originalFilename) {
        String ext = FileExtensionUtil.of(originalFilename);
        return UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
    }

    /**
     * 按对象名从配置的 MinIO 桶中删除原文件。
     * 上游为 DocumentServiceImpl.deleteOrphanObject、DocumentServiceImpl.delete。
     *
     * @param objectName 原文件在桶内的对象键
     */
    public void delete(String objectName) throws Exception{
        minioClient.removeObject(
                RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectName)
                        .build());
    };

}
