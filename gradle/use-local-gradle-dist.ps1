# 将 Gradle Wrapper 改为使用本项目 gradle\distribution 下的离线 ZIP（需事先手动下载）
$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$zip = Join-Path $repoRoot "gradle\distribution\gradle-8.12.1-bin.zip"
if (-not (Test-Path -LiteralPath $zip)) {
    Write-Host "未找到离线包，请先下载 Gradle 并重命名为：" -ForegroundColor Red
    Write-Host "  gradle\distribution\gradle-8.12.1-bin.zip" -ForegroundColor Yellow
    Write-Host "说明见 gradle\distribution\README.txt" -ForegroundColor DarkGray
    exit 1
}
$absolute = [System.IO.Path]::GetFullPath((Get-Item -LiteralPath $zip).FullName)
# 非 ASCII 路径须用百分号编码写入，否则 Java URI 解析报错；Gradle 属性里仅对 file: 的冒号做 \ 转义
$uriAbs = [System.Uri]::new($absolute, [System.UriKind]::Absolute).AbsoluteUri
$escapedUrl = $uriAbs -replace '^file:', 'file\:'
$propsPath = Join-Path $repoRoot "gradle\wrapper\gradle-wrapper.properties"
$lines = Get-Content -LiteralPath $propsPath -Encoding UTF8
$out = [System.Collections.Generic.List[string]]::new()
foreach ($line in $lines) {
    if ($line -match '^distributionUrl=') {
        $out.Add("distributionUrl=$escapedUrl")
    } elseif ($line -match '^networkTimeout=') {
        $out.Add("networkTimeout=120000")
    } elseif ($line -match '^validateDistributionUrl=') {
        $out.Add("validateDistributionUrl=false")
    } else {
        $out.Add($line)
    }
}
[System.IO.File]::WriteAllLines($propsPath, $out, [System.Text.UTF8Encoding]::new($false))
Write-Host "已切换为本地 Gradle 包：" -ForegroundColor Green
Write-Host "  $absolute"
