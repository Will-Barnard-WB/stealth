# Windows side of stealth-dev (see stealth-dev.cmd). Keep the behaviour in step with the `stealth-dev` shell script.
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root 'cli\target'
# Not shared with the shell script: the two write the file list differently
$stamp = Join-Path $target 'stealth-dev.ps.stamp'

# Everything that goes into the jar: the POMs and each module's main sources
function Get-Sources {
    $files = @(Get-Item (Join-Path $root 'pom.xml'))
    foreach ($module in Get-ChildItem $root -Directory) {
        $pom = Join-Path $module.FullName 'pom.xml'
        if (-not (Test-Path $pom)) { continue }
        $files += Get-Item $pom
        $main = Join-Path $module.FullName 'src\main'
        if (Test-Path $main) { $files += Get-ChildItem $main -Recurse -File }
    }
    $files
}

function Get-NewestJar {
    Get-ChildItem $target -Filter 'stealth-cli-*.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

function Get-Listing($files) {
    (($files | ForEach-Object { $_.FullName.Substring($root.Length) } | Sort-Object) -join "`n")
}

$sources = Get-Sources
$listing = Get-Listing $sources

$stale = $true
if ((Test-Path $stamp) -and (Get-NewestJar)) {
    $built = (Get-Item $stamp).LastWriteTimeUtc
    # A different file list catches added and deleted files, e.g. after switching branch
    $sameFiles = ((Get-Content $stamp -Raw) -replace "\s+$", '') -eq $listing
    $changed = $sources | Where-Object { $_.LastWriteTimeUtc -gt $built } | Select-Object -First 1
    $stale = (-not $sameFiles) -or $changed
}

if ($stale) {
    $branch = 'unknown'
    try { $branch = (& git -C $root rev-parse --abbrev-ref HEAD 2>$null) } catch {}
    [Console]::Error.WriteLine("stealth-dev: building $root ($branch)...")
    # Taken before the build so that edits made while it runs trigger another build next time
    $started = (Get-Date).ToUniversalTime()
    Push-Location $root
    try {
        & .\mvnw.cmd -q -pl cli -am package -DskipTests | ForEach-Object { [Console]::Error.WriteLine($_) }
        $failed = $LASTEXITCODE -ne 0
    } finally {
        Pop-Location
    }
    if ($failed) {
        [Console]::Error.WriteLine('stealth-dev: build failed')
        exit 1
    }
    Set-Content -Path $stamp -Value $listing -Encoding utf8
    (Get-Item $stamp).LastWriteTimeUtc = $started
}

$java = 'java'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
& $java -jar (Get-NewestJar).FullName @args
exit $LASTEXITCODE
