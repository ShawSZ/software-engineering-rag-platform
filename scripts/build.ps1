[CmdletBinding()]
# RunTests includes course HTTP/H2 tests; upstream container tests stay opt-in.
param([switch]$RunTests)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'local-common.ps1')
Initialize-LocalJava
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven 未安装或未加入 PATH。'
}

Push-Location $projectRoot
try {
    $mavenArgs = @('-B', '-U', '-pl', 'apps/server', '-am', 'clean', 'package', '-Pbundle')
    if ($RunTests) {
        $mavenArgs += '-Dtest=*Test,!*IntegrationTest'
        $mavenArgs += '-Dsurefire.failIfNoSpecifiedTests=false'
    } else {
        $mavenArgs += '-DskipTests'
    }
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败，退出码 $LASTEXITCODE。" }
    Write-Host '构建成功。运行 .\scripts\start-local.ps1 启动 H2 单机版本。'
} finally {
    Pop-Location
}
