package com.psbc.coin.order.repository;

import com.psbc.coin.order.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    boolean existsByProductIdAndIdCard(Long productId, String idCard);

    List<Reservation> findByIdCardOrderByCreateTimeDesc(String idCard);
}
