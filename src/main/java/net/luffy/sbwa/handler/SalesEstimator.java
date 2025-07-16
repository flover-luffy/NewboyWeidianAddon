package net.luffy.sbwa.handler;

import cn.hutool.json.JSONObject;
import net.luffy.sbwa.NewboyWeidianAddon;
import net.luffy.sbwa.config.ConfigConfig;
import net.mamoe.mirai.utils.MiraiLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 销量估算器 - 综合多种数据源估算商品销量
 */
public class SalesEstimator {
    
    private static final MiraiLogger logger = NewboyWeidianAddon.INSTANCE.getLogger();
    private final StockMonitor stockMonitor;
    
    // 数据可信度枚举
    public enum DataConfidence {
        HIGH("精确", 90),      // 有Cookie数据
        MEDIUM("较准确", 75),   // 多源估算
        LOW("估算", 60),       // 单一库存数据
        VERY_LOW("粗估", 40);  // 数据不足
        
        public final String description;
        public final int score;
        
        DataConfidence(String description, int score) {
            this.description = description;
            this.score = score;
        }
    }
    
    // 估算结果类
    public static class EstimationResult {
        public final long estimatedSales;     // 估算销量（分为单位）
        public final DataConfidence confidence; // 数据可信度
        public final String method;           // 估算方法
        public final Map<String, Object> details; // 详细信息
        
        public EstimationResult(long estimatedSales, DataConfidence confidence, String method) {
            this.estimatedSales = estimatedSales;
            this.confidence = confidence;
            this.method = method;
            this.details = new ConcurrentHashMap<>();
        }
        
        public EstimationResult addDetail(String key, Object value) {
            this.details.put(key, value);
            return this;
        }
    }
    
    public SalesEstimator(StockMonitor stockMonitor) {
        this.stockMonitor = stockMonitor;
    }
    
    /**
     * 综合估算商品销量
     * @param itemId 商品ID
     * @param timeWindowMs 时间窗口（毫秒）
     * @return 估算结果
     */
    public EstimationResult estimateSales(long itemId, long timeWindowMs) {
        // 方法1：基于库存变化的估算
        EstimationResult stockBasedResult = estimateFromStockChange(itemId, timeWindowMs);
        
        // 方法2：基于历史模式的估算
        EstimationResult patternBasedResult = estimateFromHistoricalPattern(itemId, timeWindowMs);
        
        // 方法3：基于PK数据的参考估算
        EstimationResult pkBasedResult = estimateFromPKData(itemId);
        
        // 综合多种方法的结果
        return combineEstimations(stockBasedResult, patternBasedResult, pkBasedResult);
    }
    
    /**
     * 基于库存变化估算销量
     */
    private EstimationResult estimateFromStockChange(long itemId, long timeWindowMs) {
        try {
            long stockBasedSales = stockMonitor.estimateSalesFromStockChange(itemId, timeWindowMs);
            int historySize = stockMonitor.getHistorySize(itemId);
            
            DataConfidence confidence;
            if (historySize >= 10) {
                confidence = DataConfidence.MEDIUM;
            } else if (historySize >= 3) {
                confidence = DataConfidence.LOW;
            } else {
                confidence = DataConfidence.VERY_LOW;
            }
            
            return new EstimationResult(stockBasedSales, confidence, "库存变化分析")
                    .addDetail("历史记录数", historySize)
                    .addDetail("时间窗口", timeWindowMs / (60 * 1000) + "分钟");
                    
        } catch (Exception e) {
            logger.error("基于库存变化估算销量时发生错误", e);
            return new EstimationResult(0L, DataConfidence.VERY_LOW, "库存变化分析(错误)");
        }
    }
    
    /**
     * 基于历史模式估算销量
     */
    private EstimationResult estimateFromHistoricalPattern(long itemId, long timeWindowMs) {
        try {
            // 获取最近几个时间段的销售速率
            long hourlyRate = stockMonitor.getSalesRate(itemId);
            
            if (hourlyRate > 0) {
                // 根据时间窗口推算销量
                long hoursInWindow = timeWindowMs / (60 * 60 * 1000);
                long estimatedSales = hourlyRate * Math.max(1, hoursInWindow);
                
                return new EstimationResult(estimatedSales, DataConfidence.LOW, "历史模式分析")
                        .addDetail("每小时销量", hourlyRate)
                        .addDetail("时间窗口小时数", hoursInWindow);
            }
            
        } catch (Exception e) {
            logger.error("基于历史模式估算销量时发生错误", e);
        }
        
        return new EstimationResult(0L, DataConfidence.VERY_LOW, "历史模式分析(无数据)");
    }
    
