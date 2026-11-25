param([switch]$Apply)
$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspacePrefix = $workspace.TrimEnd('\') + '\'
$latest = Get-Content -LiteralPath (Join-Path $workspace 'dist/build-report.json') -Raw | ConvertFrom-Json
$keepBuild = Split-Path -Parent $latest.build_log
$keepDist = @('组员部署包', 'mobile-ar-entry-v0131', 'mobile-functions-v014', 'geometry-v03-final', 'mobile-computer-v013', 'mobile-balanced-v0121')
$targets = @()
$targets += Get-ChildItem -LiteralPath (Join-Path $workspace 'dist') -Directory | Where-Object { $_.Name -notin $keepDist }
$targets += Get-ChildItem -LiteralPath (Join-Path $workspace 'android-app/build') -Directory | Where-Object { $_.FullName -ne $keepBuild }
$nativeBuild = Get-ChildItem -LiteralPath (Join-Path $workspace 'mobile-reconstruction/build') -Directory | Sort-Object Name -Descending
$targets += $nativeBuild | Select-Object -Skip 1
# Root-level dist assets are old published copies. Replace the canonical APK and
# report below, remove only explicitly recognized old screenshots/test outputs.
$oldRoot = @('ChairTests.apk', 'ChairTests.apk.idsig', 'phone-install-report.json',
    'preview-screen.png', 'test-ar-fallback.png', 'test-preview.png', 'test-report.json',
    'test-rotated.png', 'test-zoomed.png', 'v02-instrumentation.txt',
    'v02-test-ar-fallback.png', 'v02-test-dimensions-dialog.png', 'v02-test-dimensions.png',
    'v02-test-preview.png', 'v03-instrumentation.txt', 'v03-test-dimensions-dialog.png',
    'v03-test-preview.png', 'v03-test-size-guide.png', 'v031-instrumentation.txt', '安装说明.txt')
foreach ($name in $oldRoot) {
    $path = Join-Path $workspace "dist/$name"
    if (Test-Path -LiteralPath $path) { $targets += Get-Item -LiteralPath $path }
}
$plan = @()
foreach ($target in $targets) {
    $resolved = (Resolve-Path -LiteralPath $target.FullName).Path
    if (-not $resolved.StartsWith($workspacePrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Outside workspace: $resolved" }
    if ($target.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Reparse target: $resolved" }
    $children = if ($target.PSIsContainer) { @(Get-ChildItem -LiteralPath $resolved -Recurse -Force) } else { @($target) }
    if ($children | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw "Reparse child: $resolved" }
    $bytes = ($children | Where-Object { -not $_.PSIsContainer } | Measure-Object -Property Length -Sum).Sum
    $plan += [pscustomobject]@{ path=$resolved; bytes=[long]$bytes; directory=[bool]$target.PSIsContainer }
}
$record = [pscustomobject]@{
    created_at=(Get-Date -Format o); applied=[bool]$Apply;
    total_bytes=[long](($plan | Measure-Object -Property bytes -Sum).Sum);
    kept_current_build=$keepBuild; kept_dist=$keepDist; targets=$plan
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$recordPath = Join-Path $workspace "docs/历史版本清理-$stamp.json"
$record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $recordPath -Encoding utf8
Write-Output "Targets: $($plan.Count); bytes: $($record.total_bytes); apply: $Apply"
Write-Output "Record: $recordPath"
if ($Apply) {
    # All resolved targets were checked above, prior to any recursive deletion.
    foreach ($item in $plan) {
        Remove-Item -LiteralPath $item.path -Recurse -Force
    }
    foreach ($name in @('ChairPreview.apk','build-report.json')) {
        Copy-Item -LiteralPath (Join-Path $workspace "dist/mobile-functions-v014/$name") -Destination (Join-Path $workspace "dist/$name") -Force
    }
    Write-Output 'Deleted historical targets and refreshed canonical APK/report.'
}
