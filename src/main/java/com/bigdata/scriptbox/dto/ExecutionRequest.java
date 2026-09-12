package com.bigdata.scriptbox.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脚本执行请求 DTO。
 *
 * <p>封装前端「执行」抽屉、批量执行、场景执行、历史重跑四条路径都会用到的
 * 入参；同时也是内部 BatchService / ScenarioService 之间传递执行的载体。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@link #scriptId} — 要执行的脚本 ID；</li>
 *   <li>{@link #tenantId} — 使用的租户 ID；</li>
 *   <li>{@link #params} — 显式参数（会与 Preset 参数合并，{@code params} 优先）；</li>
 *   <li>{@link #presetId} — 可选的预设 ID；</li>
 *   <li>{@link #fileInputs} — 文件型输入，paramName → 服务端落盘路径；</li>
 *   <li>{@link #batchId} / {@link #batchRowIndex} — 由 BatchService 设置，便于按批次追踪；</li>
 *   <li>{@link #scenarioId} / {@link #scenarioStepNo} — 由 ScenarioService 设置，便于按场景追踪；</li>
 *   <li>{@link #confirmToken} — 危险脚本要求用户在前端输入 "CONFIRM" 才放行；</li>
 *   <li>{@link #bypassDangerousCheck} — 历史重跑时由 HistoricalRerunService 设置，跳过二次确认。</li>
 * </ul>
 */
@Data
public class ExecutionRequest {
    /** 脚本 ID。 */
    private Long scriptId;
    /** 租户 ID。 */
    private Long tenantId;
    /** 显式参数（key → string 值）。使用 LinkedHashMap 以保留前端传入顺序。 */
    private Map<String, String> params = new LinkedHashMap<>();
    /** 可选预设 ID；预设值会在执行前合并到 params 之下。 */
    private Long presetId;
    /** 可选文件型输入：参数名 → 服务端落盘后的绝对路径。 */
    private Map<String, String> fileInputs;
    /** 批次 ID（由 BatchService 写入）。 */
    private String batchId;
    /** 当前行在批次中的序号（0-based）。 */
    private Integer batchRowIndex;
    /** 场景 ID（由 ScenarioService 写入）。 */
    private Long scenarioId;
    /** 当前步骤在场景中的序号（1-based）。 */
    private Integer scenarioStepNo;
    /** V2: 执行危险脚本时前端必须在确认框中输入 "CONFIRM"，否则拒绝执行。
     *  dry-run 与历史重跑无需此 token。 */
    private String confirmToken;
    /** V2: 历史快照重跑时设为 true，跳过危险脚本的二次确认（原执行已经过授权）。 */
    private boolean bypassDangerousCheck;
    /** 历史快照重跑标记：为 true 时脚本路径落在 executionDir 内（临时副本），
     *  captureSnapshot 会跳过 scriptsRoot 路径校验。 */
    private boolean rerunSnapshot;
}