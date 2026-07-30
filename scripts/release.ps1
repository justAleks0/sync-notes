<#
.SYNOPSIS
  Builds every client at one version and publishes them as a GitHub release.

.DESCRIPTION
  This is what makes the in-app update check work. Each client compares its own
  compiled-in version against the newest release tag, so the tag and the built
  artifacts have to agree — that is why one script owns both.

  Builds locally rather than in CI on purpose: the APK is signed with this machine's
  debug keystore, and CI would generate a different one every run, so every update
  would fail to install with a signature mismatch. Move to CI once there is a real
  release keystore in GitHub secrets.

.EXAMPLE
  ./scripts/release.ps1 -Version 0.2.0
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^\d+\.\d+\.\d+$')]
  [string]$Version,

  # Build everything and stop, without creating the GitHub release.
  [switch]$NoPublish
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tag = "v$Version"

function Step($message) { Write-Host "`n=== $message ===" -ForegroundColor Cyan }

# Windows PowerShell 5.1 reads with Get-Content as ANSI and writes -Encoding utf8 with
# a BOM, which mangles non-ASCII and makes package.json unparseable. Go through .NET so
# files round-trip as UTF-8 with no BOM.
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function Read-Text($path) { [System.IO.File]::ReadAllText((Resolve-Path $path), $Utf8NoBom) }
function Write-Text($path, $text) {
  [System.IO.File]::WriteAllText((Resolve-Path $path), $text, $Utf8NoBom)
}

function Set-VersionIn($path, $pattern, $label) {
  $text = Read-Text $path
  $updated = $text -replace $pattern, "`${1}$Version`${2}"
  if ($updated -eq $text) { throw "Version pattern did not match in $path" }
  Write-Text $path $updated
  Write-Host "  $label"
}

if (git -C $root status --porcelain) {
  throw "Working tree is dirty. Commit or stash first so the release matches a real commit."
}

# --- stamp the version into every client -------------------------------------

Step "Setting version to $Version"

Set-VersionIn "$root\Website\package.json" '("version":\s*")[^"]+(")' "Website/package.json"
Set-VersionIn "$root\Computer Software\package.json" '("version":\s*")[^"]+(")' "Computer Software/package.json"
Set-VersionIn "$root\Android App\app\build.gradle.kts" '(val appVersionName = ")[^"]+(")' "Android App/app/build.gradle.kts"

# --- build --------------------------------------------------------------------

Step "Building website"
npm install --prefix "$root\Website" --no-fund --no-audit --silent
npm run build --prefix "$root\Website"

Step "Building Windows installer"
npm install --prefix "$root\Computer Software" --no-fund --no-audit --silent
Push-Location "$root\Computer Software"
try { npx --no-install electron-builder --win --publish never } finally { Pop-Location }

Step "Building Android APK"
Push-Location "$root\Android App"
try { & .\gradlew.bat :app:assembleRelease --console=plain } finally { Pop-Location }

# --- collect artifacts --------------------------------------------------------

$installer = Get-ChildItem "$root\Computer Software\dist" -Filter "*.exe" -File |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1
$apkBuilt = Get-ChildItem "$root\Android App\app\build\outputs\apk\release" -Filter "*.apk" -File |
  Sort-Object LastWriteTime -Descending | Select-Object -First 1

if (-not $installer) { throw "No .exe produced in Computer Software/dist" }
if (-not $apkBuilt)  { throw "No .apk produced in Android App/app/build/outputs/apk/release" }

# Give the APK a versioned name so the release page is readable.
$apk = Join-Path $apkBuilt.DirectoryName "sync-notes-$Version.apk"
Copy-Item $apkBuilt.FullName $apk -Force

Step "Artifacts"
Write-Host "  $($installer.Name)  ($([math]::Round($installer.Length/1MB,1)) MB)"
Write-Host "  $(Split-Path $apk -Leaf)  ($([math]::Round((Get-Item $apk).Length/1MB,1)) MB)"

if ($NoPublish) {
  Write-Host "`n-NoPublish set. Nothing was pushed or released." -ForegroundColor Yellow
  return
}

# --- publish ------------------------------------------------------------------

Step "Committing and tagging $tag"
git -C $root add -A
git -C $root commit -m "Release $tag"
git -C $root tag -a $tag -m "Sync Notes $Version"
git -C $root push origin main
git -C $root push origin $tag

Step "Creating GitHub release"
$notes = @"
Sync Notes $Version

Installed apps check this release automatically and offer the update in-app.

- **Windows** — download the ``.exe`` installer
- **Android** — download the ``.apk`` (you'll need to allow installs from your browser)
- **Web** — always up to date, nothing to download
"@

gh release create $tag $installer.FullName $apk --repo justAleks0/sync-notes --title "Sync Notes $Version" --notes $notes

Write-Host "`nReleased $tag" -ForegroundColor Green
