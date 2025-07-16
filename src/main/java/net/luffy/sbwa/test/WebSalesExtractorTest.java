package net.luffy.sbwa.test;

import net.luffy.sbwa.handler.WebSalesExtractor;
import net.luffy.sbwa.handler.WeidianHandler;
import net.luffy.sbwa.handler.SalesEstimator;

/**
 * 网页销量提取器测试类
 */
public class WebSalesExtractorTest {
    
    /**
     * 测试从指定微店页面提取销量
     */
    public static void testExtractSalesFromSpecificItem() {
        System.out.println("=== 测试网页销量提取功能 ===");
        
        // 测试商品ID: 7484720354 (用户提供的示例)
        long testItemId = 7484720354L;
        
        WebSalesExtractor extractor = new WebSalesExtractor();
        
        try {
            // 提取网页销量数据
            WebSalesExtractor.WebSalesData webData = extractor.extractSalesFromWeb(testItemId);
            
            System.out.println("商品ID: " + testItemId);
            System.out.println("数据获取时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new java.util.Date(webData.timestamp)));
            System.out.println("数据是否有效: " + webData.isValid);
            
            if (webData.isValid) {
                System.out.println("网页显示销量: " + webData.salesCount + "件");
                System.out.println("价格范围: " + webData.priceRange);
                System.out.println("匹配的原始文本: " + webData.rawText);
                
                // 模拟估算销量进行对比
                long mockEstimatedSales = webData.salesCount * 1103; // 假设平均价格11.03元
                
                WebSalesExtractor.SalesComparisonResult comparison = 
                    extractor.compareSales(testItemId, mockEstimatedSales, 60 * 60 * 1000);
                
                System.out.println("\n=== 准确性对比 ===");
                System.out.println("网页销量: " + comparison.webSalesCount + "件");
                System.out.println("模拟估算: " + comparison.estimatedSales + "分");
                System.out.println("准确性评估: " + comparison.getAccuracyLevel() + 
                        " (" + String.format("%.1f", comparison.accuracy * 100) + "%)");
                System.out.println("详细分析: " + comparison.analysis);
                
            } else {
                System.out.println("数据提取失败: " + webData.rawText);
            }
            
        } catch (Exception e) {
            System.err.println("测试过程中发生错误: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 测试HTML解析功能
     */
    public static void testHtmlParsing() {
        System.out.println("\n=== 测试HTML解析功能 ===");
        
        WebSalesExtractor extractor = new WebSalesExtractor();
        
        // 测试用例1：用户发现的具体格式
        String realFormatHtml = "<html>" +
                "<head><title>【雷宇霄】夏季周边售卖</title></head>" +
                "<body>" +
                "<div class=\"price\">11.03 起</div>" +
                "<em data-v-486d16e2=\"\" class=\"sale-count\">销量 28</em>" +
                "<div class=\"item-title\">【雷宇霄】夏季周边售卖</div>" +
                "<div class=\"rating\">好评100.00%</div>" +
                "</body>" +
                "</html>";
        
        // 测试用例2：通用格式
        String generalFormatHtml = "<html>" +
                "<body>" +
                "<div class=\"price\">11.03 起</div>" +
                "<div class=\"sales-info\">销量 28</div>" +
                "<div class=\"item-title\">【雷宇霄】夏季周边售卖</div>" +
                "</body>" +
                "</html>";
        
        // 测试用例3：其他可能的格式
        String alternativeFormatHtml = "<html>" +
                "<body>" +
                "<span class=\"sale-count-wrapper\">已售 15</span>" +
                "<div class=\"price\">￥9.99</div>" +
                "</body>" +
                "</html>";
        
        String[] testCases = {
            realFormatHtml,
            generalFormatHtml, 
            alternativeFormatHtml
        };
        
        String[] testNames = {
            "用户发现的真实格式",
            "通用销量格式",
            "其他可能格式"
        };
        
        try {
            // 使用反射调用私有方法进行测试（仅用于测试目的）
            java.lang.reflect.Method parseMethod = WebSalesExtractor.class
                    .getDeclaredMethod("parseSalesFromHtml", long.class, String.class);
            parseMethod.setAccessible(true);
            
            for (int i = 0; i < testCases.length; i++) {
                System.out.println("\n测试 " + (i + 1) + ": " + testNames[i]);
                
                WebSalesExtractor.WebSalesData result = 
                        (WebSalesExtractor.WebSalesData) parseMethod.invoke(extractor, 7484720354L, testCases[i]);
                
                System.out.println("- 销量: " + result.salesCount);
                System.out.println("- 价格: " + result.priceRange);
                System.out.println("- 原始文本: " + result.rawText);
                System.out.println("- 是否有效: " + result.isValid);
                
                if (result.isValid) {
                    System.out.println("✅ 解析成功");
                } else {
                    System.out.println("❌ 解析失败");
                }
            }
            
        } catch (Exception e) {
            System.err.println("HTML解析测试失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 测试与现有系统的集成
     */
    public static void testIntegrationWithExistingSystem() {
        System.out.println("\n=== 测试系统集成 ===");
        
        try {
            long testItemId = 7484720354L;
            long timeWindow = 60 * 60 * 1000; // 1小时
            
            // 测试通过WeidianHandler获取网页销量
            WebSalesExtractor.WebSalesData webData = WeidianHandler.INSTANCE.getWebSales(testItemId);
            System.out.println("通过WeidianHandler获取网页销量: " + 
                    (webData.isValid ? webData.salesCount + "件" : "失败"));
            
            // 测试验证功能
            String validationReport = WeidianHandler.INSTANCE.validateSalesAccuracy(testItemId, timeWindow);
            System.out.println("\n验证报告:");
            System.out.println(validationReport);
            
        } catch (Exception e) {
            System.err.println("系统集成测试失败: " + e.getMessage());
        }
    }
    
    /**
     * 主测试方法
     */
    public static void main(String[] args) {
        System.out.println("开始测试网页销量提取功能...");
        System.out.println("测试目标: https://weidian.com/item.html?itemID=7484720354");
        System.out.println();
        
        // 运行各项测试
        testExtractSalesFromSpecificItem();
        testHtmlParsing();
        testIntegrationWithExistingSystem();
        
        System.out.println("\n=== 测试完成 ===");
        System.out.println("\n使用说明:");
        System.out.println("1. 使用 '/pk 验证 <pkID>' 命令验证PK中商品的销量准确性");
        System.out.println("2. 系统会自动从网页获取销量并与估算结果对比");
        System.out.println("3. 验证结果包含准确性评估和改进建议");
        System.out.println("4. 网页数据会缓存5分钟以提高性能");
    }
}