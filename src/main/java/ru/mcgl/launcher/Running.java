package ru.mcgl.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Какие игры этого лаунчера идут на компьютере прямо сейчас и сколько памяти им отдано (18.10.2026).
 *
 * <p>Зачем. Каждой игре лаунчер отдаёт половину памяти машины. Игрок с двумя аккаунтами запускал лаунчер
 * дважды, и две игры вместе просили всю память: Windows выгружала её на диск, лагали оба окна. Теперь
 * каждый запуск оставляет файл {@code running/<pid лаунчера>.mb} с числом мегабайт, а следующий запуск,
 * найдя живые чужие файлы, берёт меньше ({@code Launcher.memory}).
 *
 * <p>Номер - процесса лаунчера, а не игры: лаунчер живёт ровно столько, сколько его игра (он ждёт её конца,
 * чтобы стереть расшифрованные моды), и свой номер знает без поисков. Файл мёртвого процесса - след
 * падения, его убираем. Номер процесса Windows отдаёт повторно, поэтому живой процесс засчитывается,
 * только если он запущен раньше, чем записан файл: чужая программа, получившая тот же номер позже, - нет.
 */
final class Running {

	private static final String SUFFIX = ".mb";

	private Running() {
	}

	private static Path dir() {
		return Files2.home().resolve("running");
	}

	private static Path mine() {
		return dir().resolve(ProcessHandle.current().pid() + SUFFIX);
	}

	/** Сколько мегабайт отдано другим идущим сейчас играм этого лаунчера; ноль - их нет. */
	static long othersMb() {
		long self = ProcessHandle.current().pid();
		long sum = 0;
		if (!Files.isDirectory(dir())) {
			return 0;
		}
		try (var list = Files.list(dir())) {
			for (Path file : (Iterable<Path>) list::iterator) {
				String name = file.getFileName().toString();
				if (!name.endsWith(SUFFIX)) {
					continue;
				}
				long pid;
				try {
					pid = Long.parseLong(name.substring(0, name.length() - SUFFIX.length()));
				} catch (NumberFormatException e) {
					continue;
				}
				if (pid == self) {
					continue;
				}
				if (!running(pid, file)) {
					Files.deleteIfExists(file);
					continue;
				}
				try {
					sum += Long.parseLong(Files.readString(file).trim());
				} catch (IOException | NumberFormatException e) {
					// Файл пишется прямо сейчас или испорчен - этот запуск не считаем.
				}
			}
		} catch (IOException e) {
			return sum;
		}
		return sum;
	}

	/** Жив ли процесс с этим номером и тот ли это процесс, что оставил файл. */
	private static boolean running(long pid, Path file) {
		var handle = ProcessHandle.of(pid);
		if (handle.isEmpty() || !handle.get().isAlive()) {
			return false;
		}
		var started = handle.get().info().startInstant();
		try {
			Instant written = Files.getLastModifiedTime(file).toInstant();
			return started.isEmpty() || !started.get().isAfter(written);
		} catch (IOException e) {
			return true;
		}
	}

	/** Игра запущена с {@code mb} мегабайтами - отметиться; при выходе лаунчера отметка снимется сама. */
	static void mark(int mb) {
		try {
			Files.createDirectories(dir());
			Files.writeString(mine(), Integer.toString(mb));
			Runtime.getRuntime().addShutdownHook(new Thread(Running::unmark, "running-unmark"));
		} catch (IOException | IllegalStateException e) {
			// Без отметки следующий запуск просто возьмёт память по-старому.
		}
	}

	/** Игра кончилась - снять отметку. */
	static void unmark() {
		try {
			Files.deleteIfExists(mine());
		} catch (IOException e) {
			// Останется - следующий запуск уберёт её сам, увидев мёртвый процесс.
		}
	}
}
