param(
    [ValidateSet('smoke', 'commands', 'explanations', 'real')]
    [string]$Batch = 'smoke',
    [switch]$Live,
    [ValidateRange(1, 16)]
    [int]$MaxCalls
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    $planned = @{ smoke = 6; commands = 12; explanations = 4; real = 2 }[$Batch]
    if ($Live -and (-not $PSBoundParameters.ContainsKey('MaxCalls') -or $MaxCalls -lt $planned)) {
        throw "Live batch '$Batch' requires an explicit -MaxCalls of at least $planned (maximum 16). No API calls made."
    }
    if ($Live -and [string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) {
        throw 'OPENAI_API_KEY is absent from this terminal. Configure it privately; never paste it into reports.'
    }
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $settingsPath = Join-Path $projectRoot '.vscode/settings.json'
        if (Test-Path -LiteralPath $settingsPath) {
            $env:JAVA_HOME = (Get-Content -LiteralPath $settingsPath -Raw | ConvertFrom-Json).'java.configuration.runtimes'[0].path
        }
    }
    $javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    # Compile only. No JUnit execution, packaging, clean, or dependency-reduced-pom rewrite.
    & mvn -o test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Offline compilation failed. Resolve the build before enabling live calls.' }
    $gsonVersion = ([xml](Get-Content -LiteralPath 'pom.xml' -Raw)).project.properties.'gson.version'
    $gsonJar = Join-Path $env:USERPROFILE ".m2/repository/com/google/code/gson/gson/$gsonVersion/gson-$gsonVersion.jar"
    if (-not (Test-Path -LiteralPath $gsonJar)) { throw 'Cached Gson JAR missing at the default Maven repository path.' }
    $evalArgs = @('--batch', $Batch)
    if ($Live) { $evalArgs += @('--live', '--max-calls', [string]$MaxCalls) }
    & $javaExe '-Dfile.encoding=UTF-8' -cp "target/test-classes;target/classes;$gsonJar" nettransfer.evaluation.Milestone8LiveEvaluation @evalArgs
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation stopped. Review any saved results before attempting another paid batch.' }
} finally {
    Pop-Location
}
