package com.sandbox.orders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API REST des commandes — appelée par fake-billing via Feign (OrdersClient). */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @GetMapping("/{id}")
    public String getOrder(@PathVariable Long id) {
        return "order-" + id;
    }
}
