param(
    [string]$FlutterSdk = $env:FLUTTER_ROOT,
    [string]$GradleCache = $env:GRADLE_USER_HOME
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$source = Join-Path $repo 'integrations/flclash'
$lock = Get-Content -Raw "$repo/integrations/flclash.lock.json" | ConvertFrom-Json
$version = $lock.version.Split('+')
$stage = Join-Path $repo '.tmp/flclash-module'
$onWindows = $env:OS -eq 'Windows_NT'
$flutter = Join-Path $FlutterSdk $(if ($onWindows) { 'bin/flutter.bat' } else { 'bin/flutter' })
$linkType = if ($onWindows) { 'Junction' } else { 'SymbolicLink' }
if (!(Test-Path $flutter)) { throw 'Set FLUTTER_ROOT to Flutter 3.47.6' }
if (!(Test-Path "$source/plugins/prism_ui/pubspec.yaml")) { throw 'Prepare the pinned FlClash checkout and submodules first' }
New-Item -ItemType Directory -Force $stage | Out-Null
$excluded = @('.git', '.dart_tool', '.android', 'android', 'ios', 'macos', 'linux', 'windows', 'build', '.idea', '.agents', '.github')
Get-ChildItem -LiteralPath $source -Force | ForEach-Object {
    if ($_.Name -notin $excluded) {
        $destination = Join-Path $stage $_.Name
        if ($_.PSIsContainer) {
            if (!(Test-Path -LiteralPath $destination)) {
                New-Item -ItemType $linkType -Path $destination -Target $_.FullName | Out-Null
            }
        } else { Copy-Item -LiteralPath $_.FullName -Destination $destination -Force }
    }
}
$taskGradle = Join-Path $repo '.tmp/flclash-gradle'
$previousGradleHome = $env:GRADLE_USER_HOME
New-Item -ItemType Directory -Force "$taskGradle/init.d" | Out-Null
if ($GradleCache -and [IO.Path]::GetFullPath($GradleCache) -ne [IO.Path]::GetFullPath($taskGradle)) {
    foreach ($directory in @('caches', 'wrapper')) {
        if ((Test-Path "$GradleCache/$directory") -and !(Test-Path "$taskGradle/$directory")) {
            New-Item -ItemType $linkType -Path "$taskGradle/$directory" -Target "$GradleCache/$directory" | Out-Null
        }
    }
}
Copy-Item "$PSScriptRoot/flclash-maven.init.gradle" "$taskGradle/init.d/maven.gradle" -Force
$env:GRADLE_USER_HOME = $taskGradle
Push-Location $stage
try {
    & $flutter pub get
    if ($LASTEXITCODE -ne 0) { throw 'FlClash pub get failed' }
    # Keep the generated module on FlClash's native toolchain. Gradle 9.3 has
    # broken Kotlin DSL classpaths when Windows junctions are used (Gradle #36483).
    $wrapper = Join-Path $stage '.android/gradle/wrapper/gradle-wrapper.properties'
    $text = [IO.File]::ReadAllText($wrapper).Replace('gradle-9.3.1-all.zip', 'gradle-9.8.0-all.zip')
    [IO.File]::WriteAllText($wrapper, $text)
    $moduleSettings = Join-Path $stage '.android/settings.gradle'
    $text = [IO.File]::ReadAllText($moduleSettings).Replace('version "9.1.0"', 'version "9.4.1"').Replace('version "2.4.0"', 'version "2.4.20"')
    [IO.File]::WriteAllText($moduleSettings, $text)
    foreach ($relative in @('.android/build.gradle', '.android/Flutter/build.gradle', '.android/app/build.gradle')) {
        $file = Join-Path $stage $relative
        if (Test-Path $file) {
            $text = [IO.File]::ReadAllText($file).Replace('compileSdk = 36', 'compileSdk = 37').Replace('compileSdk = flutter.compileSdkVersion', 'compileSdk = 37')
            [IO.File]::WriteAllText($file, $text)
        }
    }
    $properties = Join-Path $stage '.android/gradle.properties'
    $settings = [IO.File]::ReadAllText($properties)
    if ($env:JAVA_HOME) {
        $settings = $settings -replace '(?m)^org.gradle.java.home=.*\r?\n?', ''
        $settings += "`norg.gradle.java.home=$($env:JAVA_HOME.Replace('\','/'))`n"
    }
    $settings = $settings -replace '(?m)^org.gradle.workers.max=.*\r?\n?', ''
    $settings = $settings -replace '(?m)^org.gradle.jvmargs=.*\r?\n?', ''
    $settings += "`norg.gradle.workers.max=4`norg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=2g -Dfile.encoding=UTF-8`n"
    [IO.File]::WriteAllText($properties, $settings)
    & $flutter build aar --no-debug --no-profile --target-platform=android-arm64,android-x64 --dart-define=DSHA_EMBEDDED=true "--dart-define=FLCLASH_VERSION=$($version[0])" "--dart-define=FLCLASH_BUILD_NUMBER=$($version[1])" --dart-define=APP_ENV=stable --no-pub
    if ($LASTEXITCODE -ne 0) { throw 'FlClash AAR build failed' }
    & node "$PSScriptRoot/verify-flclash.mjs" record
    if ($LASTEXITCODE -ne 0) { throw 'FlClash build input verification failed' }
} finally {
    $env:GRADLE_USER_HOME = $previousGradleHome
    Pop-Location
}
