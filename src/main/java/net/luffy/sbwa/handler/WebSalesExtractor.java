package net.luffy.sbwa.handler;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import net.luffy.sbwa.NewboyWeidianAddon;
import net.mamoe.mirai.utils.MiraiLogger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.Map;

/**
 * 网页销量提取器 - 从微店商品页面获取显示的销量数据
 */
public class WebSalesExtractor {
    
    private static final MiraiLogger logger = NewboyWeidianAddon.INSTANCE.getLogger();
    
    // HTTP请求线程池
    private static final ThreadPoolExecutor httpExecutor = new ThreadPoolExecutor(
        4, 8, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(100),
        r -> new Thread(r, "WebSalesExtractor-" + System.currentTimeMillis()),
        new ThreadPoolExecutor.CallerRunsPolicy()
    );
    
    // 销量数据缓存
    private final Map<Long, WebSalesData> salesCache = new ConcurrentHashMap<>();
    
    // 缓存有效期（5分钟）
    private static final long CACHE_VALIDITY_MS = 5 * 60 * 1000;
    
    /**
     * 网页销量数据
     */
    public static class WebSalesData {
        public final long itemId;
        public final long salesCount;     // 销量数字
        public final String priceRange;   // 价格范围
        public final long timestamp;      // 获取时间
        public final boolean isValid;     // 数据是否有效
        public final String rawText;      // 原始文本
        
        public WebSalesData(long itemId, long salesCount, String priceRange, String rawText) {
            this.itemId = itemId;
            this.salesCount = salesCount;
            this.priceRange = priceRange;
            this.timestamp = System.currentTimeMillis();
            this.isValid = salesCount > 0;
            this.rawText = rawText;
        }
        
        public WebSalesData(long itemId, String error) {
            this.itemId = itemId;
            this.salesCount = 0;
            this.priceRange = "";
            this.timestamp = System.currentTimeMillis();
            this.isValid = false;
            this.rawText = error;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_VALIDITY_MS;
        }
    }
    
    /**
     * 从微店商品页面获取销量数据
     * @param itemId 商品ID
     * @return 销量数据
     */
    public WebSalesData extractSalesFromWeb(long itemId) {
        // 检查缓存
        WebSalesData cached = salesCache.get(itemId);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }
        
        try {
            String url = "https://weidian.com/item.html?itemID=" + itemId;
            
            // 发送HTTP请求获取页面内容
            HttpResponse response = HttpRequest.get(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
                    .header("Accept-Encoding", "gzip, deflate")
                    .timeout(10000)
                    .execute();
            
            if (response.getStatus() != 200) {
                logger.warning("获取商品页面失败，状态码: " + response.getStatus());
                WebSalesData errorData = new WebSalesData(itemId, "HTTP错误: " + response.getStatus());
                salesCache.put(itemId, errorData);
                return errorData;
            }
            
            String html = response.body();
            WebSalesData salesData = parseSalesFromHtml(itemId, html);
            
            // 缓存结果
            salesCache.put(itemId, salesData);
            
            logger.info("成功获取商品 " + itemId + " 的网页销量: " + salesData.salesCount);
            return salesData;
            
        } catch (Exception e) {
            logger.error("获取商品 " + itemId + " 的网页销量时发生错误", e);
            WebSalesData errorData = new WebSalesData(itemId, "网络错误: " + e.getMessage());
            salesCache.put(itemId, errorData);
            return errorData;
        }
    }
    
