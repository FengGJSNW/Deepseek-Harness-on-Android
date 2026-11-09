param([switch]$Check)

$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$tools = Join-Path $project '.tmp\format-tools'
$ktfmtVersion = '0.65'
$ktfmtArchive = Join-Path $tools 'ktfmt-proxy.zip'
$ktfmtRoot = Join-Path $tools 'ktfmt'
$ktfmt = Join-Path $ktfmtRoot "ktfmt-windows-x86_64-$ktfmtVersion\ktfmt.exe"
$ktfmtSha256 = '3286d659fa8df6a480e3b53ca4a30d9321c372286c46b8d74556b434189bee5f'
$pythonTools = Join-Path $tools 'python'
$prettier = Join-Path $project 'node_modules\prettier\bin\prettier.cjs'
$previousPythonPath = $env:PYTHONPATH

function Invoke-Checked {
    param([string]$Program, [string[]]$ToolArguments)

    & $Program @ToolArguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Program failed with exit code $LASTEXITCODE"
    }
}

New-Item -ItemType Directory -Force -Path $tools | Out-Null
Push-Location $project
try {
    if (-not (Test-Path -LiteralPath $ktfmt)) {
        if (-not (Test-Path -LiteralPath $ktfmtArchive)) {
            Invoke-Checked 'curl.exe' @(
                '--fail', '--location', '--retry', '2', '--max-time', '120',
                '--header', 'Accept: application/octet-stream',
                'https://api.github.com/repos/Kotlin/ktfmt/releases/assets/618674676',
                '--output', $ktfmtArchive
            )
        }
        $digest = (Get-FileHash -LiteralPath $ktfmtArchive -Algorithm SHA256).Hash
        if ($digest -ne $ktfmtSha256) {
            throw 'The ktfmt release checksum does not match the pinned official asset.'
        }
        Expand-Archive -LiteralPath $ktfmtArchive -DestinationPath $ktfmtRoot
    }

    if (-not (Test-Path -LiteralPath $prettier)) {
        throw 'Run npm ci first to install the pinned Prettier dependency.'
    }

    $env:PYTHONPATH = $pythonTools
    if ($previousPythonPath) {
        $env:PYTHONPATH += [IO.Path]::PathSeparator + $previousPythonPath
    }
    & python -c "import black; assert black.__version__ == '26.10.1'" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Invoke-Checked 'python' @(
            '-m', 'pip', 'install', '--target', $pythonTools, 'black==26.10.1',
            '--disable-pip-version-check'
        )
    }

    $kotlinFiles = @(
        Get-ChildItem -LiteralPath 'app\src', 'control-lab\src' -Recurse -File -Filter '*.kt' |
            Sort-Object FullName |
            ForEach-Object { $_.FullName }
    )
    $kotlinFiles += @(
        'build.gradle.kts', 'settings.gradle.kts',
        'app\build.gradle.kts', 'control-lab\build.gradle.kts'
    )
    $kotlinArguments = @('--kotlinlang-style', '--quiet')
    if ($Check) {
        $kotlinArguments += @('--dry-run', '--set-exit-if-changed')
    }
    Invoke-Checked $ktfmt ($kotlinArguments + $kotlinFiles)

    $prettierMode = if ($Check) { '--check' } else { '--write' }
    Invoke-Checked 'node' @(
        $prettier, $prettierMode,
        'web/*.ts', 'web/*.js', 'web/*.css', 'web/*.html', 'scripts/*.ts', 'scripts/*.mjs', 'tests/*.mjs',
        'app/src/main/assets/app.js', 'app/src/main/assets/index.html',
        'package.json', '.prettierrc.json'
    )

    $blackArguments = @('-m', 'black', 'scripts')
    if ($Check) {
        $blackArguments += '--check'
    }
    Invoke-Checked 'python' $blackArguments
    Write-Output 'Kotlin, Gradle Kotlin DSL, TypeScript/JavaScript and Python formatting complete.'
}
finally {
    $env:PYTHONPATH = $previousPythonPath
    Pop-Location
}
