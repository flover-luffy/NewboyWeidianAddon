package net.luffy.sbwa.handler;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import net.luffy.model.WeidianBuyer;
import net.luffy.model.WeidianCookie;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.ArrayList;

public class WeidianHandler extends net.luffy.handler.WeidianHandler {

    public static WeidianHandler INSTANCE;
    public static final String APIStock = "https://thor.weidian.com/detail/getItemSkuInfo/1.0?param={\"itemId\":\"%s\"}";
    
    // 优化的线程池配置
    private final ThreadPoolExecutor httpExecutor = new ThreadPoolExecutor(
        5, // 核心线程数
        20, // 最大线程数
        60L, TimeUnit.SECONDS, // 空闲时间
        new LinkedBlockingQueue<>(100), // 队列大小
        r -> new Thread(r, "WeidianHttp-" + System.currentTimeMillis()),
        new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
    );
    
    // 库存查询结果缓存（1分钟有效期）
    private final Map<Long, CachedStockResult> stockCache = new ConcurrentHashMap<>();
    
    // 缓存结果类
    private static class CachedStockResult {
        final long stock;
        final long timestamp;
        
        CachedStockResult(long stock) {
            this.stock = stock;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isValid() {
            return System.currentTimeMillis() - timestamp < 60000; // 1分钟有效期
        }
    }
    
    // 库存监控器、销量估算器和网页销量提取器
    private final StockMonitor stockMonitor;
    private final SalesEstimator salesEstimator;
    private final WebSalesExtractor webSalesExtractor;

    public WeidianHandler() {
        INSTANCE = this;
        this.stockMonitor = new StockMonitor();
        this.salesEstimator = new SalesEstimator(stockMonitor);
        this.webSalesExtractor = new WebSalesExtractor();
    }

    public long getTotalStock(long id) {
        // 检查缓存
        CachedStockResult cached = stockCache.get(id);
        if (cached != null && cached.isValid()) {
            return cached.stock;
        }
        
        try {
            String s = get(String.format(APIStock, id));
            JSONObject o = JSONUtil.parseObj(s);
            if (o != null) {
                if (o.getJSONObject("status").getInt("code") == 0) {
                    JSONObject r = o.getJSONObject("result");
                    long total = 0;
                    if (r.containsKey("skuInfos")) {
                        for (Object o1 : r.getJSONArray("skuInfos")) {
                            JSONObject sku = JSONUtil.parseObj(o1).getJSONObject("skuInfo");
                            int price = sku.getInt("originalPrice");//分为单位
                            int stock = sku.getInt("stock");
                            total += (long) price * (long) stock;
                        }
                    } else {
                        total = (long) r.getInt("itemDiscountHighPrice") * (long) r.getInt("itemStock");
                    }
                    
                    // 缓存结果
                    stockCache.put(id, new CachedStockResult(total));
                    return total;
                }
            }
        } catch (Exception e) {
            // 记录错误但不抛出异常
        }
        return 0L;
    }
    
    /**
     * 异步获取库存
     * @param id 商品ID
     * @return CompletableFuture包装的库存值
     */
    public CompletableFuture<Long> getTotalStockAsync(long id) {
        // 检查缓存
        CachedStockResult cached = stockCache.get(id);
        if (cached != null && cached.isValid()) {
            return CompletableFuture.completedFuture(cached.stock);
        }
        
        return CompletableFuture.supplyAsync(() -> getTotalStock(id), httpExecutor);
    }
    
    /**
     * 批量获取库存（异步并行）
     * @param itemIds 商品ID列表
     * @return 商品ID到库存值的映射
     */
    public CompletableFuture<Map<Long, Long>> getTotalStockBatch(List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return CompletableFuture.completedFuture(new ConcurrentHashMap<>());
        }
        
