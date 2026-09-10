$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleWrapper = Join-Path $projectRoot "gradlew.bat"
$buildFile = Join-Path $projectRoot "build.gradle.kts"

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw "Gradle Wrapper 不存在：$gradleWrapper"
}

# 从构建配置读取当前版本，确保检查的产物与 Gradle 实际输出一致。
$versionMatch = Select-String -LiteralPath $buildFile -Pattern '^version\s*=\s*"([^"]+)"\s*$' | Select-Object -First 1
if (-not $versionMatch) {
    throw "无法从 build.gradle.kts 读取项目版本。"
}

$version = $versionMatch.Matches[0].Groups[1].Value
$artifactPath = Join-Path $projectRoot "build\distributions\CodeDeck-$version.zip"

Write-Host "正在构建并验证 CodeDeck $version..."
Push-Location $projectRoot
try {
    & $gradleWrapper clean check buildPlugin verifyPlugin
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 打包失败，退出代码：$LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

# Gradle 成功后再次确认发行包存在，并输出便于发布校验的信息。
if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
    throw "Gradle 已完成，但未找到发行包：$artifactPath"
}

$artifact = Get-Item -LiteralPath $artifactPath
$hash = Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256

Write-Host ""
Write-Host "打包完成"
Write-Host "产物：$($artifact.FullName)"
Write-Host "大小：$($artifact.Length) 字节"
Write-Host "SHA-256：$($hash.Hash)"
