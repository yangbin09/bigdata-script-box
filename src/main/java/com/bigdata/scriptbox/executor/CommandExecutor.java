package com.bigdata.scriptbox.executor;

/**
 * 子进程执行的唯一契约。
 *
 * <p><b>为什么需要这个接口</b>：项目里曾经有三处互相独立的 {@code ProcessBuilder}
 * 启动实现，语义完不一致 ——
 * <ul>
 *   <li>{@link ProcessRunner}（脚本执行）：有超时、双流并行 drain、整棵进程树回收；</li>
 *   <li>{@code SyntaxCheckService#tryBashN}：{@code p.waitFor()} <b>无超时</b>，
 *       单线程读流，无 finally destroy —— 而它是所有脚本写路径的强制关卡，
 *       还被 {@code /api/scripts/syntax-check} 以"随打随探"的频率调用；</li>
 *   <li>{@code TenantController#test}：同样无超时，还在 Controller 里做进程编排。</li>
 * </ul>
 *
 * <p>收敛到本接口后，"超时 + 输出 drain + 进程树回收"只有一份实现；调用方只需
 * 描述 {@link CommandSpec}。内部短命令把 {@link CommandSpec#registrySlot()} 设为
 * {@code false}，从而不占用用户脚本的并发执行槽位。
 *
 * <p>实现方的硬性约定：
 * <ol>
 *   <li>必须对 {@link CommandSpec#timeoutSeconds()} 施加硬超时，超时后销毁整棵进程树；</li>
 *   <li>stdout 与 stderr 必须各自被持续消费，否则管道缓冲满会导致子进程阻塞；</li>
 *   <li>无论成功、异常、超时还是取消，都必须在 finally 中回收进程与句柄。</li>
 * </ol>
 */
public interface CommandExecutor {

    /**
     * 执行一个子进程并等待其结束。
     *
     * @param spec 执行描述
     * @return 执行结果（含被上限截断后的 stdout / stderr 文本）
     * @throws java.io.IOException 进程无法启动（可执行文件不存在 / 权限不足）
     */
    CommandResult exec(CommandSpec spec) throws java.io.IOException;
}
