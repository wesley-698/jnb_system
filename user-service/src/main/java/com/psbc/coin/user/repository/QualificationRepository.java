package com.psbc.coin.user.repository;

import com.psbc.coin.user.entity.Qualification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QualificationRepository extends JpaRepository<Qualification, Long> {

    boolean existsByIdCard(String idCard);

    Optional<Qualification> findByIdCard(String idCard);
}
