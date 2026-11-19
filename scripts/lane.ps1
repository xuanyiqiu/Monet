# One isolated "lane" (git worktree) per worker task, so several workers can run in parallel.
#
#   scripts\lane.ps1 -Name a17 -Branch feat/a17-storage-missing-object          # create from main
#   scripts\lane.ps1 -Name a17 -Remove                                           # delete the lane (branch is kept)
#
# A fresh worktree has none of the git-ignored folders the tooling expects, so this links them:
#   .tools            -> junction to the shared portable toolchain (the specs use ../../.tools/bin/uv.exe)
#   data\generated    -> junction (chengyu.json etc.)
#   apps\android\local.properties -> copy (Supabase publishable key; never committed)
param(
    [Parameter(Mandatory)][string]$Name,
    [string]$Branch,
    [string]$Base = "main",
    [switch]$Remove
)
$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$dir = Join-Path $repo ".claude\worktrees\$Name"

function Remove-Junction([string]$path) {
    # rmdir on a junction removes only the link, never the target's contents
    if (Test-Path $path) { cmd /c rmdir "`"$path`"" | Out-Null }
}

if ($Remove) {
    if (-not (Test-Path $dir)) { throw "no such lane: $dir" }
    Remove-Junction "$dir\.tools"
    Remove-Junction "$dir\data\generated"
    git -C $repo worktree remove --force $dir
    Write-Output "removed lane $Name (branch kept)"
    return
}

if (-not $Branch) { throw "-Branch is required" }
if (Test-Path $dir) { throw "lane already exists: $dir" }
git -C $repo worktree add $dir -b $Branch $Base
New-Item -ItemType Junction -Path "$dir\.tools" -Target "$repo\.tools" | Out-Null
if (Test-Path "$repo\data\generated") {
    New-Item -ItemType Directory -Force -Path "$dir\data" | Out-Null
    New-Item -ItemType Junction -Path "$dir\data\generated" -Target "$repo\data\generated" | Out-Null
}
if (Test-Path "$repo\apps\android\local.properties") {
    Copy-Item "$repo\apps\android\local.properties" "$dir\apps\android\local.properties"
}
Write-Output "lane ready: $dir  (branch $Branch from $Base)"
