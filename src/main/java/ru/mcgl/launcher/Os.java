package ru.mcgl.launcher;

import java.nio.file.Path;

/**
 * Какая это система и какое у неё железо.
 *
 * Раньше лаунчер знал только Windows: правила библиотек сверялись со строкой "windows", а
 * разрядность считалась как «64 или не 64». На Маке из-за первого приезжали windows-натив и
 * lwjgl.dll, на Apple Silicon из-за второго - сборка под Intel. Владелец 18.09.2026 спросил, можно
 * ли пустить сборку на макбуки и линуксы; всё, что для этого нужно, собрано здесь.
 *
 * Имена - те же, что в манифесте Mojang: {@code windows}, {@code osx}, {@code linux} и
 * {@code x86}, {@code x86_64}, {@code arm64}. Их же подставляют в правила библиотек и в имена
 * нативных джарников ({@code natives-macos-arm64} и прочие).
 *
 * Обе строки можно перебить свойствами {@code -Dporakopatb.os=} и {@code -Dporakopatb.arch=}: это
 * единственный способ проверить чужую систему, не имея её под рукой, и он же выручит, если Java
 * однажды назовёт систему непривычно.
 */
public final class Os {

	public static final String WINDOWS = "windows";
	public static final String MAC = "osx";
	public static final String LINUX = "linux";

	private static final String NAME = detectName();
	private static final String ARCH = detectArch();

	private Os() {
	}

	public static String name() {
		return NAME;
	}

	public static String arch() {
		return ARCH;
	}

	public static boolean windows() {
		return WINDOWS.equals(NAME);
	}

	public static boolean mac() {
		return MAC.equals(NAME);
	}

	/** Куда система кладёт данные программ: там и живёт игра. */
	public static Path dataHome(String folder) {
		if (windows()) {
			String appData = System.getenv("APPDATA");
			if (appData != null && !appData.isBlank()) {
				return Path.of(appData, "." + folder);
			}
		}
		if (mac()) {
			// На Маке это общепринятое место для данных программ, и Finder показывает его
			// отдельным разделом. Точка в начале имени там прячет папку, а прятать её незачем.
			return Path.of(System.getProperty("user.home"), "Library", "Application Support", folder);
		}
		return Path.of(System.getProperty("user.home"), "." + folder);
	}

	private static String detectName() {
		String forced = System.getProperty("porakopatb.os", "").trim();
		if (!forced.isEmpty()) {
			return forced;
		}
		String raw = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (raw.contains("win")) {
			return WINDOWS;
		}
		if (raw.contains("mac") || raw.contains("darwin")) {
			return MAC;
		}
		return LINUX;
	}

	private static String detectArch() {
		String forced = System.getProperty("porakopatb.arch", "").trim();
		if (!forced.isEmpty()) {
			return forced;
		}
		String raw = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
		if (raw.contains("aarch64") || raw.contains("arm64")) {
			return "arm64";
		}
		if (raw.contains("64")) {
			return "x86_64";
		}
		return "x86";
	}
}
