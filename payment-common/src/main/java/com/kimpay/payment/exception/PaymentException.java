package com.kimpay.payment.exception;

import com.kimpay.payment.constant.ErrorCode;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * =============================================================================
 * PaymentException.java
 * -----------------------------------------------------------------------------
 * Project      : payment
 * Module       : com.kimpay.payment.exception
 * Author       : kimho
 * Created On   : 25/10/2025
 * Last Modified: 1:07 pm
 * -----------------------------------------------------------------------------
 * Description  : PaymentException - Core component or utility class.
 * <p>
 * -----------------------------------------------------------------------------
 * COPYRIGHT NOTICE
 * -----------------------------------------------------------------------------
 * © 2025 Kimpay Technologies. All Rights Reserved.
 * Unauthorized copying, modification, distribution, or disclosure of this
 * file, via any medium, is strictly prohibited. This file contains proprietary
 * and confidential information of Kimpay Technologies.
 * -----------------------------------------------------------------------------
 * SECURITY NOTICE
 * -----------------------------------------------------------------------------
 * This class may process sensitive financial or personal data. Ensure all
 * logs, outputs, and interactions comply with internal data-handling policies
 * and regulatory requirements (e.g., PCI DSS, GDPR).
 * =============================================================================
 */
@Getter
public class PaymentException extends RuntimeException {

    private final ErrorCode errorCode;

    public PaymentException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public PaymentException(ErrorCode errorCode, String details) {
        super(String.format("%s - %s", errorCode.message(), details));
        this.errorCode = errorCode;
    }

    public PaymentException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }

    @Override
    public String toString() {
        return String.format("PaymentException{code=%s, message=%s}",
                errorCode.code(), getMessage());
    }
}
