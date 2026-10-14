package cn.panda.pandagpt.awareness;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KnowledgeBaseTest {
    private static final String DOC = """
            # 领地
            使用金铲圈地，每位玩家最多 3 块领地。

            # 经济
            商店位于主城东侧，钻石回收价 50 金币。

            # Teleport
            Use /spawn to return to the main city.
            """;

    @Test
    void searchFindsChineseAndEnglishSectionsByRelevance() {
        var kb = new KnowledgeBase(DOC);
        assertEquals(java.util.List.of("领地", "经济", "Teleport"), kb.outline());
        assertEquals("经济", kb.search("钻石卖多少钱", 2, 1000).getFirst().heading());
        assertEquals("领地", kb.search("怎么圈地", 2, 1000).getFirst().heading());
        assertEquals(
                "Teleport",
                kb.search("how do I return to spawn", 2, 1000).getFirst().heading());
        assertTrue(kb.search("火箭", 2, 1000).isEmpty());
    }
}
