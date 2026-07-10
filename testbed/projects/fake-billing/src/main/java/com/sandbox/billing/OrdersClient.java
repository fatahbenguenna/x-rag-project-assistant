package com.sandbox.billing;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Client Feign vers l'API de fake-orders (détail d'une commande avant facturation).
 * Arête attendue : project:fakebilling -CALLS_API-> project:fakeorders
 * (résolution du name via la table d'alias).
 */
@FeignClient(name = "fake-orders")
public interface OrdersClient {

    @GetMapping("/api/orders/{id}")
    String getOrder(@PathVariable("id") Long id);
}
