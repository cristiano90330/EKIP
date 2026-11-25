# 启动或复用本机演示环境，依次检查 Docker、模型服务、业务容器和前端代理。

[CmdletBinding()]
param(
    [switch]$Build,
    [string]$RerankerModelPath = $env:EKIP_RERANKER_MODEL_PATH
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$docker = (Get-Command docker.exe -ErrorAction Stop).Source
$loginUrl = 'http://127.0.0.1:5173/login'

# 接收本机端口并尝试建立 TCP 连接。返回布尔值，供启动流程判断是否已有监听进程。
function Test-LocalListener([int]$Port) {
    $client = [Net.Sockets.TcpClient]::new()

    try {
        $client.Connect('127.0.0.1', $Port)
        return $true
    } catch [Net.Sockets.SocketException] {
        return $false
    } finally {
        $client.Dispose()
    }
}

# 读取 Docker 服务端类型，返回是否已就绪且可运行项目使用的 Linux 容器。
function Test-DockerEngine {
    try {
        $serverOs = & $docker info --format '{{.OSType}}' 2>$null
        return ($LASTEXITCODE -eq 0 -and $serverOs -eq 'linux')
    } catch {
        return $false
    }
}

# 读取指定健康端点的状态并与期望值比较。等待流程用布尔结果区分就绪和继续轮询。
function Test-ServiceStatus([string]$Url, [string]$Expected) {
    try {
        $result = Invoke-RestMethod -Uri $Url -TimeoutSec 2
        return $result.status -eq $Expected
    } catch {
        return $false
    }
}

# 按指定秒数轮询调用方的就绪探针。就绪时结束等待，超时时抛出包含服务名的错误。
function Wait-DemoReady([string]$Name, [int]$Seconds, [scriptblock]$Ready) {
    $timer = [Diagnostics.Stopwatch]::StartNew()

    do {
        if (& $Ready) { return }
        Start-Sleep -Seconds 1
    } while ($timer.Elapsed.TotalSeconds -lt $Seconds)
    throw "$Name was not ready after $Seconds seconds."
}

# 从显式配置、已有重排记录或模型目录取得 GGUF 路径，供本机 Ollama 和重排启动使用。
function Get-DemoRerankerModelPath([string]$ConfiguredPath) {
    if (-not [string]::IsNullOrWhiteSpace($ConfiguredPath)) { return $ConfiguredPath }

    foreach ($statePath in @('logs/local-reranker/service.json', 'tmp/local-reranker-process.json')) {
        if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { continue }
        try {
            $state = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($state.Model -is [string] -and $state.Model -and (Test-Path -LiteralPath $state.Model -PathType Leaf)) {
                return $state.Model
            }
        } catch { continue }
    }

    $modelRoot = if ($env:OLLAMA_MODELS) { $env:OLLAMA_MODELS } else { Join-Path $repoRoot 'models' }
    return (Join-Path $modelRoot 'qwen3-reranker-0.6b-q8_0.gguf')
}

Push-Location -LiteralPath $repoRoot
try {
    if (-not (Test-Path -LiteralPath '.env' -PathType Leaf)) {
        throw 'Repository .env is missing. See Quick Start in README.md.'
    }
    $logDir = (New-Item -ItemType Directory -Path 'logs/local-development' -Force).FullName

    Write-Host '[1/4] Docker Desktop'
    if (-not (Test-DockerEngine)) {
        if (-not (Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue)) {
            $desktop = Join-Path (Split-Path -Parent $docker) '..\..\Docker Desktop.exe'
            if (-not (Test-Path -LiteralPath $desktop -PathType Leaf)) {
                $desktop = Join-Path $env:ProgramFiles 'Docker\Docker\Docker Desktop.exe'
            }
            if (-not (Test-Path -LiteralPath $desktop -PathType Leaf)) {
                throw 'Docker Desktop was not found. Start its Linux engine and run this script again.'
            }
            Start-Process -FilePath (Resolve-Path -LiteralPath $desktop).Path -WindowStyle Hidden
        }
        Wait-DemoReady 'Docker Linux engine' 120 { Test-DockerEngine }
    }

    Write-Host '[2/4] Ollama and local reranker'
    $RerankerModelPath = Get-DemoRerankerModelPath $RerankerModelPath
    if (-not (Test-LocalListener 11434)) {
        $ollama = (Get-Command ollama.exe -ErrorAction Stop).Source
        $previousModels = $env:OLLAMA_MODELS
        try {
            $modelRoot = Split-Path -Parent $RerankerModelPath
            if (-not $previousModels -and $modelRoot -and (Test-Path -LiteralPath (Join-Path $modelRoot 'manifests') -PathType Container)) {
                $env:OLLAMA_MODELS = (Resolve-Path -LiteralPath $modelRoot).Path
            }
            Start-Process -FilePath $ollama -ArgumentList 'serve' -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $logDir 'ollama.out.log') `
                -RedirectStandardError (Join-Path $logDir 'ollama.err.log')
        } finally {
            $env:OLLAMA_MODELS = $previousModels
        }
    }
    Wait-DemoReady 'Ollama on 11434 (logs/local-development/ollama.err.log)' 45 {
        try {
            $ollamaTags = Invoke-RestMethod 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2
            $null -ne $ollamaTags.PSObject.Properties['models']
        } catch { $false }
    }
    $ollamaTags = Invoke-RestMethod 'http://127.0.0.1:11434/api/tags' -TimeoutSec 2
    foreach ($tag in @('ekip-qwen3:4b-instruct-8k', 'ekip-qwen3-embedding:0.6b-4k')) {
        if ($tag -notin $ollamaTags.models.name) {
            throw "Missing local Ollama model: $tag. See docs/model-management.md."
        }
    }
    if (-not (Test-LocalListener 8091)) {
        if (-not (Test-Path -LiteralPath $RerankerModelPath -PathType Leaf)) {
            throw 'Reranker GGUF not found. Use -RerankerModelPath or set EKIP_RERANKER_MODEL_PATH.'
        }
        $keyFile = Join-Path $repoRoot 'tmp/local-reranker-api-key.txt'
        if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf)) {
            throw 'The existing reranker key file is missing. See docs/model-management.md.'
        }
        & (Join-Path $PSScriptRoot 'start-local-reranker.ps1') -ModelPath $RerankerModelPath -ApiKeyFile $keyFile
    }
    Wait-DemoReady 'Local reranker on 8091' 30 { Test-ServiceStatus 'http://127.0.0.1:8091/health' 'ok' }

    Write-Host '[3/4] EKIP Compose services'
    $upArgs = @('compose', 'up', '-d')
    if ($Build) { $upArgs += '--build' }
    & $docker @upArgs backend minio-init
    if ($LASTEXITCODE -ne 0) { throw 'Compose failed to start EKIP.' }
    Wait-DemoReady 'MinIO bucket initialization' 30 {
        $initState = & $docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' ekip-minio-init
        $LASTEXITCODE -eq 0 -and $initState -eq 'exited 0'
    }
    Wait-DemoReady 'Java and AI services' 120 {
        (Test-ServiceStatus 'http://127.0.0.1:8080/actuator/health' 'UP') -and
            (Test-ServiceStatus 'http://127.0.0.1:8000/health' 'ok')
    }

    # Verify the configured Compose credential against the actual local service without printing it.
    $rerankKey = & $docker compose exec -T backend printenv MODEL_DEFAULT_RERANK_API_KEY
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rerankKey)) {
        throw 'Set MODEL_DEFAULT_RERANK_API_KEY in .env to match the local reranker.'
    }
    try {
        $probeBody = @{
            model = 'ekip-qwen3-reranker-0.6b'
            query = 'upload receipt'
            documents = @('Keep the electronic receipt.', 'The window opens in April.')
            top_n = 2
        } | ConvertTo-Json -Compress
        $probe = Invoke-RestMethod 'http://127.0.0.1:8091/v1/rerank' -Method Post -TimeoutSec 10 `
            -Headers @{ Authorization = "Bearer $rerankKey" } -ContentType 'application/json' -Body $probeBody
    } catch {
        if ($_.Exception.Response.StatusCode -in @(401, 403)) {
            throw 'The local reranker rejected MODEL_DEFAULT_RERANK_API_KEY. Check the existing key file and .env.'
        }
        throw 'The local reranker probe failed. Check logs/local-reranker.'
    }
    if (@($probe.results).Count -ne 2) {
        throw 'The local reranker did not return both probe scores.'
    }

    Write-Host '[4/4] Vite frontend'
    if (-not (Test-LocalListener 5173)) {
        Push-Location -LiteralPath (Join-Path $repoRoot 'frontend')
        try {
            $viteEntry = 'node_modules/vite/bin/vite.js'
            if (-not (Test-Path -LiteralPath $viteEntry -PathType Leaf)) {
                & npm.cmd ci
                if ($LASTEXITCODE -ne 0) { throw 'npm ci failed in frontend/.' }
            }
            $node = (Get-Command node.exe -ErrorAction Stop).Source
            $previousMock = $env:VITE_USE_MOCK
            try {
                $env:VITE_USE_MOCK = 'false'
                Start-Process -FilePath $node -WorkingDirectory (Get-Location).Path -WindowStyle Hidden `
                    -ArgumentList @($viteEntry, '--host', '127.0.0.1', '--strictPort') `
                    -RedirectStandardOutput (Join-Path $logDir 'vite.out.log') `
                    -RedirectStandardError (Join-Path $logDir 'vite.err.log')
            } finally {
                $env:VITE_USE_MOCK = $previousMock
            }
        } finally {
            Pop-Location
        }
    }
    Wait-DemoReady 'Vite and its API proxies (logs/local-development/vite.err.log)' 30 {
        (Test-ServiceStatus 'http://127.0.0.1:5173/api/actuator/health' 'UP') -and
            (Test-ServiceStatus 'http://127.0.0.1:5173/ai/health' 'ok')
    }
    $vite = Invoke-WebRequest 'http://127.0.0.1:5173/@vite/client' -UseBasicParsing -TimeoutSec 5
    if ($vite.Headers['Content-Type'] -notmatch 'javascript') {
        throw 'Port 5173 is not serving the Vite development frontend.'
    }
    $viteConfig = Invoke-WebRequest 'http://127.0.0.1:5173/src/config.ts' -UseBasicParsing -TimeoutSec 5
    if ($viteConfig.Content -notmatch '\bUSE_MOCK\b') {
        throw 'Port 5173 is not serving this project.'
    }
    if ($viteConfig.Content -match '"VITE_USE_MOCK"\s*:\s*"true"') {
        throw 'The running Vite server uses mock data. Stop it and run this script again.'
    }
    Write-Host "Ready: $loginUrl"
} finally {
    Pop-Location
}
