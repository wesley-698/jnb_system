package com.psbc.coin.user.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实名资格（t_qualification）。
 */
@Data
@Entity
@Table(name = "t_qualification")
public class Qualification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "id_card", nullable = false)
    private String idCard;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "phone")
    private String phone;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @PrePersist
    void prePersist() {
        if (createTime == null) {
            createTime = LocalDateTime.now();
        }
        if (status == null) {
            status = 1;
        }
    }
}
