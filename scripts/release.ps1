<#
.SYNOPSIS
  一条命令发版：从 CHANGELOG 读版本号 -> 改 build.gradle.kts -> 更新 AGENTS.md 状态快照
  -> 提交 -> 打 tag -> 推送。推送后 GitHub Actions 自动跑单测、构建、正式签名、发 Release。

.DESCRIPTION
  发版前你只需要做一件事：在 CHANGELOG.md 顶部写好本版小节，形如

      ## [3.21.1] - 2026-11-17

      ### 修复
      - ...

  版本号、日期、tag、提交、以及给新会话看的「当前状态」都由这个脚本统一处理。

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
    } elseif (-not $DryRun) {
        $g = $g -replace 'bellnoteDefaultVersionName\s*=\s*"[^"]+"', "bellnoteDefaultVersionName = `"$version`""
        [System.IO.File]::WriteAllText((Join-Path $root $gradle), $g, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "版本号：$old -> $version"
    } else {
        Write-Host "版本号：$old -> $version（DryRun 未写入）"
    }

    Write-Host ''
    Write-Host "本次发版：$tag（$date），CHANGELOG 条目 $($bullets.Count) 条" -ForegroundColor Green
    Write-Host "  提交：release $version"
    Write-Host "  tag ：$tag"
    Write-Host "  推送：origin main + $tag（推送后 CI 自动出包发 Release）"
    Write-Host '  AGENTS.md：会把「当前状态」表更新成这一版，新会话不用常驻也能接上'
    Write-Host ''

    if ($DryRun) {
        Write-Host 'DryRun：以上只做检查，未改动任何文件' -ForegroundColor Cyan
        return
    }

    # ---- 6. 更新 AGENTS.md 的「当前状态」快照 ----
    $agents = 'AGENTS.md'
    $al = [System.IO.File]::ReadAllLines((Join-Path $root $agents), [System.Text.Encoding]::UTF8)
    $now = Get-Date -Format 'yyyy-MM-dd HH:mm'
    $hitCount = 0
    for ($i = 0; $i -lt $al.Count; $i++) {
        if ($al[$i] -match '^\| 最新发布 \|') {
            $al[$i] = "| 最新发布 | ``v$version``（$now 推送；CI 全自动构建发 Release，真机验证后补一句结论） |"
            $hitCount++
        }
        elseif ($al[$i] -match '^\| 本地领先 \|') {
            $al[$i] = "| 本地领先 | 比 tag 多 0 个提交（刚发版） |"
            $hitCount++
        }
        elseif ($al[$i] -match '^\| 代码默认版本 \|') {
            $al[$i] = "| 代码默认版本 | ``$version``（和已发布一致） |"
            $hitCount++
        }
        elseif ($al[$i] -match '^\| 下一步 \|') {
            $al[$i] = "| 下一步 | 等 CI 绿 + 真机验证本次改动；下一版先问本人要版本号 |"
            $hitCount++
        }
    }
    if ($hitCount -ge 3) {
        [System.IO.File]::WriteAllLines((Join-Path $root $agents), $al, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "已更新 AGENTS.md 的当前状态（$hitCount 行）" -ForegroundColor Green
    } else {
        Write-Host '警告：AGENTS.md 的状态表没找到，跳过更新（请检查表格格式）' -ForegroundColor Yellow
    }

    # ---- 7. 提交 + 打 tag + 推送 ----
    git add $gradle $agents
    git commit -m "release $version"
    if ($LASTEXITCODE -ne 0) { throw "git commit 失败（退出码 $LASTEXITCODE）" }
    git tag -a $tag -m "Bellnote $tag"
    if ($LASTEXITCODE -ne 0) { throw "打 tag 失败（退出码 $LASTEXITCODE）" }
    # 外部命令失败不会抛异常，必须自己看退出码：以前 ssh 失败也照样打印「已推送」
    git push origin main
    if ($LASTEXITCODE -ne 0) { throw "推送 main 失败（退出码 $LASTEXITCODE）；tag $tag 只在本地，修好后重跑 git push origin main; git push origin $tag" }
    git push origin $tag
    if ($LASTEXITCODE -ne 0) { throw "推送 tag $tag 失败（退出码 $LASTEXITCODE）；本地 tag 还在，修好后单独 git push origin $tag" }

    Write-Host ''
    Write-Host "已推送 $tag。CI 约 3 分钟内出包：" -ForegroundColor Green
    Write-Host "  https://github.com/Mentos0111/bellnote/actions/workflows/release.yml"
}
finally {
    Pop-Location
}
