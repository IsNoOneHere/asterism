package com.asterism.knowledge;

import com.asterism.IntegrationDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class KnowledgeMatchIntegrationTest {
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        IntegrationDatabase.register(registry);
    }

    @Autowired SystemKnowledgeService knowledge;
    @Autowired KnowledgeMatchService matcher;

    @Test
    void matchesChineseAnchorsByTopKAndIgnoresCandidate() {
        var marker = UUID.randomUUID().toString().substring(0, 8);
        var order = knowledge.createManual("demo-system", candidate("订单列表 " + marker, "/orders-" + marker,
                List.of("订单列表", "待发货订单")), "admin");
        var detail = knowledge.createManual("demo-system", candidate("订单详情 " + marker, "/order-detail-" + marker,
                List.of("订单详情", "收货地址")), "admin");
        knowledge.createManual("demo-system", candidate("候选精确命中 " + marker, "/candidate-" + marker,
                List.of("订单列表")), "admin");
        knowledge.updateStatus("demo-system", order.entryId(), "approved", "admin");
        knowledge.updateStatus("demo-system", detail.entryId(), "approved", "admin");

        var result = matcher.match("demo-system", List.of("订单列表", "待发货订单"));

        assertThat(result.targets()).isNotEmpty();
        assertThat(result.targets().getFirst().entryId()).isEqualTo(order.entryId());
        assertThat(result.targets()).noneMatch(target -> target.title().contains("候选精确命中"));
    }

    @Test
    void codeIndexCandidateIsIdempotentByRoute() {
        var route = "/index-" + UUID.randomUUID();
        var request = candidate("索引页面", route, List.of("索引页面"));

        assertThat(knowledge.writeCandidates("demo-system", List.of(request), "code_index", "worker")).isEqualTo(1);
        assertThat(knowledge.writeCandidates("demo-system", List.of(request), "code_index", "worker")).isZero();
    }

    private SystemKnowledgeService.CandidateRequest candidate(String title, String route, List<String> anchors) {
        return new SystemKnowledgeService.CandidateRequest("page", title, anchors, route,
                List.of("GET /api/orders"), List.of("src/orders.tsx"), route);
    }
}
