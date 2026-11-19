# Live monitor for all lanes (git worktrees). Run it in its own terminal and leave it open:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\watch.ps1
# Every 2 s it shows, per lane: branch, files changed right now (with +/- lines), the last commit,
# and whether a worker process is running. Ctrl+C to stop.
param([int]$Every = 2)
$repo = Split-Path (git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir) -Parent
while ($true) {
    $out = New-Object System.Text.StringBuilder
    $workers = @(Get-CimInstance Win32_Process -Filter "Name='opencode.exe'" -ErrorAction SilentlyContinue).Count
    [void]$out.AppendLine(("{0}   workers running: {1}   (Ctrl+C to quit)" -f (Get-Date -Format "HH:mm:ss"), $workers))
    [void]$out.AppendLine("")
    foreach ($line in (git -C $repo worktree list --porcelain | Select-String '^worktree ')) {
        $dir = $line.ToString().Substring(9)
        $branch = git -C $dir branch --show-current
        $last = git -C $dir log -1 --format="%h %s" 2>$null
        $stat = @(git -C $dir diff HEAD --numstat 2>$null)
        $new = @(git -C $dir ls-files --others --exclude-standard 2>$null)
        [void]$out.AppendLine(("== {0}   [{1}]" -f (Split-Path $dir -Leaf), $branch))
        [void]$out.AppendLine("   last commit: $last")
        if ($stat.Count + $new.Count -eq 0) { [void]$out.AppendLine("   (no uncommitted changes)") }
        foreach ($s in $stat) {
            $p = $s -split "`t"
            [void]$out.AppendLine(("   M +{0,-4} -{1,-4} {2}" -f $p[0], $p[1], $p[2]))
        }
        foreach ($n in $new) { [void]$out.AppendLine("   A (new)        $n") }
        [void]$out.AppendLine("")
    }
    Clear-Host
    Write-Host $out.ToString()
    Start-Sleep -Seconds $Every
}
