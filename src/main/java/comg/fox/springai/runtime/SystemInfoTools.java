package comg.fox.springai.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 本机信息工具：给模型提供「当前时间」与「本机网络/系统信息」。
 *
 * <p>为什么做成工具而不是全塞进系统提示词：时间几乎每轮都可能用到（所以放在
 * {@link RuntimeContextAdvisor} 里每轮注入），但 IP、主机名、系统版本只在被问到时才需要，
 * 每轮都塞进 prompt 既浪费 token，也把本机信息不必要地暴露给模型调用链。
 */
@Component
public class SystemInfoTools {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoTools.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RuntimeContextProperties properties;

    public SystemInfoTools(RuntimeContextProperties properties) {
        this.properties = properties;
    }

    @Tool(description = "获取本机的实时信息：当前时间、主机名、局域网 IP、操作系统、CPU 核数、Java 版本、进程已运行时长。"
            + "当用户询问本机 IP、我的 IP 地址、电脑配置、系统版本、主机名、内存/CPU 等信息时调用。")
    public String getLocalSystemInfo() {
        ZonedDateTime now = ZonedDateTime.now(properties.resolveZone());
        String week = now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);

        StringBuilder sb = new StringBuilder();
        sb.append("当前时间：").append(now.format(DATE_TIME))
          .append("（").append(week).append("，时区 ").append(now.getZone().getId()).append("）\n");
        sb.append("主机名：").append(localHostName()).append('\n');
        sb.append("局域网 IPv4：").append(localIpv4Addresses()).append('\n');
        sb.append("操作系统：").append(System.getProperty("os.name")).append(' ')
          .append(System.getProperty("os.version")).append(" (").append(System.getProperty("os.arch")).append(")\n");
        sb.append("CPU 核数：").append(Runtime.getRuntime().availableProcessors()).append('\n');
        sb.append("JVM：").append(System.getProperty("java.vendor")).append(' ')
          .append(System.getProperty("java.version")).append('\n');
        sb.append("进程已运行：").append(formatUptime());
        sb.append("\n说明：以上是「本机局域网地址」；如果用户问的是公网 IP，需说明本机无法直接得知，")
          .append("建议用在线查询服务确认，不要臆造。");
        return sb.toString();
    }

    @Tool(description = "获取服务器当前的精确日期和时间（精确到秒，含时区与星期）。"
            + "当用户询问现在几点、今天几号、今天星期几，且需要精确答复时调用。")
    public String getCurrentDateTime() {
        ZonedDateTime now = ZonedDateTime.now(properties.resolveZone());
        return now.format(DATE_TIME)
                + "（" + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA)
                + "，时区 " + now.getZone().getId() + "）";
    }

    private String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            // 某些环境（无 DNS / 受限容器）解析主机名会失败，退回环境变量
            String env = System.getenv("COMPUTERNAME");
            return env != null ? env : "（获取失败： " + e.getMessage() + "）";
        }
    }

    /** 列出所有启用网卡的 IPv4 地址，排除回环与虚拟网卡；取不到时给出可读提示 */
    private String localIpv4Addresses() {
        try {
            List<String> result = new ArrayList<>();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        result.add(addr.getHostAddress() + "（" + nic.getName() + "）");
                    }
                }
            }
            if (result.isEmpty()) {
                return "（未找到可用的局域网 IPv4 地址）";
            }
            return result.stream().distinct().collect(Collectors.joining("，"));
        } catch (Exception e) {
            log.warn("读取本机网卡失败: {}", e.getMessage());
            return "（读取网卡信息失败：" + e.getMessage() + "）";
        }
    }

    private String formatUptime() {
        long ms = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        Duration d = Duration.ofMillis(ms);
        return "%d 天 %d 小时 %d 分".formatted(d.toDays(), d.toHoursPart(), d.toMinutesPart());
    }
}
