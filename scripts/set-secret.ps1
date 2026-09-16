<#
.SYNOPSIS
  secrets.local.env 의 값 하나를 채운다. 값은 화면에도 셸 기록에도 남지 않는다.

.DESCRIPTION
  명령줄에 값을 직접 쓰면 PowerShell 기록(ConsoleHost_history.txt)에 그대로 남는다.
  그래서 값은 인자로 받지 않고 Read-Host 로 따로 받는다.

  줄 순서와 주석은 그대로 두고 해당 줄만 갈아 끼운다.

.EXAMPLE
  .\scripts\set-secret.ps1 SOLAPI_PF_ID

.EXAMPLE
  # 여러 개를 이어서
  'SMART_TRACKER_API_KEY','SOLAPI_PF_ID' | ForEach-Object { .\scripts\set-secret.ps1 $_ }
#>
[CmdletBinding()]
param(
    # 채울 이름. 예: SOLAPI_PF_ID
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $Name,

    [string] $File
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $File) { $File = Join-Path $repoRoot 'secrets.local.env' }

if (-not (Test-Path -LiteralPath $File)) {
    Copy-Item (Join-Path $repoRoot 'secrets.local.env.example') $File
    Write-Host "secrets.local.env 를 새로 만들었다" -ForegroundColor DarkGray
}

# 입력받는 동안 화면에 찍히지 않게 가린다. 붙여넣기는 그대로 된다
$secure = Read-Host -Prompt "$Name 값을 붙여넣고 Enter" -AsSecureString
$bstr   = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try   { $value = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

$value = $value.Trim()
if ($value -eq '') {
    Write-Host "빈 값이라 그만둔다. 파일은 그대로다" -ForegroundColor Yellow
    exit 1
}

# 줄 순서와 주석을 지키고 그 줄만 바꾼다
$lines   = @(Get-Content -LiteralPath $File -Encoding UTF8)
$replaced = $false
$out = foreach ($line in $lines) {
    if (-not $replaced -and $line -match "^\s*$([regex]::Escape($Name))\s*=") {
        $replaced = $true
        "$Name=$value"
    }
    else { $line }
}
if (-not $replaced) { $out = $out + "$Name=$value" }

Set-Content -LiteralPath $File -Value $out -Encoding UTF8

# 값이 아니라 길이만 알려 준다. 붙여넣다 잘렸는지는 이걸로 알 수 있다
Write-Host "$Name 채움 ($($value.Length)자)" -ForegroundColor Green
