package com.kimpay.payment.security;

import com.kimpay.payment.exception.ResourceAccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationGuard {

    /** @return the authenticated merchant's id, or throws if none / wrong principal type. */
    public Long currentMerchantId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof MerchantPrincipal principal) {
            return principal.merchantId();
        }
        throw new ResourceAccessDeniedException();
    }

    /** Enforce that the resource belongs to the authenticated merchant; 404-style on mismatch. */
    public void requireOwnership(Long resourceMerchantId) {
        if (resourceMerchantId == null || !resourceMerchantId.equals(currentMerchantId())) {
            throw new ResourceAccessDeniedException();
        }
    }
}
