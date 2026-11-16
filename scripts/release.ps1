<#
.SYNOPSIS
  一条命令发版：从 CHANGELOG 读版本号 -> 改 build.gradle.kts -> 提交 -> 打 tag -> 推送。
  推送 tag 后 GitHub Actions 会自动跑单测、构建、正式签名、发 Release、上传 APK。

.DESCRIPTION
  发版前你只需要做一件事：在 CHANGELOG.md 顶部写好本版小节，形如

      ## [3.21.1] - 2026-11-17

      ### 修复
      - ...

  版本号、日期、tag、提交都由这个脚本统一处理，避免手抄错。

.PARAMETER DryRun
  只打印将要做什么，不改文件、不提交、不推送。

.EXAMPLE
  .\scripts\release.ps1 -DryRun
.EXAMPLE
  .\scripts\release.ps1
#>
param([switch]$DryRun)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    # ---- 1. 从 CHANGELOG 读最新版本小节 ----
    $lines = Get-Content -LiteralPath 'CHANGELOG.md' -Encoding UTF8
    $headIdx = -1
    $version = $null
    $date = $null
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^## \[(\d+\.\d+(?:\.\d+)?)\] - (\d{4}-\d{2}-\d{2})\s*$') {
            $version = $Matches[1]
            $date = $Matches[2]
            $headIdx = $i
            break
        }
    }
    if (-not $version) {
        throw 'CHANGELOG.md 里找不到形如 "## [3.21.1] - 2026-11-17" 的小节标题'
    }

    # ---- 2. 校验小节有实质内容 ----
    $body = @()
    for ($j = $headIdx + 1; $j -lt $lines.Count; $j++) {
        if ($lines[$j] -match '^## \[') { break }
        $body += $lines[$j]
    }
    $bullets = @($body | Where-Object { $_ -match '^\s*[-*]\s+\S' })
    if ($bullets.Count -lt 1) {
        throw "CHANGELOG 的 [$version] 小节里没有列表项，先写清这版改了什么"
    }

    $tag = "v$version"
    $today = Get-Date -Format 'yyyy-MM-dd'
    if ($date -ne $today) {
        Write-Host "提示：小节日期是 $date，今天是 $today（不拦，确认无误继续）" -ForegroundColor Yellow
    }

    # ---- 3. 工作区必须干净 ----
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) {
        Write-Host '已跟踪文件有未提交改动，先提交再发版：' -ForegroundColor Yellow
        $dirty | ForEach-Object { Write-Host "  $_" }
        throw '工作区不干净'
    }

    # ---- 4. tag 不能已存在 ----
    $exists = git tag -l $tag
    if ($exists) {
        throw "tag $tag 已存在。要重发先删掉它：git tag -d $tag; git push origin :refs/tags/$tag"
    }

    # ---- 5. 改 build.gradle.kts 的默认版本号 ----
    $gradle = 'app/build.gradle.kts'
    $g = Get-Content -LiteralPath $gradle -Raw -Encoding UTF8
    if ($g -notmatch 'bellnoteDefaultVersionName\s*=\s*"([^"]+)"') {
        throw "$gradle 里找不到 bellnoteDefaultVersionName"
    }
    $old = $Matches[1]
    if ($old -eq $version) {
        Write-Host "版本号已经是 $version，跳过改写" -ForegroundColor DarkGray
    } else {
        $g = $g -replace 'bellnoteDefaultVersionName\s*=\s*"[^"]+"', "bellnoteDefaultVersionName = `"$version`""
        if (-not $DryRun) {
            [System.IO.File]::WriteAllText((Join-Path $root $gradle), $g, (New-Object System.Text.UTF8Encoding($false)))
        }
        Write-Host "版本号：$old -> $version"
    }

    Write-Host ''
    Write-Host "本次发版：$tag（$date），CHANGELOG 条目 $($bullets.Count) 条" -ForegroundColor Green
    Write-Host "  提交：release $version"
    Write-Host "  tag ：$tag"
    Write-Host "  推送：origin main + $tag（推送后 CI 自动出包发 Release）"
    Write-Host ''

    if ($DryRun) {
        Write-Host 'DryRun：以上只做检查，未改动任何文件' -ForegroundColor Cyan
        return
    }

    # ---- 6. 提交 + 打 tag + 推送 ----
    git add $gradle
    git commit -m "release $version"
    git tag -a $tag -m "Bellnote $tag"
    git push origin main
    git push origin $tag

    Write-Host ''
    Write-Host "已推送 $tag。CI 约 3 分钟内出包：" -ForegroundColor Green
    Write-Host "  https://github.com/Mentos0111/bellnote/actions/workflows/release.yml"
}
finally {
    Pop-Location
}
