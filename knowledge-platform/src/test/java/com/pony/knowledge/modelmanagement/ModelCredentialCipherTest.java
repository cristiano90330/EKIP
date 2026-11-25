/* 验证模型凭证加解密、密文边界与错误配置。 */

package com.pony.knowledge.modelmanagement;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ModelCredentialCipherTest {
    /**
     * 构造 {@code ModelCredentialCipher} 供测试使用，使用 {@code Base64.getEncoder}。
     * 由 {@code ciphertextUsesUniqueNonceAndIsBoundToOwnerAndModel} 调用。
     * 返回 {@code new ModelCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]))}。
     */
    private static ModelCredentialCipher configured() {
        return new ModelCredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    }

    /** 断言抛出 {@code ModelManagementException}。调用路径经过 {@code Base64.getEncoder}。 */
    @Test void missingOrInvalidMasterKeyFailsClosed() {
        for (String value : new String[]{"", "bad-base64", Base64.getEncoder().encodeToString(new byte[16])}) {
            ModelCredentialCipher cipher = new ModelCredentialCipher(value);
            assertNull(cipher.decrypt(null,1,"model"));
            ModelManagementException error = assertThrows(ModelManagementException.class,
                    () -> cipher.encrypt("credential-do-not-echo",1,"model"));
            assertEquals(503,error.getStatus());
            assertFalse(error.getMessage().contains("credential-do-not-echo"));
        }
    }

    /** 核对 {@code first.contains("sample-secret")} 为假。调用路径经过 {@code cipher.encrypt}。 */
    @Test void ciphertextUsesUniqueNonceAndIsBoundToOwnerAndModel() {
        ModelCredentialCipher cipher = configured();
        String first=cipher.encrypt("sample-secret",7,"m1"), second=cipher.encrypt("sample-secret",7,"m1");
        assertNotEquals(first,second);
        assertFalse(first.contains("sample-secret"));
        assertEquals("sample-secret",cipher.decrypt(first,7,"m1"));
        assertThrows(ModelManagementException.class,() -> cipher.decrypt(first,8,"m1"));
        assertThrows(ModelManagementException.class,() -> cipher.decrypt(first,7,"m2"));
        assertThrows(ModelManagementException.class,() -> cipher.decrypt(first.substring(0,first.length()-5)+"AAAAA",7,"m1"));
    }
}
