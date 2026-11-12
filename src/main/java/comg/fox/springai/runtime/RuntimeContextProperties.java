package comg.fox.springai.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/**
 * 运行时上下文配置，绑定 app.runtime。
 */
@Component
@ConfigurationProperties(prefix = "app.runtime")
public class RuntimeContextProperties {

    /** 是否向每次对话注入「当前时间」等运行时信息 */
    private boolean enabled = true;

    /** 时区，如 Asia/Shanghai；留空则用 JVM 默认时区 */
    private String zone = "";

    /** 解析后的时区；zone 配置非法时退回系统默认 */
    public ZoneId resolveZone() {
        if (zone == null || zone.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zone.trim());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
}
