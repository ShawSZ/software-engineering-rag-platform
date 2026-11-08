[CmdletBinding()]
param([string]$EnvFile = '.env.local')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'local-common.ps1')
Initialize-LocalJava

Push-Location $projectRoot
try {
    Import-LocalEnvironment -Path $EnvFile -Required:($PSBoundParameters.ContainsKey('EnvFile'))
    $port = Get-LocalServerPort
    $jars = @(Get-ChildItem -LiteralPath 'apps/server/target' -Filter 'fast-knowledge-server-*.jar' -ErrorAction SilentlyContinue)
    if ($jars.Count -ne 1) { throw '未找到唯一的可执行 JAR，请先运行 .\scripts\build.ps1。' }

    $env:JWT_SECRET = Get-LocalJwtSecret $projectRoot

    if ($port -eq 0) { Write-Host 'H2 单机模式：随机端口，实际地址见 Tomcat 启动日志；Ctrl+C 停止。' }
    else { Write-Host "H2 单机模式：http://localhost:$port/；Ctrl+C 停止。" }
    & java -jar $jars[0].FullName '--spring.profiles.active=bundle,h2'
    if ($LASTEXITCODE -ne 0) { throw "应用退出，退出码 $LASTEXITCODE。" }
} finally {
    Pop-Location
}
