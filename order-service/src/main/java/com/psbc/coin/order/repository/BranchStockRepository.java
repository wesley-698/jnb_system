package com.psbc.coin.order.repository;

import com.psbc.coin.order.entity.BranchStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BranchStockRepository extends JpaRepository<BranchStock, Long> {

    Optional<BranchStock> findByProductIdAndBranchId(Long productId, Long branchId);

    /**
     * 乐观扣减：仅当 remain>0 时扣减，返回受影响行数（0 表示该网点已约满）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE BranchStock s SET s.remain = s.remain - 1, s.version = s.version + 1 " +
            "WHERE s.productId = :productId AND s.branchId = :branchId AND s.remain > 0")
    int deduct(@Param("productId") Long productId, @Param("branchId") Long branchId);

    /**
     * 回补：库存 +1（补偿用）。
     */
    @Modifying
    @Transactional
    @Query("UPDATE BranchStock s SET s.remain = s.remain + 1, s.version = s.version + 1 " +
            "WHERE s.productId = :productId AND s.branchId = :branchId")
    int compensate(@Param("productId") Long productId, @Param("branchId") Long branchId);
}
