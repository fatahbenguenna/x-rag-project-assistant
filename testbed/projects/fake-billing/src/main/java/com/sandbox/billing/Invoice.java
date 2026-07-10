package com.sandbox.billing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Facture émise pour une commande. */
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    private Long id;
    private Long orderId;
    private String amount;

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getAmount() { return amount; }
}
