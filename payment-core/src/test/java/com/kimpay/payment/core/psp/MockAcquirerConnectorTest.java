package com.kimpay.payment.core.psp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MockAcquirerConnectorTest {

    private final MockAcquirerConnector connector = new MockAcquirerConnector();

    @Test
    void authorizeApprovesAndReturnsReference() {
        PspResult result = connector.authorize(new PspAuthorizeRequest(1L, 9L, new BigDecimal("10.00"), "USD", false));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo(PspStatus.AUTHORIZED);
        assertThat(result.pspReference()).startsWith("mock_");
    }

    @Test
    void authorizeWithCaptureReturnsCaptured() {
        PspResult result = connector.authorize(new PspAuthorizeRequest(1L, 9L, new BigDecimal("10.00"), "USD", true));
        assertThat(result.status()).isEqualTo(PspStatus.CAPTURED);
    }

    @Test
    void magicDeclineAmountIsDeclined() {
        // Convention: any amount whose minor units end in .01 is declined, for testing decline paths.
        PspResult result = connector.authorize(new PspAuthorizeRequest(1L, 9L, new BigDecimal("10.01"), "USD", false));
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.status()).isEqualTo(PspStatus.DECLINED);
        assertThat(result.declineReason()).isNotBlank();
    }

    @Test
    void captureVoidRefundEchoReferenceAndSucceed() {
        assertThat(connector.capture("mock_abc", new BigDecimal("10.00")).status()).isEqualTo(PspStatus.CAPTURED);
        assertThat(connector.voidAuthorization("mock_abc").status()).isEqualTo(PspStatus.VOIDED);
        assertThat(connector.refund("mock_abc", new BigDecimal("5.00")).status()).isEqualTo(PspStatus.REFUNDED);
    }
}
