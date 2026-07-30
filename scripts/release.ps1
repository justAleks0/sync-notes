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

# Tools like vite and gradle write progress to stderr even when they succeed, and
# $ErrorActionPreference='Stop' turns that into a fatal NativeCommandError. Run native
# commands with it relaxed and judge them by their exit code, which is the real signal.
function Invoke-Native([scriptblock]$command, [string]$what) {
  $previous = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try { & $command } finally { $ErrorActionPreference = $previous }
  if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)" }
}

function Set-VersionIn($path, $pattern, $label) {
  $text = Read-Text $path
  # Guard against a silent no-op if the file is ever restructured. Text being
  # unchanged is fine — that just means it is already at this version.
  if ($text -notmatch $pattern) { throw "Version pattern did not match in $path" }
  Write-Text $path ($text -replace $pattern, "`${1}$Version`${2}")
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
Invoke-Native { npm install --prefix "$root\Website" --no-fund --no-audit --silent } "npm install (website)"
Invoke-Native { npm run build --prefix "$root\Website" } "website build"

Step "Building Windows installer"
Invoke-Native { npm install --prefix "$root\Computer Software" --no-fund --no-audit --silent } "npm install (desktop)"
Push-Location "$root\Computer Software"
try { Invoke-Native { npx --no-install electron-builder --win --publish never } "electron-builder" }
finally { Pop-Location }

Step "Building Android APK"
Push-Location "$root\Android App"
try { Invoke-Native { & .\gradlew.bat :app:assembleRelease --console=plain } "gradle assembleRelease" }
finally { Pop-Location }

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
# Re-running at the same version leaves nothing to commit, which is not a failure.
if (git -C $root diff --cached --name-only) {
  Invoke-Native { git -C $root commit -m "Release $tag" } "git commit"
} else {
  Write-Host "  nothing to commit — version files already current"
}
Invoke-Native { git -C $root tag -a $tag -m "Sync Notes $Version" } "git tag"
Invoke-Native { git -C $root push origin main } "git push"
Invoke-Native { git -C $root push origin $tag } "git push --tags"

Step "Creating GitHub release"
# Built by joining lines rather than a here-string: PowerShell 5.1 fails to parse
# here-strings in a file with LF-only line endings, which this repo uses.
$notes = @(
  "Sync Notes $Version",
  '',
  'Installed apps check this release automatically and offer the update in-app.',
  '',
  '- **Windows** - download the `.exe` installer',
  "- **Android** - download the ``.apk`` (you'll need to allow installs from your browser)",
  '- **Web** - always up to date, nothing to download'
) -join "`n"

Invoke-Native {
  gh release create $tag $installer.FullName $apk --repo justAleks0/sync-notes `
    --title "Sync Notes $Version" --notes $notes
} "gh release create"

Write-Host "`nReleased $tag" -ForegroundColor Green
