function Initialize-LocalJava {
    if ($env:JAVA_HOME) {
        $javaBin = Join-Path $env:JAVA_HOME 'bin'
        if (-not (Test-Path -LiteralPath (Join-Path $javaBin 'javac.exe'))) {
            throw 'JAVA_HOME 必须指向完整 JDK 21 的安装目录。'
        }
        $env:Path = "$javaBin;$env:Path"
    }
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw '未找到 Java，请安装 JDK 21 并配置 JAVA_HOME。'
    }
    # Windows PowerShell 5.1 wraps native stderr as ErrorRecord objects.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $versionOutput = (& java -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($LASTEXITCODE -ne 0 -or $versionOutput -notmatch 'version "21\.') {
        throw '本项目使用 JDK 21，请将 JAVA_HOME 指向 JDK 21。'
    }
    if (-not $env:JAVA_HOME) {
        try {
            $ErrorActionPreference = 'Continue'
            $settings = (& java -XshowSettings:properties -version 2>&1 | Out-String)
        } finally { $ErrorActionPreference = $previousPreference }
        if ($LASTEXITCODE -ne 0 -or $settings -notmatch '(?m)^\s*java.home\s*=\s*(.+?)\s*$') {
            throw '无法确定 JDK 21 目录，请显式配置 JAVA_HOME。'
        }
        $detectedHome = $Matches[1].Trim()
        if (-not (Test-Path -LiteralPath (Join-Path $detectedHome 'bin/javac.exe'))) {
            throw 'Java 运行环境不包含 javac，请配置完整 JDK 21。'
        }
        $env:JAVA_HOME = $detectedHome
        $env:Path = "$(Join-Path $detectedHome 'bin');$env:Path"
    }
}

function Import-LocalEnvironment([string]$Path, [switch]$Required) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        if ($Required) { throw '指定的环境文件不存在；请核对 -EnvFile，启动已停止。' }
        return
    }
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    # Validate the whole file before applying any setting.
    $entries = @()
    foreach ($line in [System.IO.File]::ReadAllLines($resolvedPath)) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            throw '本地环境文件格式错误，必须使用 NAME=value；不会执行其中的命令。'
        }
        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if ($name -in @('HOME', 'USERPROFILE', 'CODEX_HOME', 'PATH', 'JAVA_HOME')) {
            throw "本地环境文件不允许覆盖 $name。"
        }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $entries += [pscustomobject]@{ Name = $name; Value = $value }
    }
    foreach ($entry in $entries) {
        $name = $entry.Name
        $value = $entry.Value
        # Explicit process configuration takes precedence over the local defaults.
        if (-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            continue
        }
        # .NET 9+ preserves an empty string as an existing variable. Spring then sees
        # an explicit empty DB_URL/SERVER_PORT rather than applying its default.
        if ([string]::IsNullOrEmpty($value)) {
            # The environment provider avoids PowerShell converting null to an empty string.
            if (Test-Path -LiteralPath "Env:$name") { Remove-Item -LiteralPath "Env:$name" }
        } else {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Get-LocalJwtSecret([string]$ProjectRoot) {
    if ($env:JWT_SECRET) { return $env:JWT_SECRET }
    $keyFile = Join-Path $ProjectRoot '.local/jwt-secret'
    New-Item -ItemType Directory -Path (Split-Path $keyFile -Parent) -Force | Out-Null
    if (-not (Test-Path -LiteralPath $keyFile)) {
        $bytes = New-Object byte[] 48
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $stream = $null
        try {
            # A simultaneous preparation/start must never overwrite an existing key.
            $stream = [System.IO.File]::Open($keyFile, [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            $encoded = [System.Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($bytes))
            $stream.Write($encoded, 0, $encoded.Length)
        } catch [System.IO.IOException] {
            throw '本地签名密钥文件正被占用或无法创建；请结束重复准备/启动后重试，现有密钥未覆盖。'
        } finally {
            if ($null -ne $stream) { $stream.Dispose() }
        }
    }
    $secret = [System.IO.File]::ReadAllText($keyFile).Trim()
    if ($secret.Length -lt 32) { throw '本地签名密钥文件无效；请核对既有配置，不要删除业务库。' }
    return $secret
}

function Get-LocalServerPort {
    if (-not $env:SERVER_PORT) { return 8088 }
    $port = 0
    if (-not [int]::TryParse($env:SERVER_PORT, [ref]$port) -or $port -lt 0 -or $port -gt 65535) {
        throw 'SERVER_PORT 必须为 0—65535 的整数；0 仅用于随机端口测试。'
    }
    return $port
}
