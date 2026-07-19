package com.ticketing.queue;

import com.ticketing.queue.dto.ClaimResult;
import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.dto.MeResult;
import com.ticketing.queue.dto.TokenRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @PostMapping("/join")
    public JoinResult join() {
        return queueService.join();
    }

    @GetMapping("/me")
    public MeResult me(@RequestParam String token) {
        return queueService.me(token);
    }

    @PostMapping("/claim")
    public ClaimResult claim(@Valid @RequestBody TokenRequest request) {
        return queueService.claim(request.token());
    }
}
