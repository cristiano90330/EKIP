/* 检查问答输入的空白归一化与 Unicode 字符长度边界。 */

package com.pony.knowledge.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQuestionValidatorTest {
    private final RagQuestionValidator validator = new RagQuestionValidator();

    /** 通过 {@code whitespace.codePoints} 执行本例并核对输出或记录。 */
    @Test
    void allPythonStripWhitespaceIsRejectedWithoutContent() {
        String whitespace = "\t\n\u000b\f\r\u001c\u001d\u001e\u001f \u0085\u00a0\u1680"
                + "\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a"
                + "\u2028\u2029\u202f\u205f\u3000";
        whitespace.codePoints().forEach(codePoint -> assertFalse(
                validator.isValid(new String(Character.toChars(codePoint)), null),
                "Python-strip whitespace U+" + Integer.toHexString(codePoint)));
        assertFalse(validator.isValid(whitespace, null));
        assertTrue(validator.isValid(whitespace + "😀".repeat(4000) + whitespace, null));
    }

    /** 通过 {@code validator.isValid} 执行本例并核对输出或记录。 */
    @Test
    void significantContentAndSupplementaryCodePointsUsePythonLengthSemantics() {
        assertFalse(validator.isValid(null, null));
        assertFalse(validator.isValid("", null));
        assertTrue(validator.isValid("😀".repeat(4000), null));
        assertFalse(validator.isValid("😀".repeat(4001), null));
        assertTrue(validator.isValid("q".repeat(4000), null));
        assertFalse(validator.isValid("q".repeat(4001), null));
        assertTrue(validator.isValid("\u200b", null), "zero width space is not stripped by Python");
        assertTrue(validator.isValid("\ufeff", null), "BOM is not stripped by Python");
        assertFalse(validator.isValid("q".repeat(2000) + " " + "q".repeat(2000), null),
                "internal whitespace is significant");
    }
}
