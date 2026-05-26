package com.kimpay.payment.core.psp;

/**
 * Outcome of a PSP operation.
 *
 * @param status        normalized status
 * @param pspReference  the PSP's reference id for this charge (null on hard error)
 * @param declineReason human-safe reason when DECLINED/ERROR; null on success. Never contains PAN/secrets.
 */
public record PspResult(PspStatus status, String pspReference, String declineReason) {

    public boolean isSuccess() {
        return status == PspStatus.AUTHORIZED
                || status == PspStatus.CAPTURED
                || status == PspStatus.VOIDED
                || status == PspStatus.REFUNDED;
    }

    public static PspResult ok(PspStatus status, String pspReference) {
        return new PspResult(status, pspReference, null);
    }

    public static PspResult declined(String pspReference, String reason) {
        return new PspResult(PspStatus.DECLINED, pspReference, reason);
    }
}
