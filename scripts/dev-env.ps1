<#
.SYNOPSIS
  secrets.local.env 를 읽어 환경변수로 올리고, 이어서 명령을 실행한다.

.DESCRIPTION
  $env:SOLAPI_API_KEY="..." 를 창마다 다시 치지 않으려고 만든 스크립트다.
  PowerShell 창에 직접 넣은 값은 창을 닫으면 사라진다 — 실제로 그래서
  어제 통했던 설정이 오늘 없어져 있었다.

  값은 화면에 찍지 않는다. 어느 이름이 채워졌는지만 보여 준다.

.EXAMPLE
  # 값만 올리고 현재 창에 남긴다
  .\scripts\dev-env.ps1

.EXAMPLE
  # 값을 올린 채로 앱을 띄운다
  .\scripts\dev-env.ps1 -Run '.\gradlew bootRun'

.EXAMPLE
  # 실발송 스모크 테스트 (★ 실제로 돈이 나가고 사람에게 도착한다)
  .\scripts\dev-env.ps1 -Run '.\gradlew test --tests *SolapiSmokeTest'
#>
[CmdletBinding()]
param(
    # 읽어 들일 파일. 기본값은 저장소 루트의 secrets.local.env
    [string] $File,

    # 값을 올린 뒤 실행할 명령. 비우면 환경변수만 올리고 끝낸다
    [string] $Run
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $File) { $File = Join-Path $repoRoot 'secrets.local.env' }

if (-not (Test-Path -LiteralPath $File)) {
    Write-Host "설정 파일이 없다: $File" -ForegroundColor Yellow
    Write-Host "예시를 복사해서 값을 채운다:" -ForegroundColor Yellow
    Write-Host "  Copy-Item secrets.local.env.example secrets.local.env"
    exit 1
}

$filled  = @()
$blank   = @()

foreach ($line in Get-Content -LiteralPath $File -Encoding UTF8) {
    $text = $line.Trim()
    if ($text -eq '' -or $text.StartsWith('#')) { continue }

    $split = $text.IndexOf('=')
    if ($split -lt 1) { continue }

    $name  = $text.Substring(0, $split).Trim()
    $value = $text.Substring($split + 1).Trim()

    # 따옴표로 감싼 값도 받아 준다. 안에 = 가 있어도 앞의 첫 = 만 구분자다
    if ($value.Length -ge 2) {
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }

    # 빈 값도 올린다. 지운 것을 지운 대로 반영해야 한다 —
    # 안 올리면 앞서 켜 둔 값이 남아 테스트 수신번호가 살아 있는 채로 실발송이 된다
    Set-Item -Path "env:$name" -Value $value

    if ($value -ne '') { $filled += $name } else { $blank += $name }
}

# 값은 절대 찍지 않는다 (규칙 10)
Write-Host "채워짐 ($($filled.Count)):" -ForegroundColor Green
foreach ($n in $filled) { Write-Host "  $n" }
if ($blank.Count -gt 0) {
    Write-Host "비어 있음 ($($blank.Count)):" -ForegroundColor DarkGray
    foreach ($n in $blank) { Write-Host "  $n" -ForegroundColor DarkGray }
}

# ── 켜기 전에 확인해야 하는 것들 ───────────────────────────────
$missing = @()
foreach ($n in @('SOLAPI_API_KEY','SOLAPI_API_SECRET','SOLAPI_PF_ID','SOLAPI_FROM')) {
    if (-not [Environment]::GetEnvironmentVariable($n)) { $missing += $n }
}

if ($env:NOTIFY_PROVIDER -eq 'solapi' -and $missing.Count -gt 0) {
    Write-Host ''
    Write-Host "중단: NOTIFY_PROVIDER=solapi 인데 자격증명이 비어 있다." -ForegroundColor Red
    Write-Host "  빠진 값: $($missing -join ', ')" -ForegroundColor Red
    Write-Host "  이대로 띄우면 발송이 예외로 떨어지고, outbox 가 8회 재시도한 뒤" -ForegroundColor Red
    Write-Host "  DEAD 로 쌓는다 — 그 알림은 다시 나가지 않는다." -ForegroundColor Red
    exit 1
}

if ($env:NOTIFY_PROVIDER -eq 'solapi' -and -not $env:SOLAPI_TEST_RECIPIENT) {
    Write-Host ''
    Write-Host "경고: 알림톡이 켜져 있고 테스트 수신번호가 비어 있다." -ForegroundColor Yellow
    Write-Host "  이제부터 실제 구매자에게 발송된다. 건당 단가가 붙고 되돌릴 수 없다." -ForegroundColor Yellow
}

if ($env:SMART_TRACKER_ENABLED -eq 'true' -and -not $env:SMART_TRACKER_API_KEY) {
    Write-Host ''
    Write-Host "참고: SMART_TRACKER_ENABLED=true 인데 키가 비어 있어 배송조회는 꺼진 채다." -ForegroundColor Yellow
}

if ($Run) {
    Write-Host ''
    Write-Host "실행: $Run" -ForegroundColor Cyan
    Push-Location $repoRoot
    try { Invoke-Expression $Run }
    finally { Pop-Location }
}
