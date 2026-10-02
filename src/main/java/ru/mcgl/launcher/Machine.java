package ru.mcgl.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Отпечаток компьютера: по нему сайт считает, сколько учётных записей входит с одной машины.
 *
 * Зачем (01.10.2026). Правило сервера, согласованное с владельцем: не больше двух аккаунтов на один
 * компьютер и один интернет, а приглашённый с того же компьютера или адреса реферальной награды не
 * приносит. Адрес сайт видит сам, а компьютер виден только отсюда - поэтому лаунчер шлёт отпечаток
 * при входе (поле {@code machine}), и ровно так же его считает мод в игре.
 *
 * Почему MachineGuid. Windows создаёт его при установке системы и держит в реестре, в
 * {@code HKLM\SOFTWARE\Microsoft\Cryptography}. Он переживает переустановку лаунчера, игры и Java,
 * одинаков для всех учётных записей Windows на этом компьютере, и прочитать его может обычный
 * пользователь, без прав администратора. На Маке то же самое - IOPlatformUUID, на Линуксе -
 * machine-id.
 *
 * С компьютера уходит только хеш: SHA-256 от {@code "porakopatb-machine:"} и идентификатора в
 * нижнем регистре. Сам GUID машину не покидает и нигде не пишется - ни в лог, ни на диск, ни в
 * сообщение об ошибке. Приставка нужна, чтобы наш хеш нельзя было сопоставить с чужими базами,
 * где лежит тот же GUID.
 *
 * Отпечаток - не условие входа. Не прочитался (нет reg, команда повисла, незнакомая система) -
 * отдаём пустую строку и входим как раньше; что с ней делать, решает сайт.
 */
public final class Machine {

	private static final String SALT = "porakopatb-machine:";
	/**
	 * Дольше ждать команду незачем: вход и так идёт, пока она думает. Две секунды, как и у
	 * остальных команд отпечатка ({@link Hardware}): весь сбор обязан уложиться в четыре.
	 */
	private static final long WAIT_MS = 2000;

	private static volatile String cached;

	private Machine() {
	}

	/** Хеш отпечатка (64 шестнадцатеричных знака) или пустая строка. Считается один раз за запуск. */
	public static String id() {
		String known = cached;
		if (known == null) {
			synchronized (Machine.class) {
				if (cached == null) {
					cached = compute();
				}
				known = cached;
			}
		}
		return known;
	}

	private static String compute() {
		try {
			return hash(raw());
		} catch (Exception | LinkageError quiet) {
			return "";
		}
	}

	/**
	 * Систему берём у самой Java, а не у {@link Os}: там её можно перебить свойством, чтобы
	 * проверить список библиотек для чужой системы, а отпечаток должен быть от настоящей.
	 */
	private static String raw() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return windows();
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return mac();
		}
		return linux();
	}

	/**
	 * {@code reg query} вместо чтения реестра из Java: своего доступа к реестру у неё нет.
	 * {@code /reg:64} - чтобы 32-битная Java не попала в WOW6432Node, где MachineGuid нет.
	 * Ответ выглядит так: {@code MachineGuid    REG_SZ    <guid>}; слова REG_SZ Windows не
	 * переводит, так что на русской системе строка та же.
	 */
	private static String windows() {
		String out = run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid", "/reg:64");
		for (String line : out.lines().toList()) {
			String trimmed = line.trim();
			int type = trimmed.indexOf("REG_SZ");
			if (type > 0 && trimmed.regionMatches(true, 0, "MachineGuid", 0, "MachineGuid".length())) {
				return trimmed.substring(type + "REG_SZ".length()).trim();
			}
		}
		return "";
	}

	/** Строка вида {@code "IOPlatformUUID" = "<uuid>"}. */
	private static String mac() {
		for (String line : run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice").lines().toList()) {
			int eq = line.indexOf('=');
			if (eq > 0 && line.contains("IOPlatformUUID")) {
				return line.substring(eq + 1).replace('"', ' ').trim();
			}
		}
		return "";
	}

	private static String linux() {
		for (String file : List.of("/etc/machine-id", "/var/lib/dbus/machine-id")) {
			try {
				String id = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
				if (!id.isEmpty()) {
					return id;
				}
			} catch (IOException | RuntimeException none) {
				// нет файла - пробуем следующий
			}
		}
		return "";
	}

	static String hash(String raw) throws Exception {
		String clean = raw == null ? "" : raw.trim();
		if (clean.isEmpty()) {
			return "";
		}
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] sum = digest.digest((SALT + clean.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(sum);
	}

	/**
	 * Вывод команды или пустая строка, если она не запустилась, не уложилась в две секунды или
	 * ответила ошибкой. Вывод читаем в своём потоке: повисшая команда не должна держать вход.
	 */
	private static String run(String... command) {
		Process process = null;
		try {
			process = new ProcessBuilder(command)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();
			process.getOutputStream().close();
			Process running = process;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Thread reader = new Thread(() -> {
				try (InputStream in = running.getInputStream()) {
					in.transferTo(out);
				} catch (IOException ignored) {
					// поток закрылся вместе с процессом - что успели, то и есть
				}
			}, "отпечаток");
			reader.setDaemon(true);
			reader.start();
			long started = System.nanoTime();
			if (!process.waitFor(WAIT_MS, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
				return "";
			}
			long left = WAIT_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			reader.join(Math.max(100, left));
			if (reader.isAlive()) {
				return "";
			}
			// Нужные нам слова и сам идентификатор - латиница, так что кодировка консоли
			// (на русской Windows это cp866) их не портит.
			return out.toString(StandardCharsets.ISO_8859_1);
		} catch (IOException | RuntimeException quiet) {
			return "";
		} catch (InterruptedException stop) {
			Thread.currentThread().interrupt();
			return "";
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}
}
