# Java DNS Resolver

一个用 Java 实现的高性能 DNS 解析器，支持递归查询和缓存。

## 特性

- 完整的递归 DNS 解析实现
- BIND 风格的服务器性能跟踪
- RTT 桶算法的智能服务器选择
- 多级缓存系统
- 支持 UDP DNS 服务器
- 并发查询处理
- EDNS0 支持

## 架构

- `DNSResolver`: 核心解析器实现
- `DNSServer`: DNS 服务器组件
- `ServerPerformance`: 服务器性能跟踪
- `DNSCache`: 缓存管理

## 构建和运行

### 前提条件

- Java 11 或更高版本
- Maven 3.6 或更高版本

### 构建

```bash
mvn clean package
```

### 运行

```bash
# 以 DNS 服务器模式运行（需要 root 权限）
sudo java -jar target/dns-resolver-1.0-SNAPSHOT.jar

# 测试查询
dig @localhost example.com A
dig @localhost example.com NS
dig @localhost example.com MX
```

## 配置

默认配置：
- 监听端口：53
- 线程池大小：32
- 缓存 TTL：300秒（5分钟）
- UDP 包大小：512字节

## 实现细节

### 递归解析
解析器从根服务器开始，逐级向下查询，直到找到权威答案。支持以下记录类型：
- A
- AAAA
- NS
- CNAME
- MX

### 服务器选择
使用 BIND 风格的 RTT 桶算法进行智能服务器选择：
- RTT 测量和平滑计算
- 动态服务器评分
- 失败检测和恢复
- 负载均衡

### 缓存系统
多级缓存实现：
- 记录缓存
- 否定缓存
- NS 记录缓存

## 贡献

欢迎提交 Issue 和 Pull Request！

## 许可证

MIT License
