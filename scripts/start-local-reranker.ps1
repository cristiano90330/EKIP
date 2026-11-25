# 启动本机 llama.cpp 重排实例，配置模型、访问密钥文件和就绪探针，记录实例日志。

param(
    [Parameter(Mandatory = $true)]
    [string]$ModelPath,
    [ValidateRange(1024, 65535)]
    [int]$Port = 8091,
    [ValidatePattern('^[A-Za-z0-9,_:-]+$')]
    [string]$Device = 'Vulkan0',
    [string]$ApiKeyFile = 'tmp/local-reranker-api-key.txt',
    [string]$LlamaServerPath = 'llama-server.exe'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$modelFile = (Resolve-Path -LiteralPath $ModelPath).Path
$serverFile = (Get-Command $LlamaServerPath -ErrorAction Stop).Source
$keyFile = if ([IO.Path]::IsPathRooted($ApiKeyFile)) {
    [IO.Path]::GetFullPath($ApiKeyFile)
} else {
    [IO.Path]::GetFullPath((Join-Path $repoRoot $ApiKeyFile))
}

# An occupied port belongs to its current listener, even if its identity is unknown.
$portProbe = [Net.Sockets.TcpClient]::new()
try {
    $portProbe.Connect('127.0.0.1', $Port)
    throw "Port $Port already has a listener."
} catch [Net.Sockets.SocketException] {
    if ($_.Exception.SocketErrorCode -ne [Net.Sockets.SocketError]::ConnectionRefused) { throw }
} finally {
    $portProbe.Dispose()
}

$keyDirectory = Split-Path -Parent $keyFile
New-Item -ItemType Directory -Force -Path $keyDirectory | Out-Null
if (-not (Test-Path -LiteralPath $keyFile)) {
    $keyBytes = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($keyBytes)
    } finally {
        $generator.Dispose()
    }
    [IO.File]::WriteAllText($keyFile, [Convert]::ToBase64String($keyBytes), [Text.UTF8Encoding]::new($false))
}
if ([string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($keyFile))) {
    throw 'The API key file is empty.'
}

$logDirectory = Join-Path $repoRoot 'logs\local-reranker'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
$runTag = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$stdout = Join-Path $logDirectory "$runTag-stdout.log"
$stderr = Join-Path $logDirectory "$runTag-stderr.log"
# A relative key filename avoids llama.cpp's Windows handling of non-ASCII absolute paths.
$arguments = @(
    '--reranking', '-m', ('"' + $modelFile + '"'), '--alias', 'ekip-qwen3-reranker-0.6b',
    '--host', '0.0.0.0', '--port', "$Port", '--threads', '4', '--ctx-size', '2048',
    '--parallel', '1', '--gpu-layers', '99', '--device', $Device, '--split-mode', 'none',
    '--batch-size', '2048', '--ubatch-size', '2048', '--flash-attn', 'on', '--no-webui',
    '--api-key-file', ('"' + [IO.Path]::GetFileName($keyFile) + '"'), '--log-verbosity', '1'
)
$process = Start-Process -FilePath $serverFile -ArgumentList $arguments -WorkingDirectory $keyDirectory `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
@{
    Pid = $process.Id
    Port = $Port
    Executable = $serverFile
    Model = $modelFile
    ApiKeyFile = $keyFile
    Stderr = $stderr
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $logDirectory 'service.json') -Encoding utf8

$timer = [Diagnostics.Stopwatch]::StartNew()
while ($timer.Elapsed.TotalSeconds -lt 60) {
    $process.Refresh()
    if ($process.HasExited) {
        throw "Reranker exited with code $($process.ExitCode). Diagnostic file is $stderr"
    }
    try {
        $health = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 1
        if ($health.status -eq 'ok') {
            Write-Output "Local reranker ready, pid=$($process.Id), port=$Port"
            return
        }
    } catch {
        # Loading is bounded by the startup budget, and does not trigger inference.
    }
    Start-Sleep -Milliseconds 250
}
throw "Reranker startup exceeded 60 seconds. Diagnostic file is $stderr"
