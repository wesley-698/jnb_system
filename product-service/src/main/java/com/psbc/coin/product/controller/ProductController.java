package com.psbc.coin.product.controller;

import com.psbc.coin.common.constant.CommonConstants;
import com.psbc.coin.common.result.Result;
import com.psbc.coin.product.entity.Branch;
import com.psbc.coin.product.entity.BranchStock;
import com.psbc.coin.product.entity.Product;
import com.psbc.coin.product.repository.BranchRepository;
import com.psbc.coin.product.repository.BranchStockRepository;
import com.psbc.coin.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 产品/网点/额度接口。
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;
    private final BranchStockRepository branchStockRepository;
    private final StringRedisTemplate redis;

    @Value("${coin.stock-shards:10}")
    private int stockShards;

    /** 产品列表 */
    @GetMapping("/list")
    public Result<List<Product>> list() {
        return Result.success(productRepository.findAll());
    }

    /** 某产品的网点额度列表 */
    @GetMapping("/{productId}/branches")
    public Result<List<BranchStock>> branches(@PathVariable Long productId) {
        return Result.success(branchStockRepository.findByProductId(productId));
    }

    /** 网点列表 */
    @GetMapping("/branch/list")
    public Result<List<Branch>> branchList() {
        return Result.success(branchRepository.findAll());
    }

    /**
     * 额度预热：把网点额度拆成 N 个分片写入 Redis，并登记该产品的可预约网点清单。
     *
     * <p>网点清单（{@code resv:branches:{productId}}）供预约服务在「先到先得」提交前
     * 展示各网点余量，避免峰值期间回查数据库。
     */
    @PostMapping("/{productId}/init-stock")
    public Result<Void> initStock(@PathVariable Long productId) {
        List<BranchStock> stocks = branchStockRepository.findByProductId(productId);
        String branchesKey = CommonConstants.KEY_BRANCHES + ":" + productId;
        redis.delete(branchesKey);
        for (BranchStock s : stocks) {
            long perShard = s.getRemain() / stockShards;
            long remainder = s.getRemain() % stockShards;
            for (int i = 0; i < stockShards; i++) {
                long shardStock = perShard + (i == stockShards - 1 ? remainder : 0);
                String key = CommonConstants.KEY_STOCK + ":" + productId + ":" + s.getBranchId() + ":" + i;
                redis.opsForValue().set(key, String.valueOf(shardStock));
            }
            redis.opsForSet().add(branchesKey, String.valueOf(s.getBranchId()));
        }
        return Result.success();
    }
}
