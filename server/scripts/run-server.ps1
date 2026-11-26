# Запускает сервер и перезапускает его, если он упал. Лог: server\logs\server.log
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
New-Item -ItemType Directory -Force "$root\logs" | Out-Null
$uv = (Get-Command uv -ErrorAction SilentlyContinue).Source
if (-not $uv) { $uv = "$env:LOCALAPPDATA\Microsoft\WinGet\Links\uv.exe" }
if (-not (Test-Path $uv)) {
    # winget иногда не создаёт ярлык в Links — ищем сам exe в папке пакета
    $uv = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter uv.exe -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
$env:PYTHONIOENCODING = 'utf-8'
while ($true) {
    "$(Get-Date -Format s) старт сервера" | Out-File -Append -Encoding utf8 "$root\logs\server.log"
    & $uv run --no-dev uvicorn app.main:app --host 127.0.0.1 --port 8787 --proxy-headers 2>&1 |
        ForEach-Object { "$_" } | Out-File -Append -Encoding utf8 "$root\logs\server.log"
    "$(Get-Date -Format s) сервер остановился (код $LASTEXITCODE), перезапуск через 5 сек" | Out-File -Append -Encoding utf8 "$root\logs\server.log"
    Start-Sleep -Seconds 5
}
