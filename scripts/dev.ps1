# Vite development server; start the H2 backend in a separate terminal.
[CmdletBinding()]
param([string]$EnvFile = '.env.local')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'local-common.ps1')
Push-Location $projectRoot
try { Import-LocalEnvironment -Path $EnvFile -Required:($PSBoundParameters.ContainsKey('EnvFile')) }
finally { Pop-Location }
$backendPort = Get-LocalServerPort
if ($backendPort -eq 0) { throw '开发代理需要固定的 SERVER_PORT，不能使用随机端口 0。' }
$env:COURSE_DEV_API_TARGET = "http://127.0.0.1:$backendPort"
$bundledNode = Join-Path $projectRoot 'apps/server/target/frontend/node'
if (Test-Path -LiteralPath (Join-Path $bundledNode 'node.exe')) {
    $env:Path = "$bundledNode;$env:Path"
}
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw '请先运行 build.cmd，或安装 Node.js 24。'
}

Push-Location (Join-Path $projectRoot 'web')
try {
    if (-not (Test-Path -LiteralPath 'node_modules/.bin/vite.cmd')) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败。' }
    }
    Write-Host "请先在另一终端运行 start-local.cmd。Vite 将 /api 代理到后端 $backendPort 端口。"
    Write-Host '前端开发页面：http://localhost:5174；Ctrl+C 停止当前开发服务器。'
    & npm.cmd run dev
    if ($LASTEXITCODE -ne 0) { throw "前端开发服务器退出，退出码 $LASTEXITCODE。" }
} finally {
    Pop-Location
}
