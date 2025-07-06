package net.lawaxi.sbwa.model;

import cn.hutool.json.JSONObject;
import net.lawaxi.model.WeidianCookie;
import net.lawaxi.sbwa.handler.WeidianHandler;
import java.util.Objects;

/**
 * 表示PK对手的实体类
 */
public class PKOpponent {
    // 常量定义
    private static final String DEFAULT_GROUP = "未分组";
    
    // 成员变量
    private final String name;        // 对手名称
    private final long feeAmount;     // 费用金额
    private final boolean hasCookie;  // 是否有cookie
    private String group;             // 所属分组

    /**
     * 构造函数
     * @param name 对手名称
     * @param feeAmount 费用金额
     * @param hasCookie 是否有cookie
     */
    public PKOpponent(String name, long feeAmount, boolean hasCookie) {
        this.name = Objects.requireNonNull(name, "Name cannot be null");
        this.feeAmount = feeAmount;
        this.hasCookie = hasCookie;
    }

    /**
     * 设置分组
     * @param group 分组名称
     * @return 当前对象（支持链式调用）
     */
    public PKOpponent setGroup(String group) {
        this.group = group;
        return this;
    }

    /**
     * 获取分组名称
     * @return 分组名称，如果未设置则返回默认值
     */
    public String getGroup() {
        return group != null ? group : DEFAULT_GROUP;
    }

    /**
     * 从JSON对象构造PKOpponent实例
     * @param opponent 包含对手信息的JSON对象
     * @return PKOpponent实例
     */
    public static PKOpponent construct(JSONObject opponent) {
        String name = opponent.getStr("name", "");
        long feeAmount = calculateFeeAmount(opponent);
        boolean hasCookie = opponent.containsKey("cookie");
        
        PKOpponent result = new PKOpponent(name, feeAmount, hasCookie);
        
        // 设置分组（如果存在）
        if (opponent.containsKey("pk_group")) {
            result.setGroup(opponent.getStr("pk_group"));
        }
        
        return result;
    }

    /**
     * 计算费用金额
     * @param opponent JSON对象
     * @return 计算出的费用金额
     */
    private static long calculateFeeAmount(JSONObject opponent) {
        // 有cookie的情况
        if (opponent.containsKey("cookie")) {
            return calculateFeeWithCookie(opponent);
        } 
        // 有stock的情况
        else if (opponent.containsKey("stock")) {
            return calculateFeeWithoutCookie(opponent);
        }
        // 默认情况
        return 1L;
    }

    /**
     * 使用cookie计算费用
     * @param opponent JSON对象
     * @return 计算出的费用
     */
    private static long calculateFeeWithCookie(JSONObject opponent) {
        long totalFee = 0;
        WeidianCookie cookie = WeidianCookie.construct(opponent.getStr("cookie"));
        
        for (Long itemId : opponent.getBeanList("item_id", Long.class)) {
            totalFee += WeidianHandler.INSTANCE.getTotalFee(cookie, itemId);
        }
        return totalFee;
    }

    /**
     * 不使用cookie计算费用（基于库存）
     * @param opponent JSON对象
     * @return 计算出的费用
     */
    private static long calculateFeeWithoutCookie(JSONObject opponent) {
        long stockSum = opponent.getBeanList("item_id", Long.class)
                              .stream()
                              .mapToLong(WeidianHandler.INSTANCE::getTotalStock)
                              .sum();
        return opponent.getLong("stock") - stockSum;
    }
}
