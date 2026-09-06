package com.psbc.coin.exchange.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 兑换登记流水（t_exchange）。
 */
@Data
@Entity
@Table(name = "t_exchange")
public class Exchange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "id_card", nullable = false)
    private String idCard;

    @Column(name = "exchange_time")
    private LocalDateTime exchangeTime;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "status", nullable = false)
    private Integer status;

    @PrePersist
    void prePersist() {
        if (exchangeTime == null) {
            exchangeTime = LocalDateTime.now();
        }
        if (status == null) {
            status = 1;
        }
    }
}
