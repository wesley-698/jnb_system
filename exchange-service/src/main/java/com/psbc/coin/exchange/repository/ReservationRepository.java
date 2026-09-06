package com.psbc.coin.exchange.repository;

import com.psbc.coin.exchange.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    Optional<Reservation> findByOrderNo(String orderNo);
}
