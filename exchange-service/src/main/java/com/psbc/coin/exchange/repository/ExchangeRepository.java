package com.psbc.coin.exchange.repository;

import com.psbc.coin.exchange.entity.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeRepository extends JpaRepository<Exchange, Long> {

    boolean existsByOrderNo(String orderNo);
}
