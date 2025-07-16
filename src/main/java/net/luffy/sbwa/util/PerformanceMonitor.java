package net.luffy.sbwa.util;

import net.luffy.sbwa.handler.WeidianHandler;
import net.luffy.sbwa.handler.StockMonitor;
import net.luffy.sbwa.handler.WebSalesExtractor;
import net.luffy.sbwa.NewboyWeidianAddon;
import net.mamoe.mirai.utils.MiraiLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能监控工具类
 * 监控系统资源使用情况和关键性能指标
 */
public class PerformanceMonitor {
    private static final MiraiLogger logger = NewboyWeidianAddon.INSTANCE.getLogger();
    
    private static PerformanceMonitor instance;
    private final ScheduledExecutorService scheduler;
    private final MemoryMXBean memoryBean;
    private final ThreadMXBean threadBean;
    
    // 性能计数器
    private final AtomicLong httpRequestCount = new AtomicLong(0);
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    
    private volatile boolean monitoring = false;
    
    private PerformanceMonitor() {
        this.scheduler = Executors.newScheduledThreadPool(1, 
            r -> new Thread(r, "PerformanceMonitor"));
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.threadBean = ManagementFactory.getThreadMXBean();
    }
    
    public static synchronized PerformanceMonitor getInstance() {
        if (instance == null) {
            instance = new PerformanceMonitor();
        }
        return instance;
    }
    
    /**
     * 开始性能监控
     */
    public void startMonitoring() {
        if (monitoring) {
            return;
        }
        
        monitoring = true;
        logger.info("开始性能监控...");
        
        // 每30秒输出一次性能报告
        scheduler.scheduleAtFixedRate(this::logPerformanceReport, 30, 30, TimeUnit.SECONDS);
        
        // 每5分钟清理过期缓存
        scheduler.scheduleAtFixedRate(this::cleanupCaches, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * 停止性能监控
     */
    public void stopMonitoring() {
        if (!monitoring) {
            return;
        }
        
        monitoring = false;
        logger.info("停止性能监控...");
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 记录HTTP请求
     */
    public void recordHttpRequest() {
        httpRequestCount.incrementAndGet();
    }
    
    /**
     * 记录缓存命中
     */
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }
    
    /**
     * 记录缓存未命中
     */
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }
    
    /**
     * 记录错误
     */
    public void recordError() {
        errorCount.incrementAndGet();
    }
    
    /**
     * 获取缓存命中率
     */
    public double getCacheHitRate() {
        long hits = cacheHitCount.get();
        long misses = cacheMissCount.get();
        long total = hits + misses;
        return total > 0 ? (double) hits / total * 100 : 0.0;
    }
    
    /**
     * 输出性能报告
     */
    private void logPerformanceReport() {
        try {
            StringBuilder report = new StringBuilder();
            report.append("\n=== 性能监控报告 ===");
            
            // 内存使用情况
            long usedMemory = memoryBean.getHeapMemoryUsage().getUsed() / 1024 / 1024;
            long maxMemory = memoryBean.getHeapMemoryUsage().getMax() / 1024 / 1024;
            double memoryUsage = maxMemory > 0 ? (double) usedMemory / maxMemory * 100 : 0;
            report.append(String.format("\n内存使用: %dMB / %dMB (%.1f%%)", 
                usedMemory, maxMemory, memoryUsage));
            
            // 线程使用情况
            int threadCount = threadBean.getThreadCount();
            report.append(String.format("\n活跃线程数: %d", threadCount));
            
            // 请求统计
            report.append(String.format("\nHTTP请求总数: %d", httpRequestCount.get()));
            report.append(String.format("\n缓存命中率: %.1f%% (%d/%d)", 
                getCacheHitRate(), cacheHitCount.get(), 
                cacheHitCount.get() + cacheMissCount.get()));
            report.append(String.format("\n错误总数: %d", errorCount.get()));
            
            // 组件状态
            try {
                report.append("\n--- 组件状态 ---");
                report.append("\n" + WeidianHandler.INSTANCE.getCacheStats());
                report.append("\n" + WeidianHandler.INSTANCE.getThreadPoolStats());
                
                // StockMonitor状态监控（需要实例引用）
                // report.append("\nStockMonitor: 需要实例引用");
                
                WebSalesExtractor extractor = new WebSalesExtractor();
                report.append("\n" + extractor.getCacheStats());
                report.append("\n" + extractor.getThreadPoolStats());
            } catch (Exception e) {
                report.append("\n组件状态获取失败: " + e.getMessage());
            }
            
            report.append("\n===================");
            logger.info(report.toString());
            
            // 内存使用率过高时发出警告
            if (memoryUsage > 80) {
                logger.warning(String.format("内存使用率过高: %.1f%%, 建议检查内存泄漏", memoryUsage));
            }
            
            // 错误率过高时发出警告
            long totalRequests = httpRequestCount.get();
            if (totalRequests > 100 && errorCount.get() * 100.0 / totalRequests > 10) {
                logger.warning(String.format("错误率过高: %.1f%%, 建议检查系统状态", 
                    errorCount.get() * 100.0 / totalRequests));
            }
            
        } catch (Exception e) {
            logger.error("生成性能报告失败", e);
        }
    }
    
    /**
     * 清理过期缓存
     */
    private void cleanupCaches() {
        try {
            logger.debug("开始清理过期缓存...");
            
            // 清理WeidianHandler缓存
            WeidianHandler.INSTANCE.cleanExpiredCache();
            
            // 清理StockMonitor数据（需要实例引用）
            // StockMonitor实例清理需要在具体使用时处理
            
            // 清理WebSalesExtractor缓存
            WebSalesExtractor extractor = new WebSalesExtractor();
            extractor.cleanExpiredCache();
            
            logger.debug("缓存清理完成");
        } catch (Exception e) {
            logger.error("清理缓存失败", e);
        }
    }
    
    /**
     * 获取当前性能快照
     */
    public String getPerformanceSnapshot() {
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed() / 1024 / 1024;
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax() / 1024 / 1024;
        int threadCount = threadBean.getThreadCount();
        
        return String.format("性能快照: 内存=%dMB/%dMB, 线程=%d, 请求=%d, 缓存命中率=%.1f%%, 错误=%d",
            usedMemory, maxMemory, threadCount, httpRequestCount.get(), 
            getCacheHitRate(), errorCount.get());
    }
    
    /**
     * 重置性能计数器
     */
    public void resetCounters() {
        httpRequestCount.set(0);
        cacheHitCount.set(0);
        cacheMissCount.set(0);
        errorCount.set(0);
        logger.info("性能计数器已重置");
    }
}