package com.psbc.coin.order.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 网点库存（t_branch_stock）。
 */
@Data
@Entity
@Table(name = "t_branch_stock")
public class BranchStock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "total", nullable = false)
    private Long total;

    @Column(name = "remain", nullable = false)
    private Long remain;

    @Column(name = "version", nullable = false)
    private Long version;
}
