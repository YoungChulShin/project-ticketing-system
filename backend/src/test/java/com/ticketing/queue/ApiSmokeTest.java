package com.ticketing.queue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.ticketing.queue.dto.JoinResult;
import com.ticketing.support.RedisTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * API 계층 스모크 — 컨트롤러 매핑, JSON 직렬화, 검증(400)이 붙었는지 확인.
 * 대기열 로직 자체는 서비스 테스트가 이미 커버한다.
 */
@AutoConfigureMockMvc
class ApiSmokeTest extends RedisTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    QueueService queueService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void join부터_예매_완료까지_전체_흐름() throws Exception {
        // 줄서기
        String joinBody = mockMvc.perform(post("/api/queue/join"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.ahead").value(0))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readValue(joinBody, JoinResult.class).token();

        // 대기 중 상태 조회
        mockMvc.perform(get("/api/queue/me").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));

        // 승격 (스케줄러 역할을 테스트가 대신)
        queueService.promote();

        mockMvc.perform(get("/api/queue/me").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ELIGIBLE"));

        // 입장 확정
        mockMvc.perform(post("/api/queue/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.leaseDeadline").isNumber());

        // 예매
        mockMvc.perform(post("/api/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reservationId").value("r-20260718-1"));
    }

    @Test
    void 모르는_토큰의_me는_NOT_FOUND_상태를_200으로_반환한다() throws Exception {
        mockMvc.perform(get("/api/queue/me").param("token", "ghost"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
    }

    @Test
    void 토큰_없는_claim은_400이다() throws Exception {
        mockMvc.perform(post("/api/queue/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void admin은_대기열_전체_상태를_반환한다() throws Exception {
        queueService.join();
        queueService.join();
        queueService.promote();

        mockMvc.perform(get("/api/admin/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible.length()").value(2))
                .andExpect(jsonPath("$.waiting.length()").value(0))
                .andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.dequeued").value(2));
    }
}
