package com.sandbox.billing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Modèle de lecture sur la table "orders" de fake-orders.
 * Arête attendue : SHARES_TABLE entre fakebilling et fakeorders (scenario.md §1).
 */
@Entity
@Table(name = "orders")
public class OrderReadModel {

    @Id
    private Long id;
    private String status;

    public Long getId() { return id; }
    public String getStatus() { return status; }
}
