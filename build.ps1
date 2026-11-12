# Builds the app without Gradle: aapt2 + javac + d8 (+ zipalign/apksigner, + bundletool for Play).
#   .\build.ps1            -> ShieldHotspot.apk          (dev build, signed with signing.keystore; sideload with adb)
#   .\build.ps1 -Release   -> ShieldHotspot-release.aab  (Google Play upload bundle, signed with upload-keystore.jks)
#                             nvidia-shield-5ghz-hotspot-<version>.apk (GitHub Releases download, same key)
# Uses paths relative to the project folder (the project path may contain spaces).
# SDK: -Sdk <path>, else $env:ANDROID_HOME / $env:ANDROID_SDK_ROOT.
param([string]$Sdk = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }), [switch]$Release)
if (-not $Sdk) { throw "Android SDK not found: set ANDROID_HOME or pass -Sdk <path>" }
# Native tools print notes on stderr; failures are detected via exit codes (Check) instead.
$ErrorActionPreference = "Continue"
Push-Location $PSScriptRoot
try {
    $bt = Get-ChildItem "$Sdk\build-tools" -Directory | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    $jar = "$Sdk\platforms\android-30\android.jar"
    if (Test-Path build) { Remove-Item -Recurse -Force build }
    New-Item -ItemType Directory -Force build\classes, build\dex, build\flat, build\gen | Out-Null

    function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed ($LASTEXITCODE)" } }
    function Rel($items) { $items | ForEach-Object { Resolve-Path -Relative $_.FullName } }

    if (-not (Test-Path res\drawable-xhdpi\banner.png)) { java tools\MakeArt.java .; Check "artwork" }

    # Resources (+ generated R.java)
    & "$bt\aapt2.exe" compile --dir res -o build\flat; Check "aapt2 compile"
    $flats = Rel (Get-ChildItem build\flat -Filter *.flat)
    & "$bt\aapt2.exe" link -I $jar --manifest AndroidManifest.xml --java build\gen -o build\unsigned.apk $flats; Check "aapt2 link"

    # Code
    $srcs = Rel (Get-ChildItem src, build\gen -Recurse -Filter *.java)
    javac -nowarn -Xlint:-options --release 8 -classpath $jar -encoding UTF-8 -d build\classes $srcs; Check "javac"
    jar cf build\classes.jar -C build\classes .; Check "jar"
    & "$bt\d8.bat" --release --min-api 30 --lib $jar --output build\dex build\classes.jar; Check "d8"

    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

    if (-not $Release) {
        $zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path build\unsigned.apk).Path, "Update")
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path build\dex\classes.dex).Path, "classes.dex") | Out-Null
        $zip.Dispose()
        & "$bt\zipalign.exe" -p -f 4 build\unsigned.apk build\aligned.apk; Check "zipalign"
        if (-not (Test-Path signing.keystore)) {
            cmd /c 'keytool -genkeypair -keystore signing.keystore -storepass shieldhotspot -keypass shieldhotspot -alias app -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Hotspot Dev" 2>nul'; Check "keytool"
        }
        & "$bt\apksigner.bat" sign --ks signing.keystore --ks-pass pass:shieldhotspot --ks-key-alias app --out ShieldHotspot.apk build\aligned.apk; Check "apksigner"
        Write-Host "Built $PSScriptRoot\ShieldHotspot.apk"
        return
    }

    # ---------- Release: Android App Bundle for Google Play ----------
    $bundletool = "tools\bundletool.jar"
    if (-not (Test-Path $bundletool)) { throw "Missing $bundletool (download bundletool-all-<ver>.jar from github.com/google/bundletool/releases)" }

    # Proto-format resources for the bundle, then the base module zip:
    #   manifest/AndroidManifest.xml, dex/classes.dex, res/..., resources.pb
    & "$bt\aapt2.exe" link --proto-format -I $jar --manifest AndroidManifest.xml -o build\proto.zip $flats; Check "aapt2 link (proto)"
    New-Item -ItemType Directory -Force build\module\manifest, build\module\dex | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path build\proto.zip).Path, (Join-Path $PWD "build\proto"))
    Move-Item build\proto\AndroidManifest.xml build\module\manifest\
    Move-Item build\proto\resources.pb build\module\
    Move-Item build\proto\res build\module\
    Copy-Item build\dex\classes.dex build\module\dex\
    # jar (not .NET ZipFile, which writes backslash paths on Windows PowerShell); M = no META-INF manifest
    jar cfM build\base.zip -C build\module .; Check "module zip"

    java -jar $bundletool build-bundle --modules=build\base.zip --output=build\unsigned.aab; Check "bundletool build-bundle"

    # Upload key (Play App Signing re-signs for users). Created once; BACK IT UP with its properties file.
    if (-not (Test-Path upload-keystore.jks)) {
        $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
        cmd /c "keytool -genkeypair -keystore upload-keystore.jks -storepass $pw -keypass $pw -alias upload -keyalg RSA -keysize 4096 -validity 10000 -dname `"CN=Upload Key`" 2>nul"; Check "keytool (upload)"
        "storePassword=$pw`nkeyAlias=upload" | Set-Content -Encoding ascii upload-keystore.properties
        Write-Host "Created upload-keystore.jks + upload-keystore.properties. BACK BOTH UP - you need them for every Play update."
    }
    $props = @{}; Get-Content upload-keystore.properties | ForEach-Object { $k, $v = $_ -split '=', 2; $props[$k] = $v }
    Copy-Item build\unsigned.aab ShieldHotspot-release.aab -Force
    jarsigner -keystore upload-keystore.jks -storepass $props.storePassword -sigalg SHA256withRSA -digestalg SHA-256 ShieldHotspot-release.aab $props.keyAlias | Out-Null; Check "jarsigner"
    Write-Host "Built $PSScriptRoot\ShieldHotspot-release.aab"

    # Universal APK from the bundle, for GitHub Releases / sideloading.
    $version = ([xml](Get-Content AndroidManifest.xml)).manifest.versionName
    java -jar $bundletool build-apks --bundle=ShieldHotspot-release.aab --output=build\universal.apks --mode=universal --overwrite `
        --ks=upload-keystore.jks "--ks-pass=pass:$($props.storePassword)" --ks-key-alias=$($props.keyAlias); Check "bundletool build-apks"
    $u = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path build\universal.apks).Path)
    $apkName = "nvidia-shield-5ghz-hotspot-$version.apk"
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile(($u.Entries | Where-Object FullName -eq "universal.apk"), (Join-Path $PWD $apkName), $true)
    $u.Dispose()
    Write-Host "Built $PSScriptRoot\$apkName"
} finally {
    Pop-Location
}
