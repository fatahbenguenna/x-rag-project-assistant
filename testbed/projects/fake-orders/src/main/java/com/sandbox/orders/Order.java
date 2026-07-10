package com.sandbox.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Commande client. La table "orders" est aussi lue par fake-billing
 * (OrderReadModel) : arête SHARES_TABLE attendue entre les deux projets.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private Long id;
    private String customerRef;
    private String status;

    public Long getId() { return id; }
    public String getCustomerRef() { return customerRef; }
    public String getStatus() { return status; }
}