        List<CompletableFuture<Map.Entry<Long, Long>>> futures = itemIds.stream()
            .map(id -> getTotalStockAsync(id)
                .thenApply(stock -> Map.entry(id, stock)))
            .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toMap(
                    Map.Entry::getKey, 
                    Map.Entry::getValue,
                    (existing, replacement) -> replacement,
                    ConcurrentHashMap::new
                )));
    }

    public long getTotalFee(WeidianCookie cookie, long id) {
        long total = 0;
        for (WeidianBuyer buyer : getItemBuyer(cookie, id)) {
            total += buyer.contribution;
        }
        return total;
    }
    
    /**
     * 获取估算的销量（无需Cookie）
     * @param id 商品ID
     * @param timeWindowMs 时间窗口（毫秒）
     * @return 估算的销量金额
     */
    public SalesEstimator.EstimationResult getEstimatedSales(long id, long timeWindowMs) {
        return salesEstimator.estimateSales(id, timeWindowMs);
    }
    
    /**
     * 开始监控商品库存
     * @param id 商品ID
     * @param intervalMinutes 监控间隔（分钟）
     */
    public void startStockMonitoring(long id, int intervalMinutes) {
        stockMonitor.startMonitoring(id, intervalMinutes);
    }
    
    /**
     * 开始智能监控（自动选择监控间隔）
     * @param id 商品ID
     */
    public void startSmartMonitoring(long id) {
        int interval = salesEstimator.getRecommendedMonitoringInterval(id);
        stockMonitor.startMonitoring(id, interval);
    }
    
    /**
     * 获取详细的销量分析报告
     * @param id 商品ID
     * @param timeWindowMs 时间窗口（毫秒）
     * @return 分析报告
     */
    public String getSalesAnalysisReport(long id, long timeWindowMs) {
        return salesEstimator.getDetailedAnalysisReport(id, timeWindowMs);
    }
    
    /**
     * 获取库存监控器
     */
    public StockMonitor getStockMonitor() {
        return stockMonitor;
    }
    
    /**
     * 获取销量估算器
     */
    public SalesEstimator getSalesEstimator() {
        return salesEstimator;
    }
    
    /**
     * 获取网页销量提取器
     */
    public WebSalesExtractor getWebSalesExtractor() {
        return webSalesExtractor;
    }
    
    /**
     * 从网页获取销量数据
     * @param itemId 商品ID
     * @return 网页销量数据
     */
    public WebSalesExtractor.WebSalesData getWebSales(long itemId) {
        return webSalesExtractor.extractSalesFromWeb(itemId);
    }
    
    /**
     * 验证估算销量的准确性
     * @param itemId 商品ID
     * @param timeWindowMs 时间窗口（毫秒）
     * @return 验证结果报告
     */
    public String validateSalesAccuracy(long itemId, long timeWindowMs) {
        try {
            // 获取估算销量
            SalesEstimator.EstimationResult estimation = getEstimatedSales(itemId, timeWindowMs);
            
            // 获取网页销量并进行对比
            WebSalesExtractor.SalesComparisonResult comparison = 
                webSalesExtractor.compareSales(itemId, estimation.estimatedSales, timeWindowMs);
            
            StringBuilder report = new StringBuilder();
            report.append("=== 销量准确性验证报告 ===\n");
            report.append("商品ID: ").append(itemId).append("\n");
            report.append("时间窗口: ").append(timeWindowMs / (60 * 1000)).append("分钟\n");
            report.append("\n【估算结果】\n");
            report.append("估算销量: ").append(estimation.estimatedSales).append("分\n");
            report.append("估算可信度: ").append(estimation.confidence.description)
                  .append("(").append(estimation.confidence.score).append("%)").append("\n");
            report.append("估算方法: ").append(estimation.method).append("\n");
            
            report.append("\n【网页数据】\n");
            if (comparison.webSalesCount > 0) {
                report.append("网页显示销量: ").append(comparison.webSalesCount).append("件\n");
                report.append("准确性评估: ").append(comparison.getAccuracyLevel())
                      .append("(").append(String.format("%.1f", comparison.accuracy * 100)).append("%)").append("\n");
                report.append("详细分析: ").append(comparison.analysis).append("\n");
            } else {
                report.append("网页数据获取失败: ").append(comparison.analysis).append("\n");
            }
            
            // 添加改进建议
            report.append("\n【改进建议】\n");
            if (comparison.accuracy < 0.7) {
                report.append("- 建议增加监控频率以提高准确性\n");
                report.append("- 考虑结合多个时间点的数据进行分析\n");
            }
            if (estimation.confidence.score < 70) {
                report.append("- 建议收集更多历史数据以提高估算可信度\n");
            }
            
            return report.toString();
            
        } catch (Exception e) {
            return "验证销量准确性时发生错误: " + e.getMessage();
        }
    }
    
    /**
     * 公开的HTTP GET方法，供其他组件使用
     * @param url 请求URL
     * @return 响应内容
     */
    public String httpGet(String url) {
        return get(url);
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanExpiredCache() {
        long currentTime = System.currentTimeMillis();
        stockCache.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().timestamp > 60000);
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        int totalEntries = stockCache.size();
        long validEntries = stockCache.values().stream()
            .mapToLong(cache -> cache.isValid() ? 1 : 0)
            .sum();
        
        return String.format("库存缓存统计: 总条目=%d, 有效条目=%d, 命中率=%.2f%%", 
            totalEntries, validEntries, 
            totalEntries > 0 ? (validEntries * 100.0 / totalEntries) : 0.0);
    }
    
    /**
     * 获取线程池状态
     */
    public String getThreadPoolStats() {
        return String.format("HTTP线程池状态: 活跃线程=%d, 队列大小=%d, 完成任务=%d",
            httpExecutor.getActiveCount(),
            httpExecutor.getQueue().size(),
            httpExecutor.getCompletedTaskCount());
    }
    
    /**
     * 停止所有监控和清理资源
     */
    public void shutdown() {
        if (stockMonitor != null) {
            stockMonitor.shutdown();
        }
        if (webSalesExtractor != null) {
            webSalesExtractor.cleanExpiredCache();
        }
        
        // 清理缓存
        stockCache.clear();
        
        // 关闭线程池
        httpExecutor.shutdown();
        try {
            if (!httpExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected HttpRequest setHeader(HttpRequest request) {
        return request;
    }
}
