package net.lawaxi.sbwa.model;

import cn.hutool.json.JSONObject;
import net.lawaxi.model.WeidianCookie;
import net.lawaxi.sbwa.handler.WeidianHandler;

public class PKOpponent {
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
    String name = opponent.getStr("name", "");
    long amount;
    boolean isAccurate;
    
    if (!opponent.getStr("cookie", "").isEmpty()) {
        // 有cookie获取金额的方式
        amount = calculateTotalFee(opponent);
        isAccurate = true;
    } else if (opponent.containsKey("stock")) {
        // 无cookie获取金额的方式（不准确）
        amount = opponent.getLong("stock") - calculateTotalStock(opponent);
        isAccurate = false;
    } else {
        amount = 1L;
        isAccurate = false;
    }
    
    PKOpponent result = new PKOpponent(name, amount, isAccurate);
    return opponent.containsKey("pk_group") ? result.setGroup(opponent.getStr("pk_group")) : result;
}

private static long calculateTotalFee(JSONObject opponent) {
    long fee = 0;
    String cookie = opponent.getStr("cookie");
    for (Long itemId : opponent.getBeanList("item_id", Long.class)) {
        fee += WeidianHandler.INSTANCE.getTotalFee(WeidianCookie.construct(cookie), itemId);
    }
    return fee;
}

private static long calculateTotalStock(JSONObject opponent) {
    long stock = 0;
    for (Long itemId : opponent.getBeanList("item_id", Long.class)) {
        stock += WeidianHandler.INSTANCE.getTotalStock(itemId);
    }
    return stock;
}
