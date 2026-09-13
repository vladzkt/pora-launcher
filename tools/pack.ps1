# Собирает установщик PoraKopatb-1.0.0.exe и тот же лаунчер архивом.
#
# Запускать из корня репозитория:
#     powershell -ExecutionPolicy Bypass -File tools\pack.ps1
#
# Строки установщика латиницей: WiX 3.14 валится на кириллице в vendor и меню.
# Нужен сам WiX 3.14 (candle.exe/light.exe): путь берётся из переменной WIX_BIN, иначе
# ищется рядом в tools\wix. Без него jpackage не умеет делать --type exe.
#
# Среда исполнения - Java 21 из папки игры: у неё нет jmods, поэтому кладём её готовой
# через --runtime-image, а не собираем jlink'ом.

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$java = "M:\MINE\runtime\java-runtime-delta\windows\java-runtime-delta"
$icon = Join-Path $root "src\main\resources\ru\mcgl\launcher\icon.ico"

$wix = $env:WIX_BIN
if (-not $wix) { $wix = Join-Path $PSScriptRoot "wix" }
if (-not (Test-Path (Join-Path $wix "candle.exe"))) {
	throw "Не нашёл WiX в $wix - положи candle.exe/light.exe туда или задай WIX_BIN"
}
$env:PATH = "$wix;$env:PATH"

$jar = Join-Path $root "build\libs\pora-launcher-1.0.0-all.jar"
if (-not (Test-Path $jar)) { throw "Сначала .\gradlew fatJar" }

# jpackage забирает всю папку целиком, поэтому держим в ней ровно один джарник.
$input = Join-Path $root "build\pkg"
Remove-Item $input -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $input | Out-Null
Copy-Item $jar $input

$dest = Join-Path $root "build\setup"
Remove-Item (Join-Path $dest "PoraKopatb-1.0.0.exe") -Force -ErrorAction SilentlyContinue

& "$java\bin\jpackage.exe" `
	--type exe `
	--name PoraKopatb `
	--app-version 1.0.0 `
	--vendor "porakopatb.com" `
	--description "Pora Kopatb launcher" `
	--input $input `
	--main-jar pora-launcher-1.0.0-all.jar `
	--main-class ru.mcgl.launcher.Launcher `
	--runtime-image $java `
	--icon $icon `
	--java-options "-Xmx512m" `
	--win-shortcut `
	--win-menu `
	--win-menu-group "Pora Kopatb" `
	--win-per-user-install `
	--dest $dest

# Тот же лаунчер папкой и архивом: для тех, кто не хочет ставить программу.
$app = Join-Path $root "build\app"
Remove-Item $app -Recurse -Force -ErrorAction SilentlyContinue
& "$java\bin\jpackage.exe" `
	--type app-image `
	--name PoraKopatb `
	--app-version 1.0.0 `
	--input $input `
	--main-jar pora-launcher-1.0.0-all.jar `
	--main-class ru.mcgl.launcher.Launcher `
	--runtime-image $java `
	--icon $icon `
	--java-options "-Xmx512m" `
	--dest $app

$zip = Join-Path $root "build\PoraKopatb-1.0.0.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $app "PoraKopatb") -DestinationPath $zip

Get-Item (Join-Path $dest "PoraKopatb-1.0.0.exe"), $zip | Select-Object Name, Length, LastWriteTime
