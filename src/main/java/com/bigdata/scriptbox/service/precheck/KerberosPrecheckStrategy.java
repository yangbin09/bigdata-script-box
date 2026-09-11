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
 * <p>显示名：{@code "Kerberos"}（不带冒号后缀，保持向后兼容）。
 */
@Component
public class KerberosPrecheckStrategy implements PrecheckStrategy {

    private static final String TYPE = "kerberos";
    private static final String DISPLAY_NAME = "Kerberos";

    private final ScriptBoxProperties props;

    public KerberosPrecheckStrategy(ScriptBoxProperties props) {
        this.props = props;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> items(Script script, Tenant tenant) {
        // Kerberos 是整体检查，只有一项（tenant 自身）；返回 ["self"] 让上层触发一次 check。
        return List.of("self");
    }

    @Override
    public CheckOutcome check(String item, Script script, Tenant tenant) {
        if (tenant == null) return CheckOutcome.fail("self", "tenant not set");
        if (!Boolean.TRUE.equals(tenant.getEnabled()))
            return CheckOutcome.fail("self", "tenant disabled: " + tenant.getName());
        if (tenant.getPrincipal() == null || tenant.getPrincipal().isBlank())
            return CheckOutcome.fail("self", "tenant has no principal");
        if (!props.isMock()) {
            if (tenant.getKeytabPath() == null || tenant.getKeytabPath().isBlank())
                return CheckOutcome.fail("self", "keytab not configured");
            if (!Files.exists(Paths.get(tenant.getKeytabPath())))
                return CheckOutcome.fail("self", "keytab file missing");
        }
        return CheckOutcome.pass("self", "tenant=" + tenant.getName() + " principal=" + tenant.getPrincipal());
    }

    /** 历史显示名（不带 {@code :item} 后缀），供 PrecheckService 在汇总时覆盖。 */
    public static String displayName() {
        return DISPLAY_NAME;
    }
}
