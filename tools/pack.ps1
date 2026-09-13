# Собирает установщик PoraKopatb-<версия>.exe и тот же лаунчер архивом.
#
# Запускать из корня репозитория:
#     powershell -ExecutionPolicy Bypass -File tools\pack.ps1
#
# Версия берётся из build.gradle - её надо поднимать при каждом выпуске: установщик с большим
# номером сам заменяет собой предыдущий, а с тем же номером Windows считает его повторной
# установкой и предлагает «изменить или удалить».
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

$line = Select-String -Path (Join-Path $root "build.gradle") -Pattern "^version\s*=\s*'([^']+)'"
if (-not $line) { throw "Не нашёл версию в build.gradle" }
$ver = $line.Matches[0].Groups[1].Value

$wix = $env:WIX_BIN
if (-not $wix) { $wix = Join-Path $PSScriptRoot "wix" }
if (-not (Test-Path (Join-Path $wix "candle.exe"))) {
	throw "Не нашёл WiX в $wix - положи candle.exe/light.exe туда или задай WIX_BIN"
}
$env:PATH = "$wix;$env:PATH"

$jar = Join-Path $root "build\libs\pora-launcher-$ver-all.jar"
if (-not (Test-Path $jar)) { throw "Сначала .\gradlew fatJar" }

# jpackage забирает всю папку целиком, поэтому держим в ней ровно один джарник.
$input = Join-Path $root "build\pkg"
Remove-Item $input -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $input | Out-Null
Copy-Item $jar $input

$dest = Join-Path $root "build\setup"
Remove-Item (Join-Path $dest "PoraKopatb-$ver.exe") -Force -ErrorAction SilentlyContinue

# Мастер установки: свои картинки и свой main.wxs. Двух строк с картинками у jpackage нет,
# поэтому берём его же описание установщика и добавляем их сами.
$art = Join-Path $root "build\wixres"
Remove-Item $art -Recurse -Force -ErrorAction SilentlyContinue
& "$java\bin\java.exe" (Join-Path $PSScriptRoot "MakeInstallerArt.java") $art
if ($LASTEXITCODE -ne 0) { throw "Не нарисовались картинки мастера" }
(Get-Content (Join-Path $PSScriptRoot "installer\main.wxs.in") -Raw -Encoding UTF8).Replace("@ART@", $art) |
	Set-Content (Join-Path $art "main.wxs") -Encoding UTF8

& "$java\bin\jpackage.exe" `
	--type exe `
	--name PoraKopatb `
	--app-version $ver `
	--vendor "porakopatb.com" `
	--description "Pora Kopatb launcher" `
	--input $input `
	--main-jar "pora-launcher-$ver-all.jar" `
	--main-class ru.mcgl.launcher.Launcher `
	--runtime-image $java `
	--icon $icon `
	--resource-dir $art `
	--java-options "-Xmx512m" `
	--win-dir-chooser `
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
	--app-version $ver `
	--input $input `
	--main-jar "pora-launcher-$ver-all.jar" `
	--main-class ru.mcgl.launcher.Launcher `
	--runtime-image $java `
	--icon $icon `
	--java-options "-Xmx512m" `
	--dest $app

$zip = Join-Path $root "build\PoraKopatb-$ver.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $app "PoraKopatb") -DestinationPath $zip

Get-Item (Join-Path $dest "PoraKopatb-$ver.exe"), $zip | Select-Object Name, Length, LastWriteTime
