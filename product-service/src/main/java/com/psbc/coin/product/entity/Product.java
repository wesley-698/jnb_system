package com.psbc.coin.product.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 产品/活动（t_product）。
 */
@Data
@Entity
@Table(name = "t_product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "total_stock", nullable = false)
    private Long totalStock;

    @Column(name = "limit_per_user")
    private Integer limitPerUser;

    @Column(name = "submit_start")
    private LocalDateTime submitStart;

    @Column(name = "submit_end")
    private LocalDateTime submitEnd;

    @Column(name = "status")
    private Integer status;
}
