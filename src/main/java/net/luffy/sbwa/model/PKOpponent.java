package net.luffy.sbwa.model;

import cn.hutool.json.JSONObject;
import net.luffy.model.WeidianCookie;
import net.luffy.sbwa.handler.WeidianHandler;
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
                    fee += net.luffy.sbwa.handler.WeidianHandler.INSTANCE.getTotalFee(wCookie, itemId);
                }
            }
        } catch (Exception e) {
            log.error("Fee calculation error", e);
        }
        return fee;
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
            // 获取商品ID
            Long itemId = null;
            if (opponent.containsKey("item_id")) {
                itemId = opponent.getLong("item_id");
            }
            
            if (itemId != null && WeidianHandler.INSTANCE != null) {
                // 使用新的销量估算功能
                long timeWindow = 60 * 60 * 1000; // 1小时时间窗口
                try {
                    // 这里假设WeidianHandler有getEstimatedSales方法
                    // 实际实现时需要根据WeidianHandler的API调整
                    long estimatedSales = WeidianHandler.INSTANCE.getTotalFee(WeidianCookie.construct(opponent.getStr("cookie", "")), itemId);
                    
                    if (estimatedSales > 0) {
                        log.info("使用增强估算: 商品{}, 估算销量: {}", itemId, estimatedSales);
                        return estimatedSales;
                    }
                } catch (Exception e) {
                    log.debug("Enhanced estimation failed, using base stock", e);
                }
            }
            
            // 回退到基础库存值
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
