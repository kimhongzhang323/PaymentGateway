package com.kimpay.payment.controller;

import com.kimpay.payment.core.psp.PspUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerPspTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void pspUnavailableMapsTo503WithNet003AndRetryAfter() {
        ResponseEntity<ErrorResponse> resp =
                handler.handlePspUnavailable(new PspUnavailableException(30));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody().code()).isEqualTo("NET-003");
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("30");
    }
}
