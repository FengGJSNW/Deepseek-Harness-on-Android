$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$lock = Get-Content -Raw "$repo/integrations/flclash.lock.json" | ConvertFrom-Json
$checkout = Join-Path $repo 'integrations/flclash'
function Invoke-FlClashGit {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Git command failed: $($Arguments -join ' ')" }
}
if (!(Test-Path "$checkout/.git")) {
    Invoke-FlClashGit @('clone', '--no-checkout', $lock.repository, $checkout)
    Invoke-FlClashGit @('-C', $checkout, 'checkout', '--detach', $lock.commit)
}
$head = & git -C $checkout rev-parse HEAD
if ($head -ne $lock.commit) { throw 'FlClash checkout is not at the pinned commit; preserve it and use a separate checkout.' }
Invoke-FlClashGit @('-C', $checkout, '-c', 'url.https://github.com/.insteadOf=git@github.com:', 'submodule', 'update', '--init', '--recursive')
foreach ($entry in $lock.submodules.PSObject.Properties) {
    $revision = & git -C "$checkout/$($entry.Name)" rev-parse HEAD
    if ($revision -ne $entry.Value) { throw "Unexpected FlClash submodule revision: $($entry.Name)" }
}
$patch = Join-Path $repo 'integrations/flclash.patch'
& git -C $checkout apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -eq 0) { Write-Output 'Pinned FlClash adaptation is already applied'; exit 0 }
Invoke-FlClashGit @('-C', $checkout, 'apply', '--check', $patch)
Invoke-FlClashGit @('-C', $checkout, 'apply', $patch)
