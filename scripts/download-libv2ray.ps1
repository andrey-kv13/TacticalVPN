$ErrorActionPreference = "Stop"
$libsDir = Join-Path $PSScriptRoot "..\app\libs"
New-Item -ItemType Directory -Force -Path $libsDir | Out-Null
$url = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.7.31/libv2ray.aar"
$dest = Join-Path $libsDir "libv2ray.aar"
Write-Host "Downloading libv2ray.aar..."
Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing
Write-Host "Saved to $dest"
