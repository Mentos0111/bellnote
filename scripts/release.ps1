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

.PARAMETER AllowDirty
  显式放过几个已跟踪的脏路径（逗号分隔，可写文件或目录前缀），默认空 = 一个都不放过。
  只用于「别的会话正在改、和本次发版无关」的文件；脚本不会替你 stash，也不会提交它们。
  例：-AllowDirty '任务卡\,docs\'
  放过的路径会打出来，发版成功后照样再打一次，方便抄进回执。

.EXAMPLE
  .\scripts\release.ps1 -DryRun
.EXAMPLE
  .\scripts\release.ps1 -AllowDirty 'AGENTS.md'
.EXAMPLE
  .\scripts\release.ps1
#>
param([switch]$DryRun, [string]$AllowDirty = '')

# 把 -AllowDirty 的写法统一成「相对仓库根、反斜杠、不带首尾空格」的列表。
function Split-AllowList([string]$raw) {
    if (-not $raw) { return @() }
    $out = @()
    foreach ($piece in ($raw -split '[,;]')) {
        $p = $piece.Trim().Trim('"').Trim("'").Replace('/', '\').TrimEnd('\')
        if ($p) { $out += $p }
    }
    return $out
}

# 挡路文件按「谁的地盘」分组，方便一眼看出该找哪个会话。
function Get-DirtyGroup([string]$path) {
    if ($path -like '任务卡\*') { return '任务卡' }
    if ($path -like '角色\*') { return '角色' }
    if ($path -like 'app\*' -or $path -like 'scripts\*' -or $path -like '.github\*') { return '业务代码' }
    return '其它'
}

# 白名单命中：完全相同，或落在目录前缀下（-AllowDirty 写目录就整目录放过）。
function Test-Allowed([string]$path, [string[]]$allow) {
    foreach ($a in $allow) {
        if ($path -eq $a) { return $true }
        if ($path -like "$a\*") { return $true }
    }
    return $false
}

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

    # ---- 3. 工作区必须干净（脏就按路径分组列清单，拒绝，不替人 stash）----
    $allow = Split-AllowList $AllowDirty
    $dirtyLines = git -c core.quotePath=false status --porcelain --untracked-files=no
    $dirtyFiles = @()
    foreach ($line in @($dirtyLines)) {
        if (-not $line) { continue }
        $p = $line.Substring(3).Trim()
        if ($p -match ' -> ') { $p = ($p -split ' -> ')[-1] }
        $p = $p.Trim('"').Replace('/', '\')
        $dirtyFiles += $p
    }
    $passing = @($dirtyFiles | Where-Object { Test-Allowed $_ $allow })
    $blocking = @($dirtyFiles | Where-Object { -not (Test-Allowed $_ $allow) })
    if ($passing.Count -gt 0) {
        Write-Host '以下脏路径被 -AllowDirty 显式放过（不提交、不动内容，后果自负）：' -ForegroundColor Yellow
        $passing | ForEach-Object { Write-Host "  [放过] $_" -ForegroundColor Yellow }
    }
    if ($blocking.Count -gt 0) {
        Write-Host '已跟踪文件有未提交改动，发不了版。按地盘分组如下：' -ForegroundColor Yellow
        foreach ($g in @('任务卡', '角色', '业务代码', '其它')) {
            $items = @($blocking | Where-Object { (Get-DirtyGroup $_) -eq $g })
            if ($items.Count -eq 0) { continue }
            Write-Host "  【$g】$($items.Count) 个" -ForegroundColor Yellow
            $items | ForEach-Object { Write-Host "    $_" }
        }
        Write-Host '处理办法（选一个）：' -ForegroundColor Cyan
        Write-Host '  1) 自己改的 → 现在 commit 掉；' -ForegroundColor Cyan
        Write-Host '  2) 别人正在改、与本次发版无关 → 重跑时加 -AllowDirty ''路径1,路径2''（目录写前缀即可）' -ForegroundColor Cyan
        Write-Host '  3) 不许 git stash / 备份还原绕过检查（那等于把安全带剪了）。' -ForegroundColor Cyan
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
        if ($passing.Count -gt 0) {
            Write-Host ('DryRun 会放过的脏路径：' + ($passing -join '、')) -ForegroundColor Yellow
        }
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
    if ($passing.Count -gt 0) {
        Write-Host ''
        Write-Host ('本次发版放过的脏路径（抄进回执留痕）：' + ($passing -join '、')) -ForegroundColor Yellow
    } else {
        Write-Host ''
        Write-Host '本次发版没有放过任何脏路径（工作区本来就是干净的）' -ForegroundColor DarkGray
    }
}
finally {
    Pop-Location
}
