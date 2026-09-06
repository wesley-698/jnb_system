package com.psbc.coin.product.repository;

import com.psbc.coin.product.entity.BranchStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BranchStockRepository extends JpaRepository<BranchStock, Long> {

    List<BranchStock> findByProductId(Long productId);
}
