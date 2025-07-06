package net.lawaxi.sbwa.model;

import cn.hutool.json.JSONObject;
import net.lawaxi.model.WeidianCookie;
import net.lawaxi.sbwa.handler.WeidianHandler;
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
                amount = Math.max(0, opponent.getLong("stock") - calculateTotalStock(opponent));
                isAccurate = false;
            } else {
                amount = 1L;
                isAccurate = false;
            }
            
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
                    fee += WeidianHandler.INSTANCE.getTotalFee(wCookie, itemId);
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
}
