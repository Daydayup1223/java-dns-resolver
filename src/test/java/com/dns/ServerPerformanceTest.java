package com.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ServerPerformanceTest {
    private ServerPerformance performance;
    private static final String SERVER1 = "8.8.8.8";
    private static final String SERVER2 = "8.8.4.4";
    private static final String SERVER3 = "1.1.1.1";
    private static final String SERVER4 = "1.0.0.1";
    private List<String> allServers;

    @BeforeEach
    void setUp() {
        performance = new ServerPerformance();
        allServers = Arrays.asList(SERVER1, SERVER2, SERVER3, SERVER4);
    }

    @Test
    void testInitialServerSelection() {
        // 初始状态下，所有服务器都是未测试的，应该随机选择
        List<String> selected = performance.selectServers(allServers);
        assertNotNull(selected);
        assertFalse(selected.isEmpty());
        assertTrue(selected.size() <= 2);  // 默认选择2个服务器
        assertTrue(allServers.containsAll(selected));
    }

    @Test
    void testRttBasedSelection() {
        // 设置不同的RTT值
        performance.recordSuccess(SERVER1, 100);  // 最快
        performance.recordSuccess(SERVER2, 200);
        performance.recordSuccess(SERVER3, 300);
        performance.recordSuccess(SERVER4, 400);  // 最慢

        // 多次选择，验证是否倾向于选择RTT较低的服务器
        int server1Count = 0;
        int server4Count = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            List<String> selected = performance.selectServers(allServers);
            if (selected.contains(SERVER1)) server1Count++;
            if (selected.contains(SERVER4)) server4Count++;
        }

        // SERVER1应该被选择的次数明显多于SERVER4
        assertTrue(server1Count > server4Count);
        System.out.printf("Fast server selected: %d times, Slow server selected: %d times%n", 
            server1Count, server4Count);
    }

    @Test
    void testFailureHandling() throws InterruptedException {
        // 记录初始成功
        performance.recordSuccess(SERVER1, 100);

        // 记录多次失败
        for (int i = 0; i < 3; i++) {
            performance.recordFailure(SERVER1);
        }

        // 验证失败的服务器不会被立即选择
        List<String> selected = performance.selectServers(allServers);
        assertFalse(selected.contains(SERVER1));

        // 等待恢复时间
        TimeUnit.MILLISECONDS.sleep(31000);  // 等待超过30秒的恢复时间

        // 验证服务器可以被再次选择
        selected = performance.selectServers(allServers);
        assertTrue(allServers.containsAll(selected));
    }

    @Test
    void testActiveQueriesImpact() {
        // 记录初始RTT
        performance.recordSuccess(SERVER1, 100);
        performance.recordSuccess(SERVER2, 100);

        // 增加SERVER1的活跃查询数
        for (int i = 0; i < 5; i++) {
            performance.startQuery(SERVER1);
        }

        // SERVER2应该更容易被选中
        int server1Count = 0;
        int server2Count = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            List<String> selected = performance.selectServers(Arrays.asList(SERVER1, SERVER2));
            if (selected.contains(SERVER1)) server1Count++;
            if (selected.contains(SERVER2)) server2Count++;
        }

        // 由于活跃查询的影响，SERVER2应该被选择的次数更多
        assertTrue(server2Count > server1Count);
        System.out.printf("Busy server selected: %d times, Free server selected: %d times%n", 
            server1Count, server2Count);

        // 清理活跃查询
        for (int i = 0; i < 5; i++) {
            performance.endQuery(SERVER1);
        }
    }

    @Test
    void testRttBucketDistribution() {
        // 测试RTT值在不同范围的服务器分布
        performance.recordSuccess(SERVER1, 100);   // 应该在低RTT桶
        performance.recordSuccess(SERVER2, 2000);  // 应该在中间RTT桶
        performance.recordSuccess(SERVER3, 3900);  // 应该在高RTT桶

        // 多次选择，验证RTT分布
        int lowRttCount = 0;
        int midRttCount = 0;
        int highRttCount = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            List<String> selected = performance.selectServers(Arrays.asList(SERVER1, SERVER2, SERVER3));
            if (selected.contains(SERVER1)) lowRttCount++;
            if (selected.contains(SERVER2)) midRttCount++;
            if (selected.contains(SERVER3)) highRttCount++;
        }

        // 验证选择倾向
        assertTrue(lowRttCount > midRttCount);
        assertTrue(midRttCount > highRttCount);
        System.out.printf("Low RTT: %d, Mid RTT: %d, High RTT: %d%n", 
            lowRttCount, midRttCount, highRttCount);
    }

    @Test
    void testTimeDecay() throws InterruptedException {
        // 测试时间衰减对服务器选择的影响
        performance.recordSuccess(SERVER1, 100);
        performance.recordSuccess(SERVER2, 100);

        // 等待一段时间，让SERVER1的分数衰减
        TimeUnit.MILLISECONDS.sleep(61000);  // 等待超过1分钟

        // 为SERVER2记录新的成功
        performance.recordSuccess(SERVER2, 100);

        // SERVER2应该更容易被选中
        int server1Count = 0;
        int server2Count = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            List<String> selected = performance.selectServers(Arrays.asList(SERVER1, SERVER2));
            if (selected.contains(SERVER1)) server1Count++;
            if (selected.contains(SERVER2)) server2Count++;
        }

        // 由于时间衰减的影响，SERVER2应该被选择的次数更多
        assertTrue(server2Count > server1Count);
        System.out.printf("Old server selected: %d times, Recent server selected: %d times%n", 
            server1Count, server2Count);
    }
}
