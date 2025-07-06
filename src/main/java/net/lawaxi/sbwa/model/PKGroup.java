package net.lawaxi.sbwa.model;

import cn.hutool.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * PK对战组模型类，用于管理一组对战对手及其相关数据
 */
public class PKGroup {
    // 常量定义
    private static final BigDecimal DEFAULT_COEFFICIENT = new BigDecimal(1);
    private static final BigDecimal HUNDRED = new BigDecimal(100);
    
    // 成员变量
    public final String name;          // 组名
    public final String title;         // 显示标题
    public final BigDecimal coefficient; // 系数
    public final boolean coefficientEquals1; // 系数是否为1的标记
    public long total;                  // 总金额（单位：分）
    public ArrayList<PKOpponent> opponents = new ArrayList<>(); // 对手列表

    /**
     * 构造函数
     * @param name 组名
     * @param title 显示标题
     * @param coefficient 系数
     * @param total 初始总金额
     */
    public PKGroup(String name, String title, BigDecimal coefficient, long total) {
        this.name = name;
        this.title = title;
        this.coefficient = coefficient;
        this.total = total;
        this.coefficientEquals1 = this.coefficient.compareTo(DEFAULT_COEFFICIENT) == 0;
    }

    /**
     * 静态工厂方法，从JSON构造PKGroup对象
     * @param name 组名
     * @param groups JSON对象
     * @return PKGroup实例
     */
    public static PKGroup construct(String name, JSONObject groups) {
        if (groups == null || !groups.containsKey(name)) {
            return new PKGroup(name, name, DEFAULT_COEFFICIENT, 0L);
        }
        
        JSONObject groupData = groups.getJSONObject(name);
        return new PKGroup(
                name,
                groupData.getStr("title", name),
                groupData.getBigDecimal("coefficient", DEFAULT_COEFFICIENT),
                0L
        );
    }

    /**
     * 添加对手并更新总金额
     * @param opponent 对手对象
     */
    public void addOpponent(PKOpponent opponent) {
        this.opponents.add(opponent);
        appendPrice(opponent.feeAmount);
    }

    /**
     * 生成对战组信息字符串
     * @return 格式化后的信息字符串
     */
    public String getMessage() {
        StringBuilder sb = new StringBuilder("\n【").append(title).append("】 总额: ");
        
        // 按金额降序排序
        opponents.sort(Comparator.comparingLong((PKOpponent o) -> o.feeAmount).reversed());
        
        if (this.coefficientEquals1) {
            sb.append(formatAmount(total));
            for (PKOpponent opponent : this.opponents) {
                sb.append("\n").append(opponent.name).append(": ").append(formatAmount(opponent.feeAmount));
            }
        } else {
            sb.append(formatAmount(getTotalInCoefficient()))
              .append(" (").append(formatAmount(total))
              .append(",").append(coefficient.toPlainString()).append(")");
              
            for (PKOpponent opponent : this.opponents) {
                sb.append("\n").append(opponent.name).append(": ")
                  .append(formatAmount(getPriceInCoefficient(opponent.feeAmount)))
                  .append(" (").append(formatAmount(opponent.feeAmount)).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * 格式化金额（分转元）
     * @param amount 金额（分）
     * @return 格式化后的字符串
     */
    private String formatAmount(long amount) {
        return String.valueOf(amount / 100.0);
    }

    /**
     * 累加金额
     * @param price 要增加的金额
     */
    private void appendPrice(long price) {
        this.total += price;
    }

    /**
     * 获取系数计算后的总金额
     * @return 计算后的金额
     */
    public long getTotalInCoefficient() {
        return getPriceInCoefficient(this.total);
    }

    /**
     * 根据系数计算金额
     * @param price 原始金额
     * @return 计算后的金额
     */
    public long getPriceInCoefficient(long price) {
        return coefficient.multiply(new BigDecimal(price)).longValue();
    }
}
