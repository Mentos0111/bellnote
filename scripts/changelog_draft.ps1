<#
.SYNOPSIS
  根据上一个 tag 到 HEAD 的提交，自动起草 CHANGELOG 新版本小节。

.DESCRIPTION
  发版前跑一次，它会把这段时间的提交按「新增 / 修复 / 调整 / 文档」归类，
  写成一个小节插到 CHANGELOG.md 顶部。草稿是给人和 AI 改的底稿：
  内部工具类提交会自动跳过，但**发版前必须人工精简**——合并同类、删掉不该对外的内部改动、
  把提交语改成用户能看懂的话。

.PARAMETER Version
  要发的版本号，不带 v，例如 3.21.1。

.PARAMETER FromTag
  从哪个 tag 开始算，默认取最近的一个 tag。

.PARAMETER DryRun
  只打印草稿，不写 CHANGELOG.md。

.EXAMPLE
  .\scripts\changelog_draft.ps1 -Version 3.21.1 -DryRun
.EXAMPLE
  .\scripts\changelog_draft.ps1 -Version 3.21.1
#>
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$FromTag = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if ($Version -notmatch '^\d+\.\d+(\.\d+)?$') {
        throw "版本号格式不对：$Version（要写成 3.21.1 这样，不带 v）"
    }

    if (-not $FromTag) {
        $FromTag = (git describe --tags --abbrev=0 2>$null)
        if (-not $FromTag) { throw '找不到上一个 tag，用 -FromTag 显式指定（或首次发版用 -FromTag "" 的兜底）' }
    }

    $subjects = @(git log "$FromTag..HEAD" --pretty=format:"%s" 2>$null)
    if ($subjects.Count -eq 0) {
        throw "$FromTag..HEAD 之间没有提交，没什么可写的"
    }

    $new = New-Object System.Collections.ArrayList
    $fix = New-Object System.Collections.ArrayList
    $tune = New-Object System.Collections.ArrayList
    $docs = New-Object System.Collections.ArrayList
    $other = New-Object System.Collections.ArrayList
    $skipped = 0

    foreach ($s in $subjects) {
        $t = $s.Trim()
        if (-not $t) { continue }
        # 内部流程类提交不进用户 changelog
        if ($t -match '^(发版|release|chore|ci|build)[:：]' -or $t -match '^发版') { $skipped++; continue }
        if ($t -match '^(文档|docs)[:：]' -or $t -match 'README|CHANGELOG|HANDOFF') { [void]$docs.Add(($t -replace '^(文档|docs)[:：]\s*','')); continue }
        if ($t -match '(修复|修正|修了|修好|bug|Bug|BUG|报错|崩溃|不生效|失效|漏)') { [void]$fix.Add($t); continue }
        if ($t -match '(新增|加 |加了|支持|入口|允许|首次|重做|回来)') { [void]$new.Add($t); continue }
        if ($t -match '(改|调整|调|换|重排|优化|删除|精简|提亮|压暗)') { [void]$tune.Add($t); continue }
        [void]$other.Add($t)
    }

    $date = Get-Date -Format 'yyyy-MM-dd'
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("## [$Version] - $date")
    [void]$sb.AppendLine('')
    [void]$sb.AppendLine("> 本节由 ``scripts/changelog_draft.ps1`` 从 ``$FromTag..HEAD`` 自动起草（跳过 $skipped 条内部提交），**发版前人工精简**。")
    [void]$sb.AppendLine('')
    if ($new.Count) {
        [void]$sb.AppendLine('### 新增')
        [void]$sb.AppendLine('')
        foreach ($x in $new) { [void]$sb.AppendLine("- $x") }
        [void]$sb.AppendLine('')
    }
    if ($fix.Count) {
        [void]$sb.AppendLine('### 修复')
        [void]$sb.AppendLine('')
        foreach ($x in $fix) { [void]$sb.AppendLine("- $x") }
        [void]$sb.AppendLine('')
    }
    if ($tune.Count) {
        [void]$sb.AppendLine('### 调整')
        [void]$sb.AppendLine('')
        foreach ($x in $tune) { [void]$sb.AppendLine("- $x") }
        [void]$sb.AppendLine('')
    }
    if ($docs.Count) {
        [void]$sb.AppendLine('### 文档')
        [void]$sb.AppendLine('')
        foreach ($x in $docs) { [void]$sb.AppendLine("- $x") }
        [void]$sb.AppendLine('')
    }
    if ($other.Count) {
        [void]$sb.AppendLine('### 其他')
        [void]$sb.AppendLine('')
        foreach ($x in $other) { [void]$sb.AppendLine("- $x") }
        [void]$sb.AppendLine('')
    }
    $draft = $sb.ToString()

    Write-Host $draft -ForegroundColor Cyan

    if ($DryRun) {
        Write-Host 'DryRun：未写入 CHANGELOG.md' -ForegroundColor Yellow
        return
    }

    # 插到第一个 "## [" 之前
    $path = Join-Path $root 'CHANGELOG.md'
    $lines = [System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::UTF8)
    $idx = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^## \[') { $idx = $i; break }
    }
    if ($idx -lt 0) { throw 'CHANGELOG.md 里找不到已有的 "## [版本]" 小节，无法定位插入点' }

    $out = New-Object System.Collections.ArrayList
    for ($i = 0; $i -lt $idx; $i++) { [void]$out.Add($lines[$i]) }
    foreach ($l in ($draft -split "`r?`n")) { [void]$out.Add($l) }
    for ($i = $idx; $i -lt $lines.Count; $i++) { [void]$out.Add($lines[$i]) }

    [System.IO.File]::WriteAllLines($path, $out, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已写入 CHANGELOG.md 顶部，去改一改就能发版了。" -ForegroundColor Green
    Write-Host "下一步：.\scripts\release.ps1 -DryRun" -ForegroundColor Green
}
finally {
    Pop-Location
}
