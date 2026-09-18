package ru.mcgl.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Мелочи вокруг файлов: суммы, обход папок, распаковка. */
public final class Files2 {

	private Files2() {
	}

	/** Куда лаунчер кладёт игру: рядом с остальными играми человека, а не в папку программы. */
	public static Path home() {
		return Os.dataHome("porakopatb");
	}

	/** Открыть папку или файл тем, чем система открывает такое обычно. */
	public static void reveal(Path what) {
		try {
			if (!Files.exists(what)) {
				Files.createDirectories(what.getParent() == null ? what : what.getParent());
			}
			java.awt.Desktop.getDesktop().open(Files.isDirectory(what) ? what.toFile()
					: (Files.exists(what) ? what.toFile() : what.getParent().toFile()));
		} catch (Exception quiet) {
			// Нечем открыть - не беда: путь игрок и так видит в окне.
		}
	}

	public static String sha1(Path file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buffer = new byte[1 << 16];
				int read;
				while ((read = in.read(buffer)) > 0) {
					digest.update(buffer, 0, read);
				}
			}
			StringBuilder out = new StringBuilder();
			for (byte b : digest.digest()) {
				out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return out.toString();
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	/** Файл уже такой, какой нужен: и размер, и сумма сошлись. */
	public static boolean matches(Path file, long size, String sha1) {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) != size) {
				return false;
			}
			return sha1 == null || sha1.isEmpty() || sha1.equalsIgnoreCase(sha1(file));
		} catch (IOException broken) {
			return false;
		}
	}

	public static List<Path> listFiles(Path dir) throws IOException {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (var stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile).forEach(out::add);
		}
		return out;
	}

	/**
	 * Распаковать библиотеки с машинным кодом рядом с игрой. Подписи и служебные папки
	 * пропускаем: игре они не нужны, а лишние файлы только мешают.
	 */
	public static void unpackNatives(Path jar, Path into) throws IOException {
		Files.createDirectories(into);
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				// Файлы лежат во вложенных папках вроде windows/x64/org/lwjgl/lwjgl.dll, а игре
				// нужен плоский список: берём только имя файла. META-INF - подписи, они лишние.
				if (entry.isDirectory() || name.startsWith("META-INF/")) {
					continue;
				}
				String lower = name.toLowerCase(java.util.Locale.ROOT);
				if (!lower.endsWith(".dll") && !lower.endsWith(".so") && !lower.endsWith(".dylib")) {
					continue;
				}
				Path target = into.resolve(name.substring(name.lastIndexOf('/') + 1));
				if (!Files.exists(target)) {
					Files.copy(zip, target);
				}
			}
		}
	}
}
