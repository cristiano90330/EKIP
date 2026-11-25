/* 统一解析上传入口与后台解析使用的文件扩展名。 */

package com.pony.knowledge.utils;

import java.util.Locale;

/** 上传校验、对象键生成和解析任务派发共用的文件扩展名解析规则。 */
public final class FileExtensionUtil {

    /** 初始化 FileExtensionUtil，供本文件定义的构建入口使用。 */
    private FileExtensionUtil() {
    }

    /**
     * 从文件名最后一个扩展名分隔符取得规范化后缀。
     * 上游为 DocumentProcessServiceImpl.processAttempt、DocumentServiceImpl.upload。
     *
     * @param fileName 原文件名
     * @return 规范化后缀，没有扩展名时返回空字符串
     */
    public static String of(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.substring(Math.max(
                fileName.lastIndexOf('/'),
                fileName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');

        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 拒绝包含空格、斜杠等非字母数字字符的扩展名。
        return ext.matches("[a-z0-9]{1,32}") ? ext : "";
    }
}
