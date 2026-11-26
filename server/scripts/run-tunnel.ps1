# Держит туннель ngrok к серверу и перезапускает его при обрыве. Лог: server\logs\tunnel.log
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
. "$root\config.ps1"   # там $NgrokDomain
New-Item -ItemType Directory -Force "$root\logs" | Out-Null
$ngrok = (Get-Command ngrok -ErrorAction SilentlyContinue).Source
if (-not $ngrok) { $ngrok = "$env:LOCALAPPDATA\Microsoft\WinGet\Links\ngrok.exe" }
if (-not (Test-Path $ngrok)) {
    # winget иногда не создаёт ярлык в Links — ищем сам exe в папке пакета
    $ngrok = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter ngrok.exe -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
while ($true) {
    "$(Get-Date -Format s) старт туннеля $NgrokDomain" | Out-File -Append -Encoding utf8 "$root\logs\tunnel.log"
    & $ngrok http 127.0.0.1:8787 --url "https://$NgrokDomain" --log stdout --log-level warn 2>&1 |
        ForEach-Object { "$_" } | Out-File -Append -Encoding utf8 "$root\logs\tunnel.log"
    Start-Sleep -Seconds 10
}
