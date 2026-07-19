package com.ticketing.reservation;

import com.ticketing.queue.QueueService;
import com.ticketing.queue.dto.CompleteResult;
import com.ticketing.queue.dto.TokenRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예매 API. 실제 예매 도메인(좌석, 결제)은 범위 밖 —
 * 대기열 lease 검증(complete)과 예매 번호 발급이 전부다.
 */
@RestController
public class ReservationController {

    private final QueueService queueService;

    public ReservationController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/api/reservations")
    public CompleteResult reserve(@Valid @RequestBody TokenRequest request) {
        return queueService.complete(request.token());
    }
}
