#Requires -Version 5.1
<#
.SYNOPSIS
  一键构建并部署 bigdata-script-box 到远程服务器（替换 jar + 重启 + 健康检查 + 失败自动回滚）。

.DESCRIPTION
  流程：
    1. 本地 Maven 打包（含 Vite 前端构建）产出 target/script-box.jar
    2. 远端预检：记录数据基线（scripts 条数/哈希、data 文件数、当前静态资源）
    3. 备份远端现有 jar 与 data/ 目录（打时间戳归档）
    4. scp 上传新 jar 到 *.new，并在远端校验 SHA256（与本地一致才继续）
    5. 停旧进程 -> 换 jar -> 以 RunAs 用户重启
    6. 轮询 /api/system/info；失败则自动还原旧 jar 并重启，然后以非零码退出

  远端 bash 逻辑放在 deploy/remote/*.sh，本脚本只做占位符替换 + base64 传输，
  因此不存在引号/转义问题。前提是已配置 SSH 公钥免密登录；脚本不保存密码。

.PARAMETER SkipBuild
  跳过 Maven 打包，直接部署当前 target/script-box.jar（快速重发用）。

.PARAMETER SkipBackup
  跳过远端备份（不推荐，仅在你已自行备份时使用）。

.EXAMPLE
  .\deploy\deploy.ps1
  完整构建并部署。

.EXAMPLE
  .\deploy\deploy.ps1 -SkipBuild
  只把现有 jar 推上去（比如刚手动构建完）。

.EXAMPLE
  .\deploy\deploy.ps1 -Server 10.0.0.5 -Port 8080
  部署到另一台机器 / 另一个端口。
#>
[CmdletBinding()]
param(
    [string]$Server  = '8.163.99.17',
    [string]$SshUser = 'root',
    [string]$AppDir  = '/home/dev/workspace/bigdata-script-box',
    [string]$RunAs   = 'dev',
    [string]$JavaBin = '/usr/lib/jvm/jdk-17.0.15+6/bin/java',
    [string]$Shell   = '/usr/bin/bash',
    [int]$Port       = 80,
    [string]$SshKey  = "$env:USERPROFILE\.ssh\id_rsa",
    [string]$JarName = 'script-box.jar',
    [switch]$SkipBuild,
    [switch]$SkipBackup,
    [int]$HealthTimeoutSec = 90
)

# 刻意用 Continue：ssh/scp 会把主机密钥警告写 stderr，在 'Stop' 下会被
# PowerShell 当成终止性错误。所有失败路径都靠显式检查 $LASTEXITCODE。
$ErrorActionPreference = 'Continue'

$Target   = "${SshUser}@${Server}"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$LocalJar = Join-Path $RepoRoot "target\$JarName"
$Stamp    = Get-Date -Format 'yyyyMMdd-HHmmss'

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "  [OK] $m" -ForegroundColor Green }
function Warn($m) { Write-Host "  [!]  $m" -ForegroundColor Yellow }
function Die($m)  { Write-Host "  [X]  $m" -ForegroundColor Red; exit 1 }

$SshOpts = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20', '-o', 'StrictHostKeyChecking=accept-new')
if (Test-Path $SshKey) { $SshOpts += @('-i', $SshKey) }

# 读取 deploy/remote/<Name>，替换 __PLACEHOLDER__ 后返回
function Get-RemoteScript {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][hashtable]$Vars
    )
    $path = Join-Path $PSScriptRoot "remote\$Name"
    if (-not (Test-Path $path)) { Die "缺少远端脚本: $path" }
    $s = [System.IO.File]::ReadAllText($path)
    foreach ($k in $Vars.Keys) { $s = $s.Replace("__${k}__", [string]$Vars[$k]) }
    $left = [regex]::Matches($s, '__[A-Z][A-Z_]*__')
    if ($left.Count -gt 0) {
        $names = ($left | ForEach-Object { $_.Value } | Sort-Object -Unique) -join ', '
        Die "远端脚本 $Name 存在未替换的占位符: $names"
    }
    return $s
}

# 用 base64 传脚本，规避一切引号/编码问题
function Invoke-Remote {
    param(
        [Parameter(Mandatory)][string]$Script,
        [switch]$AllowFailure,
        [switch]$Quiet
    )
    $normalized = $Script -replace "`r`n", "`n"
    $b64  = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($normalized))
    $raw  = & ssh @SshOpts $Target "echo $b64 | base64 -d | bash" 2>&1
    $code = $LASTEXITCODE
    $text = ($raw | Out-String)
    if (-not $Quiet -and $text.Trim()) { Write-Host $text.TrimEnd() }
    if ($code -ne 0 -and -not $AllowFailure) { Die "远端命令失败（exit $code）" }
    return [pscustomobject]@{ Code = $code; Text = $text }
}

function Send-File {
    param([Parameter(Mandatory)][string]$Local, [Parameter(Mandatory)][string]$Remote)
    $o = @('-q', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=20')
    if (Test-Path $SshKey) { $o += @('-i', $SshKey) }
    $null = & scp @o $Local "${Target}:${Remote}" 2>&1
    if ($LASTEXITCODE -ne 0) { Die "scp 上传失败: $Local -> $Remote" }
}

# ---------- 0. 前置检查 ----------
Step "0/7 前置检查"
foreach ($exe in @('ssh', 'scp')) {
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) { Die "本机缺少 $exe" }
}
if (-not (Test-Path $SshKey)) { Warn "未找到私钥 $SshKey，将依赖 ssh-agent / 默认密钥" }

$probe = Invoke-Remote -Quiet -AllowFailure -Script "echo REMOTE_OK; hostname; exit 0"
if ($probe.Text -notmatch 'REMOTE_OK') {
    Die "无法免密登录 ${Target}。请先配置公钥：ssh-copy-id ${Target}"
}
$hostLine = (($probe.Text -split "`r?`n") | Where-Object { $_ -and $_.Trim() -ne 'REMOTE_OK' } | Select-Object -First 1)
Ok "SSH 免密登录正常（$($hostLine.Trim())）"

# ---------- 1. 构建 ----------
if (-not $SkipBuild) {
    Step "1/7 Maven 打包"
    $mvn = (Get-Command mvn -ErrorAction SilentlyContinue).Source
    if (-not $mvn) {
        $cand = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Recurse -Filter 'mvn.cmd' -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | Select-Object -First 1
        if ($cand) { $mvn = $cand.FullName; Warn "PATH 上无 mvn，改用 $mvn" }
    }
    if (-not $mvn) { Die "找不到 Maven。请安装 mvn，或先手动构建后用 -SkipBuild" }

    Push-Location $RepoRoot
    & $mvn -DskipTests clean package
    $mvnCode = $LASTEXITCODE
    Pop-Location
    if ($mvnCode -ne 0) { Die "Maven 构建失败（exit $mvnCode）" }
    Ok "构建完成"
} else {
    Step "1/7 跳过构建（-SkipBuild）"
}

if (-not (Test-Path $LocalJar)) { Die "找不到 $LocalJar" }
$localHash = (Get-FileHash $LocalJar -Algorithm SHA256).Hash.ToLower()
$localSize = (Get-Item $LocalJar).Length
Ok "本地 jar: $localSize bytes"
Write-Host "       SHA256=$localHash" -ForegroundColor DarkGray

$common = @{
    APPDIR  = $AppDir
    RUNAS   = $RunAs
    JAVABIN = $JavaBin
    PORT    = $Port
    SHELL   = $Shell
    JAR     = $JarName
}

# ---------- 2. 远端预检 + 数据基线 ----------
Step "2/7 远端预检与数据基线"
Invoke-Remote -Script (Get-RemoteScript -Name '00-preflight.sh' -Vars $common) | Out-Null

# ---------- 3. 备份 ----------
if (-not $SkipBackup) {
    Step "3/7 远端备份"
    $bv = $common.Clone(); $bv['STAMP'] = $Stamp
    Invoke-Remote -Script (Get-RemoteScript -Name '01-backup.sh' -Vars $bv) | Out-Null
    Ok "备份完成（时间戳 $Stamp）"
} else {
    Step "3/7 跳过备份（-SkipBackup）"
}
Invoke-Remote -Script "echo '$Stamp' > /tmp/scriptbox-deploy-ts; exit 0" | Out-Null

# ---------- 4. 上传 + 远端校验 ----------
Step "4/7 上传并校验"
$remoteNew = "${AppDir}/target/${JarName}.new"
Send-File -Local $LocalJar -Remote $remoteNew
Ok "scp 上传完成"

$vv = $common.Clone(); $vv['HASH'] = $localHash
Invoke-Remote -Script (Get-RemoteScript -Name '02-verify.sh' -Vars $vv) | Out-Null
Ok "SHA256 与本地一致"

# ---------- 5. 替换 + 重启 + 健康检查（失败自动回滚） ----------
Step "5/7 替换 jar 并重启"
$sv = $common.Clone(); $sv['TIMEOUT'] = $HealthTimeoutSec
$res = Invoke-Remote -AllowFailure -Script (Get-RemoteScript -Name '03-swap.sh' -Vars $sv)
if ($res.Code -ne 0) {
    if ($res.Text -match 'ROLLBACK_OK')   { Die "新版本健康检查失败，已自动回滚到旧版本。请查 script-box.out" }
    if ($res.Text -match 'ROLLBACK_FAIL') { Die "新版本启动失败且回滚未成功，服务可能不可用，请立即人工介入！" }
    Die "部署失败（exit $($res.Code)）"
}

# ---------- 6. 完成 ----------
Step "7/7 部署成功"
Ok "$localSize bytes 已生效"
Write-Host "  备份 jar : /home/${RunAs}/backup-script-box.jar.${Stamp}" -ForegroundColor DarkGray
Write-Host "  备份 data: /home/${RunAs}/data-backup-${Stamp}.tar.gz" -ForegroundColor DarkGray
Write-Host "  访问地址 : http://${Server}/" -ForegroundColor DarkGray

# 显式 exit 0：native 命令（npm/ssh）往 stderr 写字会让 PowerShell 把退出码
# 置为 1，即使部署完全成功。CI / 脚本化调用需要确定的退出码。
exit 0
