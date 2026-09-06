package com.psbc.coin.product.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 网点（t_branch）。
 */
@Data
@Entity
@Table(name = "t_branch")
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "branch_code", nullable = false)
    private String branchCode;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(name = "address")
    private String address;

    @Column(name = "status")
    private Integer status;
}
