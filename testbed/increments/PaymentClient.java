package com.sandbox.orders;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Étape B du scénario (setup-gitlab.sh increment) : encaissement via fake-billing.
 * Nouvelle arête attendue après webhook/nightly :
 * project:fakeorders -CALLS_API-> project:fakebilling.
 */
@FeignClient(name = "fake-billing")
public interface PaymentClient {

    @PostMapping("/api/payments")
    String charge(String orderRef);
}
