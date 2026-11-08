[CmdletBinding()]
param([string]$EnvFile = '.env.local')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'local-common.ps1')
Initialize-LocalJava

function ConvertTo-JavaPropertyValue([string]$Value) {
    $result = New-Object System.Text.StringBuilder
    foreach ($character in $Value.ToCharArray()) {
        $number = [int]$character
        if ($number -eq 92) { [void]$result.Append('\\') }
        elseif ($number -eq 32) { [void]$result.Append('\ ') }
        elseif ($number -lt 32 -or $number -gt 126) { [void]$result.Append(('\u{0:x4}' -f $number)) }
        else { [void]$result.Append($character) }
    }
    return $result.ToString()
}

Push-Location $projectRoot
try {
    Import-LocalEnvironment -Path $EnvFile -Required:($PSBoundParameters.ContainsKey('EnvFile'))
    [void](Get-LocalServerPort)
    $webDist = Join-Path $projectRoot 'web/dist'
    if (-not (Test-Path -LiteralPath (Join-Path $webDist 'index.html') -PathType Leaf)) {
        throw '缺少已构建前端，请先运行 scripts/build.cmd，再准备 IDEA。'
    }
    $classpath = Join-Path $projectRoot '.local/idea-web-classpath'
    New-Item -ItemType Directory -Path $classpath -Force | Out-Null
    $staticPath = Join-Path $classpath 'static'
    $existing = Get-Item -LiteralPath $staticPath -Force -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        if ($existing.LinkType -ne 'Junction' -or
                [System.IO.Path]::GetFullPath([string]$existing.Target[0]).TrimEnd('\') -ne $webDist.TrimEnd('\')) {
            throw 'IDEA 前端类路径已存在且不是本项目 dist 的目录联接；保留原目录，请核对配置。'
        }
    } else {
        New-Item -ItemType Junction -Path $staticPath -Target $webDist | Out-Null
    }
    $names = @('SERVER_PORT', 'DB_URL', 'DB_USER', 'DB_PASSWORD', 'STORAGE_PROVIDER',
        'CACHE_PROVIDER', 'STORAGE_LOCAL_DIR', 'JWT_SECRET', 'APP_ADMIN_USERNAME',
        'APP_ADMIN_PASSWORD', 'DEEPSEEK_API_URL', 'DEEPSEEK_API_KEY', 'DEEPSEEK_MODEL', 'CORS_ORIGINS')

    $env:JWT_SECRET = Get-LocalJwtSecret $projectRoot

    $lines = @('# Local IDEA runtime configuration. Never commit this file.')
    foreach ($name in $names) {
        $value = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ($null -ne $value) { $lines += $name + '=' + (ConvertTo-JavaPropertyValue $value) }
    }
    [System.IO.File]::WriteAllLines((Join-Path $projectRoot '.local/idea.properties'), $lines,
        [System.Text.Encoding]::ASCII)
    $runFolder = Join-Path $projectRoot '.run'
    $runFile = Join-Path $runFolder 'KnowledgeApplication_IDEA.run.xml'
    if (-not (Test-Path -LiteralPath $runFile)) {
        New-Item -ItemType Directory -Path $runFolder -Force | Out-Null
        $jdkPath = [System.Security.SecurityElement]::Escape($env:JAVA_HOME)
        $marker = '$PROJECT_DIR$'
        $configuration = @"
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="KnowledgeApplication (IDEA)" type="SpringBootApplicationConfigurationType" factoryName="Spring Boot">
    <option name="ALTERNATIVE_JRE_PATH" value="$jdkPath" />
    <option name="ALTERNATIVE_JRE_PATH_ENABLED" value="true" />
    <option name="ACTIVE_PROFILES" value="bundle,h2" />
    <option name="SHORTEN_COMMAND_LINE" value="ARGS_FILE" />
    <option name="SPRING_BOOT_MAIN_CLASS" value="com.fast.knowledge.KnowledgeApplication" />
    <option name="VM_PARAMETERS" value="-Dspring.config.additional-location=optional:file:./.local/idea.properties" />
    <option name="WORKING_DIRECTORY" value="file://$marker" />
    <module name="fast-knowledge-server" />
    <method v="2"><option name="Make" enabled="true" /></method>
    <classpathModifications><entry path="$marker/.local/idea-web-classpath" /></classpathModifications>
  </configuration>
</component>
"@
        [System.IO.File]::WriteAllText($runFile, $configuration, (New-Object System.Text.UTF8Encoding($false)))
    }
    Write-Host 'IDEA 本地环境已准备；选择 KnowledgeApplication (IDEA) 后运行。'
} finally {
    Pop-Location
}
