package com.psbc.coin.order.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预约记录（t_reservation）。
 */
@Data
@Entity
@Table(name = "t_reservation")
public class Reservation {

    @Id
    private Long id;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "id_card", nullable = false)
    private String idCard;

    @Column(name = "sequence_no")
    private Long sequenceNo;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    void prePersist() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (updateTime == null) {
            updateTime = LocalDateTime.now();
        }
    }
}
