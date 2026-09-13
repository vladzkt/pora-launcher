package ru.mcgl.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Собирает команду запуска игры и запускает её. */
public final class Game {

	private Game() {
	}

	/**
	 * Тот же идентификатор, что сервер посчитает сам. Сервер работает без проверки Mojang,
	 * а игрока пускает наш сайт по разовому ключу, поэтому важно лишь, чтобы обе стороны
	 * считали одинаково.
	 */
	public static UUID offlineId(String nick) {
		return UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
	}

	/** Java, которой запускаем игру: та же, что вложена в сам лаунчер. */
	public static Path java() {
		Path home = Path.of(System.getProperty("java.home"));
		Path windows = home.resolve("bin").resolve("javaw.exe");
		if (java.nio.file.Files.isRegularFile(windows)) {
			return windows;
		}
		Path console = home.resolve("bin").resolve("java.exe");
		return java.nio.file.Files.isRegularFile(console) ? console : home.resolve("bin").resolve("java");
	}

	public static List<String> command(Path root, Installer.Plan plan, Site.Account account,
			String minecraft, String fabric, int memoryMb) {
		StringBuilder classpath = new StringBuilder();
		for (Path entry : plan.classpath()) {
			if (classpath.length() > 0) {
				classpath.append(File.pathSeparator);
			}
			classpath.append(entry.toAbsolutePath());
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(java().toString());
		cmd.add("-Xmx" + memoryMb + "M");
		cmd.add("-Xms" + Math.min(memoryMb, 1024) + "M");
		cmd.add("-Djava.library.path=" + plan.natives().toAbsolutePath());
		// Разовый ключ для входа: мод на сервере проверит его через сайт и пустит игрока.
		cmd.add("-Dporakopatb.token=" + account.token());
		cmd.add("-Dporakopatb.site=" + Site.BASE);
		cmd.add("-cp");
		cmd.add(classpath.toString());
		cmd.add(plan.mainClass());

		cmd.add("--username");
		cmd.add(account.nick());
		cmd.add("--version");
		cmd.add("fabric-loader-" + fabric + "-" + minecraft);
		cmd.add("--gameDir");
		cmd.add(root.toAbsolutePath().toString());
		cmd.add("--assetsDir");
		cmd.add(plan.assets().toAbsolutePath().toString());
		cmd.add("--assetIndex");
		cmd.add(plan.assetIndex());
		cmd.add("--uuid");
		cmd.add(offlineId(account.nick()).toString().replace("-", ""));
		cmd.add("--accessToken");
		cmd.add("0");
		cmd.add("--userType");
		cmd.add("msa");
		cmd.add("--versionType");
		cmd.add("release");
		return cmd;
	}

	public static Process start(Path root, Installer.Plan plan, Site.Account account,
			String minecraft, String fabric, int memoryMb) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(command(root, plan, account, minecraft, fabric, memoryMb));
		builder.directory(root.toFile());
		builder.redirectErrorStream(true);
		builder.redirectOutput(root.resolve("game.log").toFile());
		return builder.start();
	}
}
