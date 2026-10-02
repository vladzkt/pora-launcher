package ru.mcgl.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отпечаток железа: несколько хешей вместо одного, по которым сайт узнаёт компьютер (02.10.2026).
 *
 * Зачем. С 01.10.2026 на компьютер и на интернет пускается не больше двух аккаунтов, а компьютер
 * сайт узнавал по одному MachineGuid ({@link Machine}). 02.10.2026 игрок вошёл третьим аккаунтом,
 * «почистив реестр»: GUID лежит в реестре и меняется одной правкой, а адреса у него меняются сами.
 * Владелец одобрил отпечаток покрепче: к GUID добавляются номер платы, серийник системного диска и
 * адреса сетевых карт. Сайт отказывает, если хоть один хеш из списка совпал с чужими аккаунтами, -
 * значит, чтобы прийти «новым компьютером», менять придётся всё разом, а не одну строку реестра.
 *
 * Виды, в постоянном порядке:
 * <ul>
 * <li>{@code guid} - ровно то, что уходит полем {@code machine} ({@link Machine#id()}), той же
 *     формулой, - чтобы старые отметки сайта продолжали совпадать;
 * <li>{@code bios} - UUID системы из SMBIOS (только Windows; на Маке и Линуксе им уже служит guid):
 *     реестр {@code HKLM\SYSTEM\HardwareConfig}, значение LastConfig, а если его нет -
 *     PowerShell, {@code Win32_ComputerSystemProduct}. Не wmic: в Windows 11 24H2 его убрали;
 * <li>{@code disk} - серийник тома системного диска, {@code vol} (только Windows);
 * <li>{@code mac} - до трёх адресов физических сетевых карт, по порядку строк.
 * </ul>
 * Хеш - SHA-256 от {@code "porakopatb-hw:" + вид + ":" + значение}, шестнадцатерично строчными.
 * Сами значения компьютер не покидают и не пишутся никуда - ни в лог, ни на диск, ни в ошибку.
 *
 * Мусор отбрасывается, а не хешируется: у дешёвых плат UUID бывает из одних нулей или
 * «03000200-0400-...», и такой хеш был бы общим для тысяч чужих компьютеров - третьим «на этом
 * компьютере» оказался бы любой из них. По той же причине из адресов выкинуты виртуальные карты
 * (VPN, VirtualBox, Hyper-V, Radmin), Bluetooth и случайные адреса Wi-Fi, которые Windows
 * выдумывает сама.
 *
 * Запереть вход отпечаток не вправе. Каждая внешняя команда ждётся не дольше двух секунд, весь
 * сбор - не дольше четырёх; что не успело или не прочиталось, просто не попадает в список.
 * Ничего не требует прав администратора и не вызывает окна UAC: реестр читается на чтение,
 * {@code vol} и запрос к CIM доступны обычному пользователю. Считается один раз за запуск.
 *
 * <b>Этот файл живёт в двух репозиториях и обязан совпадать до байта, кроме строки package:</b>
 * лаунчер ({@code pora-launcher: src/main/java/ru/mcgl/launcher/Hardware.java}) и мод
 * ({@code galaxy-mod: src/client/java/ru/mcgl/galaxy/client/net/Hardware.java}, вход из игры у
 * тех, кто пришёл чужим лаунчером). Один компьютер обязан давать один список, чем его ни запусти,
 * иначе лимит считал бы его двумя. Правишь здесь - правь и там.
 */
public final class Hardware {

	/** Один хеш отпечатка: вид ({@code guid}, {@code bios}, {@code disk}, {@code mac}) и сам хеш. */
	public record Entry(String kind, String hash) {
	}

	private static final String SALT = "porakopatb-hw:";
	/** Сколько ждём одну внешнюю команду. */
	private static final long STEP_MS = 2000;
	/** Сколько ждём весь сбор от его начала: вход дольше не задерживаем. */
	private static final long TOTAL_MS = 4000;
	/** Запас, чтобы сбор успел сложить результат до того, как вход перестанет его ждать. */
	private static final long MARGIN_MS = 200;
	private static final int MAX_MACS = 3;
	private static final int MAX_ENTRIES = 8;

	private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern UUID = Pattern.compile(
			"[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}");
	/** Серийник тома: XXXX-XXXX, не часть длинной шестнадцатеричной строки. */
	private static final Pattern SERIAL = Pattern.compile("(?<![0-9A-F])[0-9A-F]{4}-[0-9A-F]{4}(?![0-9A-F])");
	private static final Pattern DRIVE = Pattern.compile("[A-Za-z]:");

	/** UUID, которые прошивают в платы по умолчанию: общий у тысяч чужих компьютеров. */
	private static final Set<String> JUNK_UUIDS = Set.of(
			"03000200-0400-0500-0006-000700080009",
			"00020003-0004-0005-0006-000700080009",
			"12345678-1234-5678-90AB-CDDEEFAABBCC");

	/** Слова в имени сетевой карты, по которым она виртуальная или не своя. */
	private static final List<String> VIRTUAL_WORDS = List.of(
			"virtual", "vmware", "vbox", "virtualbox", "hyper-v", "vethernet", "tap", "tun",
			"wireguard", "zerotier", "hamachi", "radmin", "openvpn", "wintun", "bluetooth",
			"wi-fi direct", "loopback", "pseudo",
			// Русская Windows переводит имена встроенных виртуальных карт Майкрософт.
			"виртуальн");

	/** Начала адресов виртуальных карт: VMware, VirtualBox, Hyper-V, Parallels, TAP-драйверы VPN. */
	private static final List<String> VIRTUAL_PREFIXES = List.of(
			"00:05:69", "00:0C:29", "00:1C:14", "00:50:56", "08:00:27", "00:15:5D", "00:FF");

	private static final Object LOCK = new Object();
	/** Поток сбора; null - сбор ещё не начинали. */
	private static Thread worker;
	/** Когда сбор начат, по {@link System#nanoTime()}. */
	private static long startedAt;

	private static volatile String bios;
	private static volatile String disk;
	private static volatile List<String> macs = List.of();

	private Hardware() {
	}

	/**
	 * Начать сбор в фоне, если он ещё не начат. Ничего не ждёт и не бросает: зовётся заранее,
	 * чтобы ко входу всё было уже посчитано.
	 */
	public static void warm() {
		synchronized (LOCK) {
			if (worker != null) {
				return;
			}
			startedAt = System.nanoTime();
			Thread thread = new Thread(Hardware::collect, "отпечаток-железа");
			thread.setDaemon(true);
			worker = thread;
			try {
				thread.start();
			} catch (RuntimeException | Error refused) {
				// Поток не создался - значит, без отпечатка железа: guid уйдёт и так.
			}
		}
	}

	/**
	 * Хеши этого компьютера в постоянном порядке: guid, bios, disk, mac. Ждёт сбор не дольше
	 * четырёх секунд от его начала; что не успело - в список не попадает. Не бросает никогда.
	 */
	public static List<Entry> list() {
		List<Entry> out = new ArrayList<>();
		try {
			warm();
			// guid считаем здесь, в потоке входа, пока сбор в фоне читает остальное: вход и так
			// ждёт Machine.id() ради поля machine, а последовательно было бы дольше.
			add(out, "guid", Machine.id());
			Thread thread;
			long from;
			synchronized (LOCK) {
				thread = worker;
				from = startedAt;
			}
			long left = TOTAL_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - from);
			if (thread != null && left > 0) {
				thread.join(left);
			}
		} catch (InterruptedException stop) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException | LinkageError quiet) {
			// Отпечаток - не условие входа: что собрали, то и отдаём.
		}
		add(out, "bios", bios);
		add(out, "disk", disk);
		for (String mac : macs) {
			add(out, "mac", mac);
		}
		return List.copyOf(out.size() > MAX_ENTRIES ? out.subList(0, MAX_ENTRIES) : out);
	}

	private static void add(List<Entry> out, String kind, String hash) {
		if (hash != null && HASH.matcher(hash).matches()) {
			out.add(new Entry(kind, hash));
		}
	}

	/**
	 * Поток сбора: каждый вид отдельно, упавший вид не мешает остальным. Порядок - от быстрого и
	 * надёжного к медленному: плата из реестра и диск отвечают за десятки миллисекунд, опрос сетевых
	 * карт на машине с VPN и Hyper-V бывает и в секунду, а PowerShell запускается дольше всех - он
	 * последним и только когда реестр промолчал. Не успело к сроку - пропадает хвост, а не плата.
	 */
	private static void collect() {
		long deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(TOTAL_MS - MARGIN_MS);
		boolean windows = windows();
		String registry = null;
		if (windows) {
			try {
				registry = registryUuid(deadline);
				bios = hash("bios", usableUuid(registry));
			} catch (Exception | LinkageError skip) {
				// без платы - попробуем PowerShell ниже
			}
			try {
				disk = hash("disk", volumeSerial(deadline));
			} catch (Exception | LinkageError skip) {
				// без диска
			}
		}
		try {
			macs = macHashes();
		} catch (Exception | LinkageError skip) {
			// Нет сети в Java или она бросила - без адресов.
		}
		if (windows && registry == null) {
			try {
				bios = hash("bios", usableUuid(cimUuid(deadline)));
			} catch (Exception | LinkageError skip) {
				// без платы
			}
		}
	}

	/** Систему берём у самой Java, как и {@link Machine}: отпечаток должен быть от настоящей. */
	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	static String hash(String kind, String value) throws Exception {
		if (value == null || value.isEmpty()) {
			return null;
		}
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[] sum = digest.digest((SALT + kind + ":" + value).getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(sum);
	}

	// --- плата ----------------------------------------------------------------------------------

	/**
	 * UUID системы из SMBIOS - тот, что показывает {@code Win32_ComputerSystemProduct}. Сперва
	 * реестр: Windows сама кладёт его в {@code HardwareConfig\LastConfig} (вида {@code {4C4C4544-...}}),
	 * и это быстро. Null - реестр не ответил; тогда {@link #cimUuid} спросит PowerShell.
	 */
	private static String registryUuid(long deadline) {
		return uuidIn(lineWith(run(deadline, system("reg.exe"), "query", "HKLM\\SYSTEM\\HardwareConfig",
				"/v", "LastConfig", "/reg:64"), "LastConfig"));
	}

	/** Тот же UUID через PowerShell, если в реестре его не нашлось: он думает дольше, поэтому вторым. */
	private static String cimUuid(long deadline) {
		return uuidIn(run(deadline, system("WindowsPowerShell\\v1.0\\powershell.exe"), "-NoProfile",
				"-NonInteractive", "-Command", "(Get-CimInstance Win32_ComputerSystemProduct).UUID"));
	}

	/** UUID, годный в отпечаток, или null, если его нет или он из прошитых по умолчанию. */
	static String usableUuid(String uuid) {
		return uuid == null || junkUuid(uuid) ? null : uuid;
	}

	/** Первый UUID в тексте: без фигурных скобок, заглавными. */
	static String uuidIn(String text) {
		if (text == null) {
			return null;
		}
		Matcher match = UUID.matcher(text.toUpperCase(Locale.ROOT));
		return match.find() ? match.group() : null;
	}

	/** Прошитый по умолчанию или из одной повторённой цифры (все нули, все F и прочие). */
	static boolean junkUuid(String uuid) {
		if (JUNK_UUIDS.contains(uuid)) {
			return true;
		}
		String digits = uuid.replace("-", "");
		return digits.chars().allMatch(c -> c == digits.charAt(0));
	}

	// --- диск -----------------------------------------------------------------------------------

	/**
	 * Серийник тома системного диска из {@code vol}. Ответ переведён на язык системы («Серийный
	 * номер тома: ...»), поэтому ищем не слова, а сам вид XXXX-XXXX, и берём последний: строкой
	 * выше стоит метка тома, а в ней может оказаться что угодно.
	 */
	private static String volumeSerial(long deadline) {
		String drive = System.getenv("SystemDrive");
		if (drive == null || !DRIVE.matcher(drive).matches()) {
			drive = "C:";
		}
		// /d - не выполнять AutoRun из реестра: чужая команда там могла бы повесить или засорить ответ.
		return serialIn(run(deadline, system("cmd.exe"), "/d", "/c", "vol", drive));
	}

	static String serialIn(String text) {
		if (text == null) {
			return null;
		}
		Matcher match = SERIAL.matcher(text.toUpperCase(Locale.ROOT));
		String last = null;
		while (match.find()) {
			last = match.group();
		}
		return last == null || "0000-0000".equals(last) ? null : last;
	}

	// --- сетевые карты --------------------------------------------------------------------------

	/**
	 * Хеши адресов физических сетевых карт: включена карта или нет - неважно, важно, что она есть.
	 * Windows показывает одну карту несколько раз (с фильтрами QoS, WFP и прочими) - адрес у них
	 * один, поэтому множество. Берём три первых по порядку строк: порядок не зависит от того,
	 * в каком порядке их отдала система.
	 */
	private static List<String> macHashes() throws Exception {
		Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
		if (all == null) {
			return List.of();
		}
		TreeSet<String> found = new TreeSet<>();
		for (NetworkInterface one : Collections.list(all)) {
			try {
				if (one.isLoopback() || one.isVirtual() || one.isPointToPoint()
						|| virtualName(one.getName()) || virtualName(one.getDisplayName())) {
					continue;
				}
				String mac = physicalMac(one.getHardwareAddress());
				if (mac != null) {
					found.add(mac);
				}
			} catch (SocketException | RuntimeException skip) {
				// Карта пропала посреди опроса - без неё.
			}
		}
		List<String> out = new ArrayList<>();
		for (String mac : found) {
			if (out.size() == MAX_MACS) {
				break;
			}
			out.add(hash("mac", mac));
		}
		return out;
	}

	static boolean virtualName(String name) {
		if (name == null) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String word : VIRTUAL_WORDS) {
			if (lower.contains(word)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Адрес вида {@code AA:BB:CC:DD:EE:FF} или null, если он не годится: не шесть байт, из нулей,
	 * групповой, «локально назначенный» (бит 0x02 первого байта - такие Windows выдумывает сама для
	 * случайных адресов Wi-Fi) или из диапазона виртуальных карт.
	 */
	static String physicalMac(byte[] address) {
		if (address == null || address.length != 6) {
			return null;
		}
		if ((address[0] & 0x02) != 0 || (address[0] & 0x01) != 0) {
			return null;
		}
		HexFormat hex = HexFormat.ofDelimiter(":").withUpperCase();
		String mac = hex.formatHex(address);
		if ("00:00:00:00:00:00".equals(mac)) {
			return null;
		}
		for (String prefix : VIRTUAL_PREFIXES) {
			if (mac.startsWith(prefix)) {
				return null;
			}
		}
		return mac;
	}

	// --- внешние команды ------------------------------------------------------------------------

	/**
	 * Полный путь к системной программе: так её не подменит одноимённый файл в папке игры или
	 * лаунчера, откуда Windows ищет программы раньше системной.
	 */
	private static String system(String program) {
		String root = System.getenv("SystemRoot");
		if (root == null || root.isBlank()) {
			return program.substring(program.lastIndexOf('\\') + 1);
		}
		return root + "\\System32\\" + program;
	}

	/** Строка ответа, где есть слово, или пустая строка. */
	private static String lineWith(String text, String word) {
		for (String line : text.lines().toList()) {
			if (line.contains(word)) {
				return line;
			}
		}
		return "";
	}

	/**
	 * Вывод команды или пустая строка, если она не запустилась, не уложилась в свои две секунды
	 * (и в остаток общих четырёх) или ответила ошибкой. Вывод читается в своём потоке: повисшая
	 * команда снимается, а не держит вход.
	 */
	private static String run(long deadline, String... command) {
		long budget = Math.min(STEP_MS, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
		if (budget <= 0) {
			return "";
		}
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
			}, "отпечаток-железа-вывод");
			reader.setDaemon(true);
			reader.start();
			long from = System.nanoTime();
			if (!process.waitFor(budget, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
				return "";
			}
			long left = budget - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - from);
			reader.join(Math.max(50, left));
			if (reader.isAlive()) {
				return "";
			}
			// Нужное в ответе - латиница и цифры, так что кодировка консоли (на русской Windows
			// это cp866) их не портит.
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