    /**
     * 从HTML内容中解析销量信息
     * @param itemId 商品ID
     * @param html HTML内容
     * @return 销量数据
     */
    private WebSalesData parseSalesFromHtml(long itemId, String html) {
        try {
            // 匹配销量的正则表达式模式
            // 优先匹配用户发现的具体格式：<em data-v-486d16e2="" class="sale-count">销量 28</em>
            Pattern[] salesPatterns = {
                // 用户发现的具体格式 - 最高优先级
                Pattern.compile("<em[^>]*class=\"sale-count\"[^>]*>销量\\s*(\\d+)</em>"),
                Pattern.compile("<em[^>]*class=\"sale-count\"[^>]*>已售\\s*(\\d+)</em>"),
                Pattern.compile("<em[^>]*class='sale-count'[^>]*>销量\\s*(\\d+)</em>"),
                Pattern.compile("<em[^>]*class='sale-count'[^>]*>已售\\s*(\\d+)</em>"),
                
                // 更宽泛的sale-count类匹配
                Pattern.compile("class=\"[^\"]*sale-count[^\"]*\"[^>]*>(?:销量|已售)\\s*(\\d+)"),
                Pattern.compile("class='[^']*sale-count[^']*'[^>]*>(?:销量|已售)\\s*(\\d+)"),
                
                // 带data-v属性的格式
                Pattern.compile("data-v-[^=]*=\"[^\"]*\"[^>]*class=\"sale-count\"[^>]*>(?:销量|已售)\\s*(\\d+)"),
                
                // 通用销量格式
                Pattern.compile("销量\\s*(\\d+)"),
                Pattern.compile("已售\\s*(\\d+)"),
                Pattern.compile("售出\\s*(\\d+)"),
                Pattern.compile("销售\\s*(\\d+)"),
                
                // JSON格式
                Pattern.compile("\"sales\":\\s*(\\d+)"),
                Pattern.compile("\"soldCount\":\\s*(\\d+)")
            };
            
            long salesCount = 0;
            String matchedText = "";
            String usedPattern = "";
            
            for (Pattern pattern : salesPatterns) {
                Matcher matcher = pattern.matcher(html);
                if (matcher.find()) {
                    try {
                        salesCount = Long.parseLong(matcher.group(1));
                        matchedText = matcher.group(0);
                        usedPattern = pattern.pattern();
                        logger.info("成功匹配销量，使用模式: " + usedPattern.substring(0, Math.min(50, usedPattern.length())) + "...");
                        break;
                    } catch (NumberFormatException e) {
                        // 继续尝试下一个模式
                    }
                }
            }
            
            // 如果没有匹配到，进行调试输出
            if (salesCount == 0) {
                logger.warning("未能从HTML中解析出销量信息");
                
                // 检查是否包含sale-count类但格式不匹配
                if (html.contains("sale-count")) {
                    logger.info("HTML中包含sale-count类，但格式可能不匹配");
                    Pattern debugPattern = Pattern.compile("<[^>]*sale-count[^>]*>[^<]*</[^>]*>");
                    Matcher debugMatcher = debugPattern.matcher(html);
                    if (debugMatcher.find()) {
                        logger.info("找到的sale-count元素: " + debugMatcher.group());
                    }
                }
                
                // 输出包含"销量"的所有文本片段用于调试
                Pattern debugSalesPattern = Pattern.compile("[^<>]*销量[^<>]*");
                Matcher debugSalesMatcher = debugSalesPattern.matcher(html);
                while (debugSalesMatcher.find()) {
                    logger.info("找到包含'销量'的文本: " + debugSalesMatcher.group());
                }
            }
            
            // 匹配价格范围
            String priceRange = "";
            Pattern[] pricePatterns = {
                Pattern.compile("(\\d+\\.\\d+)\\s*起"),
                Pattern.compile("￥(\\d+\\.\\d+)"),
                Pattern.compile("价格[：:]\\s*(\\d+\\.\\d+)")
            };
            
            for (Pattern pricePattern : pricePatterns) {
                Matcher priceMatcher = pricePattern.matcher(html);
                if (priceMatcher.find()) {
                    priceRange = priceMatcher.group(0);
                    break;
                }
            }
            
            if (salesCount > 0) {
                logger.info("成功解析销量: " + salesCount + ", 匹配文本: " + matchedText);
                return new WebSalesData(itemId, salesCount, priceRange, matchedText);
            } else {
                return new WebSalesData(itemId, "未找到销量信息");
            }
            
        } catch (Exception e) {
            logger.error("解析HTML销量信息时发生错误", e);
            return new WebSalesData(itemId, "解析错误: " + e.getMessage());
        }
    }
    
    /**
     * 比较网页销量与估算销量的准确性
     * @param itemId 商品ID
     * @param estimatedSales 估算销量（分为单位）
     * @param timeWindowMs 时间窗口
     * @return 比较结果
     */
    public SalesComparisonResult compareSales(long itemId, long estimatedSales, long timeWindowMs) {
        WebSalesData webData = extractSalesFromWeb(itemId);
        
        if (!webData.isValid) {
            return new SalesComparisonResult(itemId, 0, estimatedSales, 0.0, 
                    "无法获取网页销量: " + webData.rawText);
        }
        
        // 网页显示的是总销量，需要转换为金额（这里需要根据实际情况调整）
        // 假设平均单价，这个需要根据实际商品信息来计算
        long webSalesAmount = webData.salesCount * 1000; // 假设平均10元一件
        
        // 计算准确率
        double accuracy = 0.0;
        if (webSalesAmount > 0) {
            accuracy = 1.0 - Math.abs(estimatedSales - webSalesAmount) / (double) webSalesAmount;
            accuracy = Math.max(0.0, Math.min(1.0, accuracy)); // 限制在0-1之间
        }
        
        String analysis = String.format(
                "网页销量: %d件, 估算金额: %d分, 网页金额(估): %d分, 准确率: %.2f%%",
                webData.salesCount, estimatedSales, webSalesAmount, accuracy * 100
        );
        
        return new SalesComparisonResult(itemId, webData.salesCount, estimatedSales, accuracy, analysis);
    }
    
