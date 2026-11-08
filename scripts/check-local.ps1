[CmdletBinding()]
param([string]$EnvFile = '.env.local', [string]$BaseUrl)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'local-common.ps1')
Push-Location $projectRoot
try {
    if (-not $BaseUrl) {
        Import-LocalEnvironment -Path $EnvFile -Required:($PSBoundParameters.ContainsKey('EnvFile'))
        $port = Get-LocalServerPort
        if ($port -eq 0) { throw '随机端口请通过 -BaseUrl 指定日志中的实际地址。' }
        $BaseUrl = "http://localhost:$port"
    }
    $address = $null
    if (-not [Uri]::TryCreate($BaseUrl, [UriKind]::Absolute, [ref]$address) -or
            $address.Scheme -notin @('http', 'https') -or $address.UserInfo -or
            $address.AbsolutePath -ne '/' -or $address.Query -or $address.Fragment) {
        throw 'BaseUrl 必须为不含凭据、路径或查询参数的 http(s) 服务地址。'
    }
    $base = $BaseUrl.TrimEnd('/')
    Add-Type -AssemblyName System.Net.Http
    function Read-DeploymentResponse([string]$RelativePath) {
        $handler = New-Object System.Net.Http.HttpClientHandler
        $handler.AllowAutoRedirect = $false
        $client = New-Object System.Net.Http.HttpClient($handler)
        $client.Timeout = [TimeSpan]::FromSeconds(10)
        try {
            $response = $client.GetAsync($base + $RelativePath).GetAwaiter().GetResult()
            try {
                return [pscustomobject]@{ Status = [int]$response.StatusCode;
                    Content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() }
            } finally { $response.Dispose() }
        } catch {
            throw '服务无法连接；请确认启动完成、端口和网络访问路径。'
        } finally { $client.Dispose(); $handler.Dispose() }
    }
    $homepage = Read-DeploymentResponse '/'
    if ($homepage.Status -ne 200 -or $homepage.Content -notmatch '<div id="app">' -or
            $homepage.Content -notmatch '<script[^>]+src="(?<asset>/assets/[^"?]+\.js)"') {
        throw '首页没有返回有效前端；请重新构建 bundle 并检查实际运行的 JAR。'
    }
    $asset = $Matches['asset']
    if ($asset -notmatch '^/assets/[A-Za-z0-9_./-]+\.js$') { throw '首页静态资源地址不合法。' }
    $assetResponse = Read-DeploymentResponse $asset
    $health = Read-DeploymentResponse '/api/actuator/health'
    $unauthorized = Read-DeploymentResponse '/api/documents'
    $unknown = Read-DeploymentResponse '/deployment-check-missing-page'
    $missingAsset = Read-DeploymentResponse '/assets/deployment-check-missing.js'
    if ($assetResponse.Status -ne 200 -or $assetResponse.Content -match '<div id="app">' -or
            $health.Status -ne 200 -or ($health.Content | ConvertFrom-Json).status -ne 'UP' -or
            $unauthorized.Status -ne 401 -or ($unauthorized.Content | ConvertFrom-Json).code -ne 40101 -or
            $unknown.Status -ne 404 -or $missingAsset.Status -ne 404) {
        throw "部署检查未通过：资源=$($assetResponse.Status)，健康=$($health.Status)，未登录接口=$($unauthorized.Status)，未知页面=$($unknown.Status)，缺失资源=$($missingAsset.Status)。"
    }
    [pscustomobject]@{ Passed = $true; Homepage = 200; StaticAsset = 200; Health = 'UP';
        UnauthenticatedApi = 401; UnknownPage = 404; MissingAsset = 404 } | ConvertTo-Json
} finally { Pop-Location }
