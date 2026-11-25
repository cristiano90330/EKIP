/* 加密和解密模型服务访问凭证。 */

package com.pony.knowledge.modelmanagement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-GCM storage, bound to the owning model. No plaintext fallback. */
@Component
public class ModelCredentialCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    /**
     * 保存加密和解密模型服务访问凭证所需的依赖和初始配置。
     * 上游为 Spring 组件装配。
     * 下游为 Base64.getDecoder。
     *
     * @param configured 本方法计算或判断使用的 configured 值
     */
    public ModelCredentialCipher(@Value("${model-management.encryption-key:${MODEL_CONFIG_ENCRYPTION_KEY:}}") String configured) {
        byte[] parsed = null;
        if (configured != null && !configured.isBlank()) {
            try { parsed = Base64.getDecoder().decode(configured.trim()); }
            catch (IllegalArgumentException ignored) { }
        }
        this.key = parsed != null && parsed.length == 32 ? parsed : null;
    }
    /**
     * 用配置密钥和随机初始化向量加密访问凭证。
     * 上游为 ModelRegistryService.credentials。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param secret 从配置或调用凭据提供的密钥内容
     * @param ownerId 所有者用户 ID
     * @param modelId 模型档案 ID
     * @return 供数据库保存的编码密文
     */
    public String encrypt(String secret, long ownerId, String modelId) {
        requireKey();
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,nonce));
            cipher.updateAAD((ownerId+":"+modelId).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return "v1."+Base64.getEncoder().encodeToString(nonce)+"."+Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) { throw ModelManagementException.unavailable("模型凭证加密失败"); }
    }
    /**
     * 解析持久化密文并恢复供模型请求使用的凭证。
     * 上游为 ModelRegistryService.executionSnapshot。
     * 下游为 ModelManagementException.unavailable。
     *
     * @param encrypted 本方法计算或判断使用的 encrypted 值
     * @param ownerId 所有者用户 ID
     * @param modelId 模型档案 ID
     * @return 可转发给模型服务的原始访问凭证
     */
    public String decrypt(String encrypted, long ownerId, String modelId) {
        if (encrypted == null || encrypted.isBlank()) return null;
        requireKey();
        try {
            String[] parts = encrypted.split("\\.");
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key,"AES"), new GCMParameterSpec(128,Base64.getDecoder().decode(parts[1])));
            cipher.updateAAD((ownerId+":"+modelId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch (Exception e) { throw ModelManagementException.unavailable("模型凭证无法解密，请检查独立加密主密钥"); }
    }
    /**
     * 在使用密码算法前确认模型凭据加密密钥已配置。
     * 上游为 ModelCredentialCipher.encrypt、ModelCredentialCipher.decrypt。
     * 下游为 ModelManagementException.unavailable。
     */
    private void requireKey() {
        if (key == null) throw ModelManagementException.unavailable("保存或读取云端凭证需要有效的 MODEL_CONFIG_ENCRYPTION_KEY（Base64 32 字节）");
    }
}
