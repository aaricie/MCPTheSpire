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

$javacCommand = Get-Command $Javac -ErrorAction SilentlyContinue
if (-not $javacCommand) {
    throw "Java compiler not found: $Javac. Pass -Javac with the path to a JDK javac.exe."
}
$java = Join-Path (Split-Path $javacCommand.Source -Parent) 'java.exe'
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
    throw "Java runtime not found next to compiler: $java"
}

$targetDirectory = Join-Path $projectRoot 'target'
$classesDirectory = Join-Path $targetDirectory 'local-classes'
$testClassesDirectory = Join-Path $targetDirectory 'local-test-classes'
$jarPath = Join-Path $targetDirectory 'MCPTheSpire.jar'
$projectPrefix = $projectRoot.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
foreach ($buildDirectory in @($classesDirectory, $testClassesDirectory)) {
    if (-not [IO.Path]::GetFullPath($buildDirectory).StartsWith($projectPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Build output is outside the project directory.'
    }
    if (Test-Path -LiteralPath $buildDirectory) {
        Remove-Item -LiteralPath $buildDirectory -Recurse -Force
    }
    $null = New-Item -ItemType Directory -Force -Path $buildDirectory
}

$sourceDirectory = Join-Path $projectRoot 'src\main\java'
$sourceFiles = @(Get-ChildItem -LiteralPath $sourceDirectory -Recurse -Filter '*.java' -File | ForEach-Object FullName)
$classPath = "$gameJar;$modTheSpireJar;$baseModJar"
# ModTheSpire reads patch parameter names from the LocalVariableTable.
& $javacCommand.Source --release 8 -g -proc:none -Xlint:-options -cp $classPath -d $classesDirectory $sourceFiles
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed with exit code $LASTEXITCODE."
}

$metadataTest = Join-Path $projectRoot 'src\test\java\mcpthespire\patches\PatchParameterMetadataTest.java'
& $javacCommand.Source --release 8 -proc:none -Xlint:-options -cp "$classesDirectory;$classPath" -d $testClassesDirectory $metadataTest
if ($LASTEXITCODE -ne 0) {
    throw "Patch metadata test compilation failed with exit code $LASTEXITCODE."
}
& $java -cp "$testClassesDirectory;$classesDirectory;$classPath" mcpthespire.patches.PatchParameterMetadataTest $classesDirectory
if ($LASTEXITCODE -ne 0) {
    throw "Patch metadata test failed with exit code $LASTEXITCODE."
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
