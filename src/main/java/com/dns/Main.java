package com.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            // 创建解析器
            DNSResolver resolver = new DNSResolver();
            
            // 创建并启动DNS服务器
            DNSServer server = new DNSServer(resolver);
            server.start();
            
            // 添加关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("正在关闭DNS服务器...");
                server.stop();
            }));
            
            logger.info("DNS服务器已启动，按Ctrl+C停止");
            
            // 保持主线程运行
            Thread.currentThread().join();
            
        } catch (Exception e) {
            logger.error("服务器启动失败: {}", e.getMessage());
            System.exit(1);
        }
    }
}
