package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;

import java.util.List;

/**
 * PreCheck 策略接口。
 *
 * <p>背景：原 {@code PrecheckService} 在同一个类里硬编码 4 类检查（Kerberos、命令存在、
 * 文件存在、目录可写），每类检查是 if-else 块。新增检查类型时必须改原类，扩展性差。
 *
 * <p>本接口将每类检查拆成独立 Spring Bean，由 {@link PrecheckStrategyRegistry} 自动收集；
 * {@code PrecheckService} 只负责遍历 + 汇总结果，不再写任何 if-else 分支。
 *
 * <p>如何新增一种 PreCheck：
 * <ol>
 *   <li>实现本接口（列表型检查建议直接继承 {@link AbstractJsonListPrecheckStrategy}），
 *       标注 {@code @Component}；</li>
 *   <li>实现 {@link #configKey()} 返回 {@code precheckConfigJson} 里的稳定 key；</li>
 *   <li>实现 {@link #check(String, Script, Tenant)} 检查单个项目；</li>
 *   <li>需要自定义展示名时覆写 {@link #displayName(String)}。</li>
 * </ol>
 * 这样 {@code PrecheckService} 不需要任何改动。
 */
public interface PrecheckStrategy {

    /**
     * 策略 key，对应 {@code Script.precheckConfigJson} 里的字段名（如 {@code "kerberos"} /
     * {@code "commands"} / {@code "files"} / {@code "writableDirectories"}）。
     */
    String configKey();

    /**
     * 从脚本的 precheckConfigJson 中提取本策略关心的项目列表。返回空列表表示该策略无项目可检查。
     */
    List<String> items(Script script, Tenant tenant);

    /**
     * 检查单个项目；返回的 {@link CheckOutcome} 由调用方汇总到结果列表。
     */
    CheckOutcome check(String item, Script script, Tenant tenant);

    /**
     * 单条检查在结果列表里的展示名。
     *
     * <p>默认是 {@code <configKey>:<item>}（如 {@code commands:spark-sql}）；整体型检查
     * （Kerberos 这种只有一个"检查租户本身"的策略）覆写本方法返回不带冒号后缀的短名。
     *
     * <p>历史实现把这套规则硬写在 {@code PrecheckService} 里的
     * {@code type.equals("kerberos")} 分支中，导致显示名有两处来源、必须手动同步。
     */
    default String displayName(String item) {
        return item == null ? configKey() : configKey() + ":" + item;
    }

    /** 单次检查的结果。 */
    record CheckOutcome(String item, boolean ok, String message) {
        public static CheckOutcome pass(String item, String message) {
            return new CheckOutcome(item, true, message);
        }

        public static CheckOutcome fail(String item, String message) {
            return new CheckOutcome(item, false, message);
        }
    }
}
