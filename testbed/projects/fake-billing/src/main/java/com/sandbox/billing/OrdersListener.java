package com.sandbox.billing;

import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consomme les événements de commande pour déclencher la facturation.
 * Arête attendue : project:fakebilling -CONSUMES-> topic:orders.
 */
public class OrdersListener {

    @KafkaListener(topics = "orders")
    public void onOrderEvent(String payload) {
        // facturation déclenchée à la réception (factice)
    }
}