    /**
     * 销量比较结果
     */
    public static class SalesComparisonResult {
        public final long itemId;
        public final long webSalesCount;      // 网页显示的销量（件数）
        public final long estimatedSales;     // 估算的销量（分为单位）
        public final double accuracy;         // 准确率 (0.0 - 1.0)
        public final String analysis;         // 分析说明
        
        public SalesComparisonResult(long itemId, long webSalesCount, long estimatedSales, 
                                   double accuracy, String analysis) {
            this.itemId = itemId;
            this.webSalesCount = webSalesCount;
            this.estimatedSales = estimatedSales;
            this.accuracy = accuracy;
            this.analysis = analysis;
        }
        
        public String getAccuracyLevel() {
            if (accuracy >= 0.9) return "非常准确";
            if (accuracy >= 0.8) return "较准确";
            if (accuracy >= 0.7) return "一般准确";
            if (accuracy >= 0.5) return "准确性较低";
            return "准确性很低";
        }
    }
    
    /**
     * 异步提取销量数据
     */
    public CompletableFuture<WebSalesData> extractSalesAsync(long itemId) {
        return CompletableFuture.supplyAsync(() -> extractSalesFromWeb(itemId), httpExecutor)
            .exceptionally(throwable -> {
                logger.error("异步提取销量失败: itemId=" + itemId, throwable);
                return new WebSalesData(itemId, "异步提取失败: " + throwable.getMessage());
            });
    }
    
    /**
     * 批量异步提取销量数据
     */
    public CompletableFuture<Map<Long, WebSalesData>> extractSalesBatch(java.util.List<Long> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return CompletableFuture.completedFuture(new ConcurrentHashMap<>());
        }
        
        Map<Long, CompletableFuture<WebSalesData>> futures = new ConcurrentHashMap<>();
        for (Long itemId : itemIds) {
            futures.put(itemId, extractSalesAsync(itemId));
        }
        
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<Long, WebSalesData> results = new ConcurrentHashMap<>();
                futures.forEach((itemId, future) -> {
                    try {
                        results.put(itemId, future.get());
                    } catch (Exception e) {
                        logger.error("获取销量结果失败: itemId=" + itemId, e);
                        results.put(itemId, new WebSalesData(itemId, "获取结果失败: " + e.getMessage()));
                    }
                });
                return results;
            })
            .orTimeout(30, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                logger.error("批量提取销量超时或失败", throwable);
                Map<Long, WebSalesData> fallbackResults = new ConcurrentHashMap<>();
                itemIds.forEach(itemId -> fallbackResults.put(itemId, new WebSalesData(itemId, "批量提取超时")));
                return fallbackResults;
            });
    }
    
    /**
     * 清理过期缓存
     */
    public void cleanExpiredCache() {
        salesCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * 获取缓存大小
     */
    public int getCacheSize() {
        return salesCache.size();
    }
    
    /**
     * 获取缓存统计信息
     */
    public String getCacheStats() {
        return String.format("销量缓存统计: 缓存条目=%d", salesCache.size());
    }
    
    /**
     * 获取线程池状态
     */
    public String getThreadPoolStats() {
        return String.format("WebSalesExtractor线程池: 活跃=%d, 队列=%d, 完成=%d",
            httpExecutor.getActiveCount(),
            httpExecutor.getQueue().size(),
            httpExecutor.getCompletedTaskCount());
    }
    
    /**
     * 关闭资源
     */
    public void shutdown() {
        logger.info("正在关闭WebSalesExtractor...");
        
        // 清理缓存
        salesCache.clear();
        
        // 关闭线程池
        httpExecutor.shutdown();
        try {
            if (!httpExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
                logger.warning("强制关闭WebSalesExtractor线程池");
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.error("关闭WebSalesExtractor线程池时被中断", e);
        }
        
        logger.info("WebSalesExtractor已关闭");
    }
}