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
     * 库存预热：把网点额度拆成 N 个分片写入 Redis。
     * 每个分片分配 total/N，余数放入最后一个分片。
     */
    @PostMapping("/{productId}/init-stock")
    public Result<Void> initStock(@PathVariable Long productId) {
        List<BranchStock> stocks = branchStockRepository.findByProductId(productId);
        for (BranchStock s : stocks) {
            long perShard = s.getRemain() / stockShards;
            long remainder = s.getRemain() % stockShards;
            for (int i = 0; i < stockShards; i++) {
                long shardStock = perShard + (i == stockShards - 1 ? remainder : 0);
                String key = CommonConstants.KEY_STOCK + ":" + productId + ":" + s.getBranchId() + ":" + i;
                redis.opsForValue().set(key, String.valueOf(shardStock));
            }
        }
        return Result.success();
    }
}
