package net.luffy.sbwa.handler;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.sbwa.NewboyWeidianAddon;
import net.mamoe.mirai.utils.MiraiLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * 库存监控器 - 通过监控库存变化来估算销量
 */
public class StockMonitor {
    
    private static final MiraiLogger logger = NewboyWeidianAddon.INSTANCE.getLogger();
    
    // 优化的调度线程池
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
        3, // 核心线程数
        r -> new Thread(r, "StockMonitor-" + System.currentTimeMillis()),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    
    // 最大历史记录数（每个商品）
    private static final int MAX_HISTORY_SIZE = 50;
    // 数据保留时间（24小时）
    private static final long DATA_RETENTION_MS = 24 * 60 * 60 * 1000L;
    
    // 存储商品的历史库存数据
    private final Map<Long, List<StockRecord>> stockHistory = new ConcurrentHashMap<>();
    
    // 存储商品的SKU详细信息
    private final Map<Long, Map<String, SkuInfo>> skuInfoCache = new ConcurrentHashMap<>();
    
    public static class StockRecord {
        public final long timestamp;
        public final long totalStockValue; // 总库存价值
        public final Map<String, Integer> skuStocks; // 各SKU库存数量
        
        public StockRecord(long timestamp, long totalStockValue, Map<String, Integer> skuStocks) {
            this.timestamp = timestamp;
            this.totalStockValue = totalStockValue;
            this.skuStocks = new ConcurrentHashMap<>(skuStocks);
        }
    }
    
    public static class SkuInfo {
        public final String skuId;
        public final int price; // 价格（分为单位）
        public final String name; // SKU名称
        
        public SkuInfo(String skuId, int price, String name) {
            this.skuId = skuId;
            this.price = price;
            this.name = name;
        }
    }
    
