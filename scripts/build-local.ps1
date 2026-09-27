param(
    [string]$GameDirectory = 'C:\Program Files (x86)\Steam\steamapps\common\SlayTheSpire',
    [string]$Javac = 'javac'
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$steamApps = Split-Path (Split-Path $GameDirectory -Parent) -Parent
$gameJar = Join-Path $GameDirectory 'desktop-1.0.jar'
$modTheSpireJar = Join-Path $steamApps 'workshop\content\646570\1605060445\ModTheSpire.jar'
$baseModJar = Join-Path $steamApps 'workshop\content\646570\1605833019\BaseMod.jar'

foreach ($dependency in @($gameJar, $modTheSpireJar, $baseModJar)) {
    if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) {
        throw "Missing dependency: $dependency"
    }
}

if (-not (Get-Command $Javac -ErrorAction SilentlyContinue)) {
    throw "Java compiler not found: $Javac. Pass -Javac with the path to a JDK javac.exe."
}

$targetDirectory = Join-Path $projectRoot 'target'
$classesDirectory = Join-Path $targetDirectory 'local-classes'
$jarPath = Join-Path $targetDirectory 'MCPTheSpire.jar'
$projectPrefix = $projectRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if (-not [IO.Path]::GetFullPath($classesDirectory).StartsWith($projectPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Build output is outside the project directory.'
}
if (Test-Path -LiteralPath $classesDirectory) {
    Remove-Item -LiteralPath $classesDirectory -Recurse -Force
}
$null = New-Item -ItemType Directory -Force -Path $classesDirectory

$sourceDirectory = Join-Path $projectRoot 'src\main\java'
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceDirectory -Recurse -Filter '*.java' -File | ForEach-Object FullName)
$classPath = "$gameJar;$modTheSpireJar;$baseModJar"
& $Javac --release 8 -proc:none -cp $classPath -d $classesDirectory $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed with exit code $LASTEXITCODE."
}

$resourcesDirectory = Join-Path $projectRoot 'src\main\resources'
Copy-Item -LiteralPath (Join-Path $resourcesDirectory 'badge.png') -Destination $classesDirectory

[xml]$pom = Get-Content -LiteralPath (Join-Path $projectRoot 'pom.xml') -Raw
$ns = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
$ns.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
$metadata = Get-Content -LiteralPath (Join-Path $resourcesDirectory 'ModTheSpire.json') -Raw | ConvertFrom-Json
$metadata.modid = $pom.SelectSingleNode('/m:project/m:artifactId', $ns).InnerText
$metadata.name = $pom.SelectSingleNode('/m:project/m:name', $ns).InnerText
$metadata.description = $pom.SelectSingleNode('/m:project/m:description', $ns).InnerText
$metadata.version = $pom.SelectSingleNode('/m:project/m:version', $ns).InnerText
$metadata.sts_version = $pom.SelectSingleNode('/m:project/m:properties/m:SlayTheSpire.version', $ns).InnerText
$metadata.mts_version = $pom.SelectSingleNode('/m:project/m:properties/m:ModTheSpire.version', $ns).InnerText
$metadataPath = Join-Path $classesDirectory 'ModTheSpire.json'
[IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json -Depth 8), (New-Object Text.UTF8Encoding($false)))

Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path -LiteralPath $jarPath) {
    Remove-Item -LiteralPath $jarPath -Force
}
[IO.Compression.ZipFile]::CreateFromDirectory($classesDirectory, $jarPath, [IO.Compression.CompressionLevel]::Optimal, $false)
Write-Output $jarPath
