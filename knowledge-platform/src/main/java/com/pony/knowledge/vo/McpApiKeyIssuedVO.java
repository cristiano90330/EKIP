/* 向密钥创建者返回首次签发时的明文密钥和显示元数据。 */

package com.pony.knowledge.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 签发响应：唯一携带明文密钥的响应体，签发后服务端不再能取回。
 * 与 {@link McpApiKeyVO} 刻意分成两个类——列表接口不可能误带明文。
 */
@Data
@Builder
public class McpApiKeyIssuedVO {

    private Long id;

    private String name;

    private String keyPrefix;

    /** 明文密钥，仅此一次。 */
    private String apiKey;
}
