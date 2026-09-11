package com.bigdata.scriptbox.service.precheck;

import com.bigdata.scriptbox.entity.Script;
import com.bigdata.scriptbox.entity.Tenant;

import java.util.List;
import java.util.Map;

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
 *   <li>实现本接口，标注 {@code @Component}；</li>
 *   <li>实现 {@link #type()} 返回稳定的字符串 key；</li>
 *   <li>实现 {@link #items(Script, Tenant)} 返回要检查的项目列表；</li>
 *   <li>实现 {@link #check(String, Script, Tenant)} 检查单个项目。</li>
 * </ol>
 * 这样 {@code PrecheckService.run()} 不需要任何改动。
 */
public interface PrecheckStrategy {

    /**
     * 策略类型字符串，对应 {@code Script.precheckConfigJson} 里的 key（如 {@code "kerberos"} /
     * {@code "commands"} / {@code "files"} / {@code "writableDirectories"}）。
     */
    String type();

    /**
     * 从脚本的 precheckConfigJson 中提取本策略关心的项目列表。返回空列表表示该策略无项目可检查。
     */
    List<String> items(Script script, Tenant tenant);

    /**
     * 检查单个项目；返回的 {@link CheckOutcome} 由调用方汇总到 {@code results} 数组。
     */
    CheckOutcome check(String item, Script script, Tenant tenant);

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
