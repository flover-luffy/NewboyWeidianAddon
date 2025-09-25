package net.luffy.sbwa.model;

import cn.hutool.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;

public class PKGroup {
    public final String name;
    public final String title;
    public final BigDecimal coefficient;
    public final boolean coefficientEquals1;
    public long total;
    public ArrayList<PKOpponent> opponents = new ArrayList<>();

    public PKGroup(String name, String title, BigDecimal coefficient, long total) {
        this.name = name;
        this.title = title;
        this.coefficient = coefficient;
        this.total = total;
        this.coefficientEquals1 = this.coefficient.compareTo(new BigDecimal(1)) == 0;
    }

    public static PKGroup construct(String name, JSONObject groups) {
        if (groups == null || !groups.containsKey(name)) {
            return new PKGroup(name, name, new BigDecimal(1), 0L);
        } else {
            return new PKGroup(
                    name,
                    groups.getJSONObject(name).getStr("title", name),
                    groups.getJSONObject(name).getBigDecimal("coefficient", new BigDecimal(1)),
                    0L
            );
        }
    }

    public void addOpponent(PKOpponent opponent) {
        this.opponents.add(opponent);
        appendPrice(opponent.feeAmount);
    }

    public String getMessage() {
        String a = "\n【" + title + "】 总额: ";
        this.opponents.sort((a1, a2) -> (a2.feeAmount - a1.feeAmount > 0 ? 1 : -1));
        if (this.coefficientEquals1) {
            a += "" + (total / 100.0);
            for (PKOpponent opponent : this.opponents) {
                a += "\n" + opponent.name + ": " + (opponent.feeAmount / 100.0);
            }
        } else {

            a += "" + (getTotalInCoefficient() / 100.0) + " (" + (this.total / 100.0) + "," + this.coefficient.toPlainString() + ")";
            for (PKOpponent opponent : this.opponents) {
                a += "\n" + opponent.name + ": " + (getPriceInCoefficient(opponent.feeAmount) / 100.0) + " (" + (opponent.feeAmount / 100.0) + ")";
            }
        }
        return a;
    }

    private void appendPrice(long price) {
        // 检查累加是否会导致溢出
        if (price > 0 && this.total > Long.MAX_VALUE - price) {
            System.err.println("警告: PK总金额累加溢出，当前总额=" + this.total + ", 新增金额=" + price);
            this.total = Long.MAX_VALUE;
        } else if (price < 0 && this.total < Long.MIN_VALUE - price) {
            System.err.println("警告: PK总金额累加下溢，当前总额=" + this.total + ", 新增金额=" + price);
            this.total = 0L; // 确保总额不为负数
        } else {
            this.total += price;
            // 确保总额不为负数
            if (this.total < 0) {
                System.err.println("警告: PK总金额为负数，重置为0，原值=" + (this.total - price));
                this.total = 0L;
            }
        }
    }

    public long getTotalInCoefficient() {
        return getPriceInCoefficient(this.total);
    }

    public long getPriceInCoefficient(long price) {
        try {
            BigDecimal result = this.coefficient.multiply(new BigDecimal(price));
            
            // 检查结果是否超出long的范围
            if (result.compareTo(new BigDecimal(Long.MAX_VALUE)) > 0) {
                // 如果超出范围，返回Long.MAX_VALUE并记录警告
                System.err.println("警告: PK金额计算溢出，系数=" + this.coefficient + ", 原始金额=" + price);
                return Long.MAX_VALUE;
            }
            
            if (result.compareTo(new BigDecimal(Long.MIN_VALUE)) < 0) {
                // 如果小于最小值，返回0
                System.err.println("警告: PK金额计算结果为负数，系数=" + this.coefficient + ", 原始金额=" + price);
                return 0L;
            }
            
            return result.longValue();
        } catch (Exception e) {
            System.err.println("错误: PK金额计算异常，系数=" + this.coefficient + ", 原始金额=" + price + ", 错误=" + e.getMessage());
            return price; // 发生异常时返回原始金额
        }
    }
}