    /**
     * 基于PK数据的参考估算
     */
    private EstimationResult estimateFromPKData(long itemId) {
        try {
            // 查找相关的PK数据作为参考
            Map<String, JSONObject> allPks = ConfigConfig.INSTANCE.pk;
            long totalPkSales = 0L;
            int pkCount = 0;
            
            for (JSONObject pk : allPks.values()) {
                if (pk.getLong("item_id", 0L) == itemId) {
                    long stock = pk.getLong("stock", 0L);
                    if (stock > 0) {
                        totalPkSales += stock;
                        pkCount++;
                    }
                }
            }
            
            if (pkCount > 0) {
                // 基于PK数据的平均值进行估算
                long avgPkSales = totalPkSales / pkCount;
                
                DataConfidence confidence = pkCount >= 3 ? DataConfidence.LOW : DataConfidence.VERY_LOW;
                
                return new EstimationResult(avgPkSales, confidence, "PK数据参考")
                        .addDetail("参考PK数量", pkCount)
                        .addDetail("平均销量", avgPkSales);
            }
            
        } catch (Exception e) {
            logger.error("基于PK数据估算销量时发生错误", e);
        }
        
        return new EstimationResult(0L, DataConfidence.VERY_LOW, "PK数据参考(无数据)");
    }
    
    /**
     * 综合多种估算方法的结果
     */
    private EstimationResult combineEstimations(EstimationResult... results) {
        long totalWeightedSales = 0L;
        int totalWeight = 0;
        String combinedMethod = "综合分析(";
        Map<String, Object> combinedDetails = new ConcurrentHashMap<>();
        
        DataConfidence bestConfidence = DataConfidence.VERY_LOW;
        
        for (EstimationResult result : results) {
            if (result.estimatedSales > 0) {
                int weight = result.confidence.score;
                totalWeightedSales += result.estimatedSales * weight;
                totalWeight += weight;
                
                combinedMethod += result.method + ",";
                combinedDetails.put(result.method, result.estimatedSales);
                
                if (result.confidence.score > bestConfidence.score) {
                    bestConfidence = result.confidence;
                }
            }
        }
        
        if (totalWeight > 0) {
            long finalEstimation = totalWeightedSales / totalWeight;
            combinedMethod = combinedMethod.substring(0, combinedMethod.length() - 1) + ")";
            
            // 综合分析的可信度略低于最佳单一方法
            DataConfidence finalConfidence = bestConfidence;
            if (bestConfidence == DataConfidence.HIGH) {
                finalConfidence = DataConfidence.MEDIUM;
            } else if (bestConfidence == DataConfidence.MEDIUM) {
                finalConfidence = DataConfidence.LOW;
            }
            
            return new EstimationResult(finalEstimation, finalConfidence, combinedMethod)
                    .addDetail("加权平均", finalEstimation)
                    .addDetail("总权重", totalWeight);
        }
        
        // 如果所有方法都没有有效结果
        return new EstimationResult(0L, DataConfidence.VERY_LOW, "无有效数据");
    }
    
    /**
     * 获取商品销量的详细分析报告
     */
    public String getDetailedAnalysisReport(long itemId, long timeWindowMs) {
        EstimationResult result = estimateSales(itemId, timeWindowMs);
        
        StringBuilder report = new StringBuilder();
        report.append("=== 销量分析报告 ===").append("\n");
        report.append("商品ID: ").append(itemId).append("\n");
        report.append("分析时间窗口: ").append(timeWindowMs / (60 * 1000)).append("分钟\n");
        report.append("估算销量: ").append(result.estimatedSales).append("分\n");
        report.append("可信度: ").append(result.confidence.description)
              .append("(").append(result.confidence.score).append("%)").append("\n");
        report.append("分析方法: ").append(result.method).append("\n");
        
        if (!result.details.isEmpty()) {
            report.append("详细信息:\n");
            for (Map.Entry<String, Object> entry : result.details.entrySet()) {
                report.append("  ").append(entry.getKey()).append(": ")
                      .append(entry.getValue()).append("\n");
            }
        }
        
        // 添加SKU详细分析
        Map<String, Long> skuSales = stockMonitor.getDetailedSkuSales(itemId, timeWindowMs);
        if (!skuSales.isEmpty()) {
            report.append("SKU销量详情:\n");
            for (Map.Entry<String, Long> entry : skuSales.entrySet()) {
                report.append("  ").append(entry.getKey()).append(": ")
                      .append(entry.getValue()).append("分\n");
            }
        }
        
        return report.toString();
    }
    
    /**
     * 检查数据是否过期
     */
    public boolean isDataStale(long itemId, long maxAgeMs) {
        int historySize = stockMonitor.getHistorySize(itemId);
        return historySize == 0; // 简化实现，可以根据需要增加更复杂的逻辑
    }
    
    /**
     * 获取推荐的监控间隔
     */
    public int getRecommendedMonitoringInterval(long itemId) {
        // 基于商品活跃度推荐监控间隔
        long hourlyRate = stockMonitor.getSalesRate(itemId);
        
        if (hourlyRate > 10000) { // 高活跃度商品
            return 2; // 2分钟
        } else if (hourlyRate > 1000) { // 中活跃度商品
            return 5; // 5分钟
        } else { // 低活跃度商品
            return 10; // 10分钟
        }
    }
}