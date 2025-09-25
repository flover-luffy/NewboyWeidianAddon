package net.luffy.sbwa.model;

import cn.hutool.json.JSONObject;
import net.luffy.model.WeidianCookie;
import net.luffy.sbwa.handler.WeidianHandler;
import net.luffy.sbwa.handler.SalesEstimator;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

public class PKOpponent {
    private static final Logger log = LoggerFactory.getLogger(PKOpponent.class);
    public final String name;
    public final long feeAmount;
    public final boolean hasCookie;
    public String group;

    public PKOpponent(String name, long feeAmount, boolean hasCookie) {
        this.name = name;
        this.feeAmount = feeAmount;
        this.hasCookie = hasCookie;
    }

    public PKOpponent setGroup(String group) {
        this.group = group;
        return this;
    }

    public String getGroup() {
        return group == null ? "未分组" : group;
    }

    public static PKOpponent construct(JSONObject opponent) {
        if (opponent == null) {
            throw new IllegalArgumentException("Opponent JSON cannot be null");
        }

        try {
            String name = opponent.getStr("name", "");
            long amount;
            boolean isAccurate;
            
            if (StringUtils.isNotBlank(opponent.getStr("cookie"))) {
                amount = calculateTotalFee(opponent);
                isAccurate = true;
            } else if (opponent.containsKey("stock")) {
                long stock = opponent.getLong("stock", 0L);
                // 尝试使用新的销量估算功能
                amount = getEnhancedEstimation(opponent, stock);
                isAccurate = false;
            } else {
                amount = 1L;
                isAccurate = false;
            }
            
            // 确保金额不为负数
            amount = Math.max(0L, amount);
            
            PKOpponent result = new PKOpponent(name, amount, isAccurate);
            return opponent.containsKey("pk_group") ? 
                   result.setGroup(opponent.getStr("pk_group")) : result;
        } catch (Exception e) {
            log.error("Failed to construct PKOpponent", e);
            throw new RuntimeException("Opponent data format error", e);
        }
    }

    private static long calculateTotalFee(JSONObject opponent) {
        long fee = 0;
        try {
            String cookie = opponent.getStr("cookie");
            if (StringUtils.isBlank(cookie)) {
                return 0;
            }
            
            List<Long> itemIds = opponent.getBeanList("item_id", Long.class);
            if (itemIds == null || itemIds.isEmpty()) {
                return 0;
            }
            
            WeidianCookie wCookie = WeidianCookie.construct(cookie);
            for (Long itemId : itemIds) {
                if (itemId != null) {
                    long itemFee = net.luffy.sbwa.handler.WeidianHandler.INSTANCE.getTotalFee(wCookie, itemId);
                    
                    // 检查累加是否会导致溢出
                    if (itemFee > 0 && fee > Long.MAX_VALUE - itemFee) {
                        log.warn("PK对手金额累加溢出，当前总额={}, 新增金额={}, 商品ID={}", fee, itemFee, itemId);
                        return Long.MAX_VALUE;
                    }
                    
                    fee += itemFee;
                }
            }
        } catch (Exception e) {
            log.error("Fee calculation error", e);
        }
        
        // 确保返回值不为负数
        return Math.max(0L, fee);
    }

    private static long calculateTotalStock(JSONObject opponent) {
        // 实现库存计算逻辑
        try {
            return opponent.getLong("stock", 0L);
        } catch (Exception e) {
            log.error("Stock calculation error", e);
            return 0L;
        }
    }
    
    /**
     * 获取增强的销量估算
     * @param opponent 对手数据
     * @param baseStock 基础库存值
     * @return 增强估算的金额
     */
    private static long getEnhancedEstimation(JSONObject opponent, long baseStock) {
        try {
            // 获取商品ID列表
            List<Long> itemIds = opponent.getBeanList("item_id", Long.class);
            
            if (itemIds != null && !itemIds.isEmpty() && WeidianHandler.INSTANCE != null) {
                // 使用新的销量估算功能（无需Cookie）
                long timeWindow = 60 * 60 * 1000; // 1小时时间窗口
                long totalEstimatedSales = 0L;
                
                try {
                    for (Long itemId : itemIds) {
                        if (itemId != null) {
                            // 使用无Cookie的销量估算方法
                            SalesEstimator.EstimationResult result = WeidianHandler.INSTANCE.getEstimatedSales(itemId, timeWindow);
                            
                            if (result != null && result.estimatedSales > 0) {
                                // 检查累加是否会导致溢出
                                if (result.estimatedSales > 0 && totalEstimatedSales > Long.MAX_VALUE - result.estimatedSales) {
                                    log.warn("增强估算金额累加溢出，当前总额={}, 新增金额={}, 商品ID={}", 
                                            totalEstimatedSales, result.estimatedSales, itemId);
                                    return Long.MAX_VALUE;
                                }
                                
                                totalEstimatedSales += result.estimatedSales;
                                log.debug("商品{}估算销量: {}, 累计: {}", itemId, result.estimatedSales, totalEstimatedSales);
                            }
                        }
                    }
                    
                    if (totalEstimatedSales > 0) {
                        log.info("使用增强估算成功: 总估算销量: {}", totalEstimatedSales);
                        return totalEstimatedSales;
                    }
                } catch (Exception e) {
                    log.debug("Enhanced estimation failed, using base stock", e);
                }
            }
            
            // 回退到基础库存值
            log.debug("回退到基础库存值: {}", baseStock);
            return baseStock;
            
        } catch (Exception e) {
            log.error("Enhanced estimation error", e);
            return baseStock;
        }
    }
    
    /**
     * 获取数据准确性描述
     * @return 准确性描述字符串
     */
    public String getAccuracyIndicator() {
        if (hasCookie) {
            return "[精确]";
        } else {
            // 检查是否使用了增强估算
            return "[估算]";
        }
    }
}
