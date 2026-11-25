/* 比较注册约束与数据库列长度，并检查超长字段的错误位置。 */

package com.pony.knowledge.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterDtoValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    /** 为本类验证用例初始化 Bean Validation 工厂和校验器。 */
    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    /** 结束本类验证用例后关闭校验工厂。 */
    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    /** 通过 {@code registration} 执行本例并核对输出或记录。 */
    @Test
    void databaseColumnLengthsAreAccepted() {
        RegisterDTO dto = registration("u".repeat(50), "a".repeat(50) + "@" + "b".repeat(45) + ".com");

        assertThat(violatedFields(dto)).isEmpty();
    }

    /** 通过 {@code registration} 执行本例并核对输出或记录。 */
    @Test
    void valuesLongerThanDatabaseColumnsAreRejected() {
        RegisterDTO dto = registration("u".repeat(51), "a".repeat(50) + "@" + "b".repeat(46) + ".com");

        assertThat(violatedFields(dto)).containsExactlyInAnyOrder("username", "email");
    }

    /**
     * 以用户名和邮箱构造注册请求夹具。
     * 由 {@code databaseColumnLengthsAreAccepted}、{@code valuesLongerThanDatabaseColumnsAreRejected} 调用。
     * @param username 测试用户名
     * @param email 本用例传入的{@code email}
     * 返回 {@code dto}。
     */
    private static RegisterDTO registration(String username, String email) {
        RegisterDTO dto = new RegisterDTO();
        dto.setUsername(username);
        dto.setPassword("secret1");
        dto.setEmail(email);
        return dto;
    }

    /**
     * 返回注册约束校验报告里的字段集合。
     * 由 {@code databaseColumnLengthsAreAccepted}、{@code valuesLongerThanDatabaseColumnsAreRejected} 调用。
     * @param dto 本用例传入的{@code dto}
     */
    private static Set<String> violatedFields(RegisterDTO dto) {
        return validator.validate(dto).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