    /**
     * 开始监控指定商品的库存变化
     * @param itemId 商品ID
     * @param intervalMinutes 监控间隔（分钟）
     */
    public void startMonitoring(long itemId, int intervalMinutes) {
        logger.info("开始监控商品 " + itemId + " 的库存变化，间隔 " + intervalMinutes + " 分钟");
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                recordCurrentStock(itemId);
            } catch (Exception e) {
                logger.error("监控商品 " + itemId + " 库存时发生错误", e);
            }
        }, 0, intervalMinutes, TimeUnit.MINUTES);
    }
    
    /**
     * 记录当前库存状态
     */
    private void recordCurrentStock(long itemId) {
        try {
            String response = WeidianHandler.INSTANCE.httpGet(
                String.format(WeidianHandler.APIStock, itemId)
            );
            
            JSONObject result = JSONUtil.parseObj(response);
            if (result == null || result.getJSONObject("status").getInt("code") != 0) {
                return;
            }
            
            JSONObject data = result.getJSONObject("result");
            long totalStockValue = 0;
            Map<String, Integer> currentSkuStocks = new ConcurrentHashMap<>();
            Map<String, SkuInfo> currentSkuInfos = new ConcurrentHashMap<>();
            
            if (data.containsKey("skuInfos")) {
                JSONArray skuInfos = data.getJSONArray("skuInfos");
                for (Object obj : skuInfos) {
                    JSONObject skuData = JSONUtil.parseObj(obj).getJSONObject("skuInfo");
                    String skuId = skuData.getStr("skuId", "");
                    int price = skuData.getInt("originalPrice", 0);
                    int stock = skuData.getInt("stock", 0);
                    String skuName = skuData.getStr("skuName", "");
                    
                    totalStockValue += (long) price * stock;
                    currentSkuStocks.put(skuId, stock);
                    currentSkuInfos.put(skuId, new SkuInfo(skuId, price, skuName));
                }
            } else {
                // 单SKU商品
                int price = data.getInt("itemDiscountHighPrice", 0);
                int stock = data.getInt("itemStock", 0);
                totalStockValue = (long) price * stock;
                currentSkuStocks.put("default", stock);
                currentSkuInfos.put("default", new SkuInfo("default", price, "默认规格"));
            }
            
            // 更新SKU信息缓存
            skuInfoCache.put(itemId, currentSkuInfos);
            
            // 记录历史数据
            StockRecord record = new StockRecord(
                System.currentTimeMillis(),
                totalStockValue,
                currentSkuStocks
            );
            
            stockHistory.computeIfAbsent(itemId, k -> new ArrayList<>()).add(record);
            
            // 保持历史记录在合理范围内
            List<StockRecord> history = stockHistory.get(itemId);
            cleanHistoryData(history);
            
            // 定期清理过期数据
            if (System.currentTimeMillis() % (5 * 60 * 1000) == 0) { // 每5分钟检查一次
                cleanExpiredData();
            }
            
            logger.debug("记录商品 " + itemId + " 库存数据，总价值: " + totalStockValue);
            
        } catch (Exception e) {
            logger.error("记录商品 " + itemId + " 库存数据时发生错误", e);
        }
    }
    
    /**
     * 基于库存变化估算销量
     * @param itemId 商品ID
     * @param timeWindowMs 时间窗口（毫秒）
     * @return 估算的销量金额（分为单位）
     */
    public long estimateSalesFromStockChange(long itemId, long timeWindowMs) {
        List<StockRecord> history = stockHistory.get(itemId);
        if (history == null || history.size() < 2) {
            return 0L;
        }
        
        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - timeWindowMs;
        
        // 找到时间窗口内的第一条和最后一条记录
        StockRecord firstRecord = null;
        StockRecord lastRecord = null;
        
        for (StockRecord record : history) {
            if (record.timestamp >= startTime) {
                if (firstRecord == null) {
                    firstRecord = record;
                }
                lastRecord = record;
            }
        }
        
        if (firstRecord == null || lastRecord == null || firstRecord == lastRecord) {
            return 0L;
        }
        
        // 计算总库存价值的变化
        long stockValueDecrease = firstRecord.totalStockValue - lastRecord.totalStockValue;
        
        // 库存价值减少即为销量
        return Math.max(0L, stockValueDecrease);
    }
    
    /**
     * 清理单个商品的历史数据
     */
    private void cleanHistoryData(List<StockRecord> history) {
        if (history == null) return;
        
        // 按时间排序并移除过期数据
        long cutoffTime = System.currentTimeMillis() - DATA_RETENTION_MS;
        history.removeIf(record -> record.timestamp < cutoffTime);
        
        // 限制记录数量
        while (history.size() > MAX_HISTORY_SIZE) {
            history.remove(0);
        }
    }
    
    /**
     * 清理所有过期数据
     */
    public void cleanExpiredData() {
        long cutoffTime = System.currentTimeMillis() - DATA_RETENTION_MS;
        
        Iterator<Map.Entry<Long, List<StockRecord>>> iterator = stockHistory.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, List<StockRecord>> entry = iterator.next();
            List<StockRecord> history = entry.getValue();
            
            // 移除过期记录
            history.removeIf(record -> record.timestamp < cutoffTime);
            
            // 如果没有有效数据，移除整个条目
            if (history.isEmpty()) {
                iterator.remove();
            }
        }
        
        // 清理SKU信息缓存中的无用数据
        skuInfoCache.entrySet().removeIf(entry -> !stockHistory.containsKey(entry.getKey()));
        
        logger.debug("清理过期数据完成，当前监控商品数: " + stockHistory.size());
    }
    
    /**
     * 获取指定商品的历史记录数量
     */
    public int getHistorySize(long itemId) {
        List<StockRecord> history = stockHistory.get(itemId);
        return history != null ? history.size() : 0;
    }
    
    /**
     * 获取销售速率（每小时）
     */
    public long getSalesRate(long itemId) {
        List<StockRecord> history = stockHistory.get(itemId);
        if (history == null || history.size() < 2) {
            return 0L;
        }
        
        // 计算最近1小时的销售速率
        long oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000L;
        
        StockRecord recentRecord = null;
        StockRecord oldRecord = null;
        
        for (int i = history.size() - 1; i >= 0; i--) {
            StockRecord record = history.get(i);
            if (record.timestamp >= oneHourAgo) {
                if (recentRecord == null) {
                    recentRecord = record;
                }
                oldRecord = record;
            } else {
                break;
            }
        }
        
        if (recentRecord != null && oldRecord != null && recentRecord != oldRecord) {
            long timeDiff = recentRecord.timestamp - oldRecord.timestamp;
            long stockDiff = oldRecord.totalStockValue - recentRecord.totalStockValue;
            
            if (timeDiff > 0 && stockDiff > 0) {
                // 转换为每小时的销售额
                return (stockDiff * 60 * 60 * 1000L) / timeDiff;
            }
        }
        
        return 0L;
    }
    
    /**
     * 获取监控统计信息
     */
    public String getMonitoringStats() {
        int totalItems = stockHistory.size();
        int totalRecords = stockHistory.values().stream()
            .mapToInt(List::size)
            .sum();
        
        return String.format("库存监控统计: 监控商品=%d, 历史记录=%d, 线程池活跃=%d",
            totalItems, totalRecords, scheduler.getActiveCount());
    }
    
    /**
     * 获取详细的SKU销量分析
     * @param itemId 商品ID
     * @param timeWindowMs 时间窗口（毫秒）
     * @return SKU销量详情
     */
    public Map<String, Long> getDetailedSkuSales(long itemId, long timeWindowMs) {
        Map<String, Long> skuSales = new ConcurrentHashMap<>();
        List<StockRecord> history = stockHistory.get(itemId);
        Map<String, SkuInfo> skuInfos = skuInfoCache.get(itemId);
        
        if (history == null || history.size() < 2 || skuInfos == null) {
            return skuSales;
        }
        
        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - timeWindowMs;
        
        StockRecord firstRecord = null;
        StockRecord lastRecord = null;
        
        for (StockRecord record : history) {
            if (record.timestamp >= startTime) {
                if (firstRecord == null) {
                    firstRecord = record;
                }
                lastRecord = record;
            }
        }
        
        if (firstRecord == null || lastRecord == null) {
            return skuSales;
        }
        
        // 计算每个SKU的销量
        for (String skuId : skuInfos.keySet()) {
            SkuInfo skuInfo = skuInfos.get(skuId);
            Integer firstStock = firstRecord.skuStocks.get(skuId);
            Integer lastStock = lastRecord.skuStocks.get(skuId);
            
            if (firstStock != null && lastStock != null && firstStock > lastStock) {
                int soldQuantity = firstStock - lastStock;
                long soldValue = (long) soldQuantity * skuInfo.price;
                skuSales.put(skuId, soldValue);
            }
        }
        
        return skuSales;
    }
    
    /**
     * 停止监控并清理资源
     */
    public void shutdown() {
        logger.info("正在停止库存监控服务...");
        
        // 清理所有数据
        stockHistory.clear();
        skuInfoCache.clear();
        
        // 关闭调度器
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                logger.warning("强制关闭库存监控调度器");
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
            logger.error("关闭库存监控调度器时被中断", e);
        }
        
        logger.info("库存监控服务已停止");
    }
}