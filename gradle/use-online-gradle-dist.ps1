# 将 Gradle Wrapper 恢复为官方在线下载（弱网不推荐）
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$propsPath = Join-Path $repoRoot "gradle\wrapper\gradle-wrapper.properties"
$lines = Get-Content -LiteralPath $propsPath -Encoding UTF8
$out = [System.Collections.Generic.List[string]]::new()
foreach ($line in $lines) {
    if ($line -match '^distributionUrl=') {
        $out.Add("distributionUrl=https\://services.gradle.org/distributions/gradle-8.12.1-bin.zip")
    } elseif ($line -match '^networkTimeout=') {
        $out.Add("networkTimeout=120000")
    } elseif ($line -match '^validateDistributionUrl=') {
        $out.Add("validateDistributionUrl=true")
    } else {
        $out.Add($line)
    }
}
[System.IO.File]::WriteAllLines($propsPath, $out, [System.Text.UTF8Encoding]::new($false))
Write-Host "已恢复为官方在线 distributionUrl（services.gradle.org）。" -ForegroundColor Green
