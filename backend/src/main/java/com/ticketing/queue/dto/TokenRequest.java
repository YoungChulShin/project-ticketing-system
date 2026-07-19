package com.ticketing.queue.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 하나만 담는 요청 본문. 누락/공백이면 400 — 대기열 상태와 무관한 형식 오류.
 */
public record TokenRequest(@NotBlank String token) {
}
