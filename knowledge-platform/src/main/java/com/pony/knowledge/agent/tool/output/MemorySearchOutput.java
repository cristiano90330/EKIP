/* 描述长期记忆工具返回的匹配条目。 */

package com.pony.knowledge.agent.tool.output;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 长期记忆检索结果。只回条目与类别，不回 userId/时间戳——模型不需要，也没有理由看到。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchOutput {

    private String query;

    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {

        private String kind;

        private String content;
    }
}
