# FTolk-G 배포 스크립트
# 사용법 (프로젝트 폴더에서):  powershell -ExecutionPolicy Bypass -File .\publish.ps1
#
# 하는 일:
#   1. 배포용 APK 빌드 (코드를 수정했으면 버전이 0.1 올라가요)
#   2. 변경 내용을 Git 에 커밋하고 GitHub 에 올리기
#   3. GitHub Release "v버전" 을 만들고 APK 첨부
# 이미 같은 버전 릴리스가 있으면 새로 만들지 않고 멈춰요.

# 'Continue': git/gh 는 정상일 때도 stderr 에 글을 써서, 'Stop' 이면 PowerShell 5.1 에서 멈춰요.
# 대신 각 단계에서 $LASTEXITCODE 로 실패를 확인해요.
$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot' }

Write-Host '1) 배포용 APK 빌드...'
& .\gradlew.bat assembleRelease --console=plain -q
if ($LASTEXITCODE -ne 0) { throw '빌드 실패' }

$code = [int]((Select-String -Path version.properties -Pattern '^VERSION_CODE=(\d+)').Matches[0].Groups[1].Value)
$version = '{0}.{1}' -f [math]::Floor($code / 10), ($code % 10)
$tag = "v$version"
$apk = "app\build\outputs\apk\release\FTolk-G-$tag-release.apk"
if (-not (Test-Path $apk)) { throw "APK 가 없어요: $apk" }
Write-Host "   -> $apk"

gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) {
    Write-Host "릴리스 $tag 가 이미 있어요. 코드를 수정한 뒤 다시 실행하면 새 버전이 만들어져요."
    exit 0
}

Write-Host '2) Git 커밋 + 올리기...'
git add -A
git diff --cached --quiet
if ($LASTEXITCODE -ne 0) { git commit -m "FTolk-G $tag" | Out-Null }
git push origin HEAD
if ($LASTEXITCODE -ne 0) { throw 'git push 실패' }

Write-Host "3) GitHub 릴리스 $tag 만들기..."
gh release create $tag $apk --title "FTolk-G $tag" --notes "FTolk-G $tag 번역기 앱. 아래 APK 를 받아 설치하세요."
if ($LASTEXITCODE -ne 0) { throw '릴리스 만들기 실패' }

$repo = gh repo view --json url -q .url
Write-Host ''
Write-Host "완료! 최신 버전 다운로드 페이지: $repo/releases/latest"
