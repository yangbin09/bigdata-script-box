package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.config.ScriptBoxProperties;
import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * Kerberos PreCheck 策略。
 *
 * <p>检查项：tenant 是否启用、是否配置 principal、非 mock 模式下是否配置 keytab 且文件存在。
 *
 * <p>这是"整体型"检查：没有 per-item 列表，只有一项（tenant 自身）。因此用一个
 * {@link #SELF} 哨兵项目触发一次 check，并覆写 {@link #displayName(String)} 让结果列表里
 * 显示为不带冒号后缀的 {@code "Kerberos"}（保持与历史 UI 的向后兼容）。
 *
 * <p>历史实现把 {@code "Kerberos"} 这个名字常量同时放在本类和
 * {@code PrecheckService} 的 {@code type.equals("kerberos")} 分支里，两处必须手动同步。
 * 现在只有一个来源。
 */
@Component
public class KerberosPrecheckStrategy implements PrecheckStrategy {

    /** 配置 key（{@code precheckConfigJson} 里的字段名）。 */
    public static final String KEY = "kerberos";
    /** 整体型检查的哨兵项目名。 */
    public static final String SELF = "self";
    /** 历史显示名（不带 {@code :item} 后缀）。 */
    public static final String DISPLAY_NAME = "Kerberos";

    private final ScriptBoxProperties props;

    public KerberosPrecheckStrategy(ScriptBoxProperties props) {
        this.props = props;
    }

    @Override
    public String configKey() {
        return KEY;
    }

    /** 整体检查：只有一项，返回 {@link #SELF} 让上层触发一次 check。 */
    @Override
    public List<String> items(Script script, Tenant tenant) {
        return List.of(SELF);
    }

    @Override
    public String displayName(String item) {
        return DISPLAY_NAME;
    }

    @Override
    public CheckOutcome check(String item, Script script, Tenant tenant) {
        if (tenant == null) return CheckOutcome.fail(SELF, "tenant not set");
        if (!Boolean.TRUE.equals(tenant.getEnabled()))
            return CheckOutcome.fail(SELF, "tenant disabled: " + tenant.getName());
        if (tenant.getPrincipal() == null || tenant.getPrincipal().isBlank())
            return CheckOutcome.fail(SELF, "tenant has no principal");
        if (!props.isMock()) {
            if (tenant.getKeytabPath() == null || tenant.getKeytabPath().isBlank())
                return CheckOutcome.fail(SELF, "keytab not configured");
            if (!Files.exists(Paths.get(tenant.getKeytabPath())))
                return CheckOutcome.fail(SELF, "keytab file missing");
        }
        return CheckOutcome.pass(SELF,
                "tenant=" + tenant.getName() + " principal=" + tenant.getPrincipal());
    }
}
