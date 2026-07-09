package com.sandbox.orders;

import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publie les événements de commande sur le topic "orders".
 * Arête attendue : project:fakeorders -PUBLISHES-> topic:orders.
 */
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public OrderEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void orderCreated(String orderPayload) {
        kafkaTemplate.send("orders", orderPayload);
    }
}
