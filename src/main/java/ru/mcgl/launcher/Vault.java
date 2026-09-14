package ru.mcgl.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Хранилище модов: на диске они лежат зашифрованными и не джарниками.
 *
 * Задача поставлена владельцем и словами очевидца: «мкгл я когда скачивал, перерыл все файлы
 * абсолютно, не нашёл модов, я бы просто тогда скачал их моды и не делал свою сборку». Ровно этот
 * случай тут и закрывается - человек лезет в папки, видит `a7f3.dat`, который не открывается
 * ничем, и уходит.
 *
 * Как устроено:
 *
 * В папке игры модов нет вовсе - ни папки `mods`, ни джарников. Файлы лежат в кладовой рядом с
 * лаунчером, под именами из шестнадцатеричных цифр и с расширением `.dat`: ни поиск по `*.jar`,
 * ни архиватор их не найдут.
 *
 * Расшифровываются они только на время игры - во временную папку со случайным именем, которая
 * удаляется, как только игра закрылась. Fabric получает пути к ним через `-Dfabric.addMods`, ему
 * папка `mods` не нужна.
 *
 * Ключ приходит от сайта при входе и нигде не хранится: нет учётной записи - нет и ключа.
 *
 * Где предел, чтобы не обманываться: пока игра запущена, расшифрованные файлы лежат во временной
 * папке, и кто знает, где смотреть, тот их возьмёт; путь виден и в командной строке процесса.
 * Абсолютной защиты у того, что исполняется на чужой машине, не бывает - речь о планке, и планка
 * здесь ровно та, о которую споткнулся сам владелец.
 */
public final class Vault {

	/** Метка в начале файла: по ней видно, что это наш контейнер, а не мусор. */
	private static final byte[] MAGIC = { 'P', 'K', 'V', 1 };
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private static final SecureRandom RANDOM = new SecureRandom();

	private Vault() {
	}

	/**
	 * Где лежит зашифрованное: рядом с самим лаунчером, а не в папке игры.
	 *
	 * Это важнее, чем кажется. Папку игры игрок открывает сам - туда лезут за скриншотами, мирами
	 * и настройками, - и кладовая в ней нашлась бы первой же. Папка установки лаунчера в
	 * %LOCALAPPDATA% вне поля зрения: туда не ходят вовсе.
	 */
	public static Path store() {
		String local = System.getenv("LOCALAPPDATA");
		if (local != null && !local.isBlank()) {
			return Path.of(local, "PoraKopatb", ".data");
		}
		return Path.of(System.getProperty("user.home"), ".porakopatb-data");
	}

	/** Имя контейнера для файла сборки: от пути, чтобы по нему нельзя было угадать мод. */
	public static Path container(String path) {
		return store().resolve(name(path) + ".dat");
	}

	private static String name(String path) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] hash = digest.digest(path.getBytes(StandardCharsets.UTF_8));
			StringBuilder out = new StringBuilder();
			for (int i = 0; i < 6; i++) {
				out.append(String.format("%02x", hash[i]));
			}
			return out.toString();
		} catch (Exception broken) {
			return Integer.toHexString(path.hashCode());
		}
	}

	/** Кладёт файл в кладовую зашифрованным и убирает открытый. */
	public static void put(Path plain, String path, String key) throws IOException {
		Path target = container(path);
		Files.createDirectories(target.getParent());
		byte[] iv = new byte[IV_BYTES];
		RANDOM.nextBytes(iv);
		try (OutputStream raw = Files.newOutputStream(target)) {
			raw.write(MAGIC);
			raw.write(iv);
			try (OutputStream out = new CipherOutputStream(raw, cipher(Cipher.ENCRYPT_MODE, key, iv));
					InputStream in = Files.newInputStream(plain)) {
				in.transferTo(out);
			}
		} catch (IOException broken) {
			Files.deleteIfExists(target);
			throw broken;
		} catch (Exception broken) {
			Files.deleteIfExists(target);
			throw new IOException("Не удалось убрать файл в кладовую: " + broken.getMessage());
		}
		Files.deleteIfExists(plain);
	}

	/** Достаёт файл из кладовой в открытом виде. */
	public static void take(String path, Path plain, String key) throws IOException {
		Path source = container(path);
		Files.createDirectories(plain.getParent());
		try (InputStream raw = Files.newInputStream(source)) {
			byte[] magic = raw.readNBytes(MAGIC.length);
			byte[] iv = raw.readNBytes(IV_BYTES);
			if (magic.length != MAGIC.length || iv.length != IV_BYTES) {
				throw new IOException("Испорченный файл сборки");
			}
			try (InputStream in = new CipherInputStream(raw, cipher(Cipher.DECRYPT_MODE, key, iv));
					OutputStream out = Files.newOutputStream(plain)) {
				in.transferTo(out);
			}
		} catch (IOException broken) {
			throw broken;
		} catch (Exception broken) {
			throw new IOException("Не удалось прочитать файл сборки: " + broken.getMessage());
		}
	}

	/**
	 * Раскладывает всю сборку во временную папку и возвращает пути.
	 *
	 * Имя папки случайное: по постоянному её нашли бы один раз и дальше знали бы наизусть.
	 */
	public static Path unpack(List<Site.PackFile> files, String key) throws IOException {
		sweep();
		Path where = Files.createTempDirectory("pk-");
		for (Site.PackFile file : files) {
			if (!Files.isRegularFile(container(file.path()))) {
				continue;
			}
			String plain = file.path().substring(file.path().lastIndexOf('/') + 1);
			take(file.path(), where.resolve(plain), key);
		}
		return where;
	}

	/** Убирает за собой: и свежую папку, и хвосты от прошлых запусков, если игра падала. */
	public static void sweep() {
		Path temp = Path.of(System.getProperty("java.io.tmpdir", "."));
		try (Stream<Path> list = Files.list(temp)) {
			list.filter(Files::isDirectory)
					.filter(dir -> dir.getFileName().toString().startsWith("pk-"))
					.forEach(Vault::erase);
		} catch (IOException ignored) {
			// Не смогли прибраться - не беда: в следующий раз попробуем снова.
		}
	}

	public static void erase(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Файл держит игра, которая ещё не отпустила его: удалится при следующей уборке.
				}
			});
		} catch (IOException ignored) {
			// см. выше
		}
	}

	private static Cipher cipher(int mode, String key, byte[] iv) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		SecretKeySpec spec = new SecretKeySpec(digest.digest(key.getBytes(StandardCharsets.UTF_8)), "AES");
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, spec, new GCMParameterSpec(TAG_BITS, iv));
		return cipher;
	}
}
