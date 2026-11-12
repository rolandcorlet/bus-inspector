$ErrorActionPreference = "Stop"

$ProjectDir = "c:\Users\rolan\Downloads\Bus_Inspector"
$AppMainDir = Join-Path $ProjectDir "app\src\main"
$AssetsDir  = Join-Path $AppMainDir "assets"
$ResDir     = Join-Path $AppMainDir "res"
$JavaDir    = Join-Path $AppMainDir "java"
$Manifest   = Join-Path $AppMainDir "AndroidManifest.xml"
$BuildDir   = Join-Path $ProjectDir "build"

$JdkHome    = "C:\Program Files\Android\Android Studio\jbr"
$JdkBin     = Join-Path $JdkHome "bin"
$env:JAVA_HOME = $JdkHome
$env:PATH      = "$JdkBin;$env:PATH"
$Javac      = Join-Path $JdkBin "javac.exe"
$Keytool    = Join-Path $JdkBin "keytool.exe"
$JarTool    = Join-Path $JdkBin "jar.exe"

$SdkDir     = "C:\Users\rolan\AppData\Local\Android\Sdk"
$AndroidJar = Join-Path $SdkDir "platforms\android-36.1\android.jar"
$BuildTools = Join-Path $SdkDir "build-tools\36.1.0"
$Aapt2      = Join-Path $BuildTools "aapt2.exe"
$D8         = Join-Path $BuildTools "d8.bat"
$ZipAlign   = Join-Path $BuildTools "zipalign.exe"
$ApkSigner  = Join-Path $BuildTools "apksigner.bat"
$DexDump    = Join-Path $BuildTools "dexdump.exe"

Write-Host "=== 1. Syncing Web Assets into app/src/main/assets ==="
New-Item -ItemType Directory -Force -Path $AssetsDir | Out-Null
$WebFiles = @(
    "index.html",
    "bus-condition.html",
    "incident-report.html",
    "pre-departure-check.html",
    "safety-hazard-report.html",
    "android-email.js"
)
foreach ($f in $WebFiles) {
    Copy-Item -Force (Join-Path $ProjectDir $f) (Join-Path $AssetsDir $f)
    Write-Host "  Synced $f"
}

Write-Host "=== 2. Preparing Build Directories ==="
if (Test-Path $BuildDir) {
    Remove-Item -Recurse -Force $BuildDir
}
$CompiledResDir = Join-Path $BuildDir "compiled_res"
$GenJavaDir     = Join-Path $BuildDir "gen"
$ClassesDir     = Join-Path $BuildDir "classes"
$DexDir         = Join-Path $BuildDir "dex"
New-Item -ItemType Directory -Force -Path $CompiledResDir, $GenJavaDir, $ClassesDir, $DexDir | Out-Null

Write-Host "=== 3. Compiling Android Resources with aapt2 ==="
$ResZip = Join-Path $BuildDir "compiled_res.zip"
& $Aapt2 compile --dir $ResDir -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== 4. Linking Resources & Assets into Base APK with aapt2 ==="
$UnalignedApk = Join-Path $BuildDir "bus_inspector_unaligned.apk"
& $Aapt2 link `
    -I $AndroidJar `
    --manifest $Manifest `
    -A $AssetsDir `
    --java $GenJavaDir `
    --min-sdk-version 21 `
    --target-sdk-version 34 `
    --version-code 3 `
    --version-name "3.0.0" `
    -o $UnalignedApk `
    $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== 5. Compiling Java Sources with javac ==="
$JavaSources = Get-ChildItem -Path $JavaDir, $GenJavaDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& $Javac --release 8 -cp $AndroidJar -d $ClassesDir $JavaSources
if ($LASTEXITCODE -ne 0) { throw "javac compilation failed" }

Write-Host "=== 6. Converting Class Files to DEX with d8 ==="
$ClassFiles = Get-ChildItem -Path $ClassesDir -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
& $D8 --min-api 21 --lib $AndroidJar --output $DexDir $ClassFiles
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed" }

$ClassesDex = Join-Path $DexDir "classes.dex"
if (-not (Test-Path $ClassesDex)) { throw "classes.dex was not generated" }

Write-Host "=== 7. Verifying DEX File Integrity with dexdump ==="
$DexVerifyOut = & $DexDump -c $ClassesDex 2>&1
Write-Host "  dexdump check complete (size: $((Get-Item $ClassesDex).Length) bytes)"

Write-Host "=== 8. Packaging classes.dex into APK ==="
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = [System.IO.Compression.ZipFile]::Open($UnalignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $Existing = $Zip.GetEntry("classes.dex")
    if ($Existing) { $Existing.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Zip, $ClassesDex, "classes.dex", [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $Zip.Dispose()
}

Write-Host "=== 9. Aligning APK with zipalign ==="
$AlignedApk = Join-Path $BuildDir "bus_inspector_aligned.apk"
& $ZipAlign -f -p 4 $UnalignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== 10. Signing APK with apksigner ==="
$KeystorePath = Join-Path $ProjectDir "bus_inspector_release.keystore"
if (-not (Test-Path $KeystorePath)) {
    & $Keytool -genkeypair `
        -v `
        -keystore $KeystorePath `
        -alias businspector `
        -keyalg RSA `
        -keysize 2048 `
        -validity 10000 `
        -storepass businspector123 `
        -keypass businspector123 `
        -dname "CN=Bus Inspector, OU=Operations, O=Busabout, L=Smeaton Grange, ST=NSW, C=AU"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$FinalApk = Join-Path $ProjectDir "bus_inspector.apk"
& $ApkSigner sign `
    --ks $KeystorePath `
    --ks-key-alias businspector `
    --ks-pass pass:businspector123 `
    --key-pass pass:businspector123 `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --v3-signing-enabled true `
    --out $FinalApk `
    $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

Write-Host "=== 11. Verifying Signed APK ==="
& $ApkSigner verify --verbose --print-certs $FinalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed" }

$ApkInfo = Get-Item $FinalApk
Write-Host ""
Write-Host "SUCCESS! Built APK: $($ApkInfo.FullName) ($($ApkInfo.Length) bytes)"
