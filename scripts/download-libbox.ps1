$ErrorActionPreference = "Stop"
$libsDir = Join-Path $PSScriptRoot "..\app\libs"
New-Item -ItemType Directory -Force -Path $libsDir | Out-Null
$url = "https://raw.githubusercontent.com/singbox-android/libbox/main/libbox.aar"
$dest = Join-Path $libsDir "libbox.aar"
Write-Host "Downloading libbox.aar..."
Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
Write-Host "Saved to $dest"
