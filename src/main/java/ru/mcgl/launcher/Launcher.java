package ru.mcgl.launcher;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * Лаунчер «Пора Копать».
 *
 * Одно окно: ник, пароль, кнопка. Всё остальное - скачивание игры, Fabric и модов - он делает
 * сам и показывает полосой.
 *
 * Пароль на диск не ложится никогда. С галочкой «запомнить» сайт выдаёт отдельный долгий ключ
 * для этой машины - он и хранится рядом с ником; годится только для входа в лаунчер и пропадает,
 * когда игрок меняет пароль.
 *
 * Для проверки без окна: {@code --check} печатает список сборки, {@code --install} доводит
 * установку до конца и ничего не запускает, {@code --shot файл.png} рисует окно в файл.
 */
public final class Launcher {

	private static final int WIDTH = 480;
	private static final int SIDE = 372;
	private static final int HEIGHT = 520;
	// Ровно доля картинки 920x320 при ширине окна: так она видна целиком и без искажений.
	private static final int HEADER = 167;
	private static final int PAD = 30;

	private final Properties settings = new Properties();
	/** Куда ставится игра. По умолчанию рядом с настройками, но игрок может увести на другой диск. */
	private Path root = Files2.home();

	private JFrame frame;
	private JTextField nick;
	private JPasswordField password;
	private Skin.GoldButton play;
	private Skin.Check remember;
	private JPanel passBlock;
	private JPanel newsBox;
	private JLabel onlineLine;
	private JLabel gameLine;
	private JLabel packLine;
	private Skin.Bar bar;
	private JLabel status;
	private JPanel crashBox;
	private Skin.Link crashLink;
	private Timer spinner;
	private boolean working;

	public static void main(String[] args) throws Exception {
		// До окна: если на сайте лежит версия новее, запустимся уже с ней.
		Update.apply(args);
		if (args.length > 0 && ("--check".equals(args[0]) || "--install".equals(args[0]))) {
			console(args);
			return;
		}
		if (args.length > 0 && "--shot".equals(args[0])) {
			// Третьим доводом «setup» снимается окно настроек, иначе главное.
			shot(args.length > 1 ? args[1] : "launcher.png",
					args.length > 2 && "setup".equals(args[2]));
			return;
		}
		SwingUtilities.invokeLater(() -> new Launcher().show());
	}

	/** Проверка из командной строки: окно не открывается, игра не запускается. */
	private static void console(String[] args) throws Exception {
		Site.Pack pack = Site.pack();
		System.out.println("Сборка " + pack.version() + ": Minecraft " + pack.minecraft()
				+ ", Fabric " + pack.fabric());
		for (Site.PackFile f : pack.files()) {
			System.out.printf("  %-42s %7d КБ%n", f.path(), f.size() / 1024);
		}
		if ("--check".equals(args[0])) {
			return;
		}
		Path root = Files2.home();
		System.out.println("Ставлю в " + root);
		Installer.Plan plan = new Installer(root).install(pack, (what, done) ->
				System.out.printf("  [%3.0f%%] %s%n", done * 100, what));
		System.out.println("Точка входа: " + plan.mainClass());
		System.out.println("В пути классов: " + plan.classpath().size() + " файлов");
	}

	/** Рисуем окно в файл, чтобы посмотреть на него, никому его не показывая. */
	private static void shot(String file, boolean setup) throws Exception {
		SwingUtilities.invokeAndWait(() -> new Launcher().show());
		Thread.sleep(setup ? 300 : 2500);
		if (setup) {
			SwingUtilities.invokeAndWait(() -> SHOWN.openSetup());
			Thread.sleep(400);
		}
		SwingUtilities.invokeAndWait(() -> {
			Launcher one = SHOWN;
			JFrame shown = setup ? SETUP : one.frame;
			shown.setVisible(false);
			Component pane = shown.getContentPane();
			BufferedImage image = new BufferedImage(pane.getWidth(), pane.getHeight(),
					BufferedImage.TYPE_INT_RGB);
			Graphics g = image.getGraphics();
			pane.printAll(g);
			g.dispose();
			try {
				ImageIO.write(image, "png", new java.io.File(file));
			} catch (IOException broken) {
				System.out.println("не записалось: " + broken);
			}
		});
		System.exit(0);
	}

	/** Последнее открытое окно: нужно только съёмке. */
	private static Launcher SHOWN;
	private static JFrame SETUP;

	/** Открыть настройки: после сохранения папка игры может смениться. */
	private void openSetup() {
		Setup one = new Setup(frame, settings, () -> {
			root = Path.of(settings.getProperty("dir", Files2.home().toString()));
			save();
		});
		SETUP = one.show();
	}

	private void show() {
		SHOWN = this;
		load();
		root = Path.of(settings.getProperty("dir", Files2.home().toString()));
		frame = new JFrame("Пора Копать");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		// Без системной рамки: своя кнопка закрытия лежит прямо на картинке.
		frame.setUndecorated(true);
		frame.setSize(WIDTH + SIDE, HEIGHT);
		frame.setLocationRelativeTo(null);
		frame.setResizable(false);
		Image icon = image("icon.png");
		if (icon != null) {
			frame.setIconImage(icon);
		}

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(Skin.BG);
		body.setBorder(new EmptyBorder(2, PAD, 18, PAD));

		nick = Skin.text();
		password = Skin.secret();
		nick.setText(settings.getProperty("nick", ""));

		body.add(row(Skin.caption("Ник"), 16));
		body.add(Box.createVerticalStrut(5));
		body.add(capped(new Skin.Field(nick), 40));
		body.add(Box.createVerticalStrut(14));

		// Когда вход сохранён, поле прячем целиком: пустая строка «Пароль» выглядит так, будто
		// лаунчер каждый раз просит пароль заново.
		passBlock = new JPanel();
		passBlock.setLayout(new BoxLayout(passBlock, BoxLayout.Y_AXIS));
		passBlock.setBackground(Skin.BG);
		passBlock.add(row(Skin.caption("Пароль"), 16));
		passBlock.add(Box.createVerticalStrut(5));
		passBlock.add(capped(new Skin.Field(password), 40));
		passBlock.add(Box.createVerticalStrut(12));
		passBlock.setMaximumSize(new Dimension(Integer.MAX_VALUE, 73));
		body.add(passBlock);

		remember = new Skin.Check("Запомнить пароль", !saved().isEmpty());
		body.add(capped(remember, 20));
		body.add(Box.createVerticalStrut(16));

		play = new Skin.GoldButton("Играть", this::go);
		body.add(capped(play, 44));
		body.add(Box.createVerticalStrut(16));

		bar = new Skin.Bar();
		body.add(capped(bar, 6));
		body.add(Box.createVerticalStrut(9));

		status = Skin.label(" ", Skin.MUTED, Font.PLAIN, 12f);
		body.add(row(status, 18));
		crashBox = new JPanel();
		crashBox.setLayout(new BoxLayout(crashBox, BoxLayout.Y_AXIS));
		crashBox.setBackground(Skin.BG);
		crashBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		crashBox.setVisible(false);
		body.add(crashBox);
		body.add(Box.createVerticalGlue());

		body.add(row(Skin.label("porakopatb.com · " + Update.running(),
				new java.awt.Color(0x5E6878), Font.PLAIN, 11f), 16));

		Skin.Header head = new Skin.Header(image("head.png"), WIDTH, HEADER);
		dragBy(head);

		JPanel left = new JPanel(new BorderLayout());
		left.setBackground(Skin.BG);
		left.setPreferredSize(new Dimension(WIDTH, HEIGHT));
		left.add(head, BorderLayout.NORTH);
		left.add(body, BorderLayout.CENTER);

		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(Skin.BG);
		outer.add(left, BorderLayout.WEST);
		outer.add(side(), BorderLayout.CENTER);
		frame.setContentPane(outer);

		// Кнопка гаснет, пока не введено и то и другое: меньше поводов увидеть отказ сайта.
		// С сохранённым входом пароль не нужен - там уже есть чем войти.
		Runnable check = () -> play.setOn(!working && !nick.getText().trim().isEmpty()
				&& (password.getPassword().length > 0 || !saved().isEmpty()));
		Skin.onType(nick, check);
		Skin.onType(password, check);
		// Снял галочку - забываем ключ сразу, не дожидаясь следующего входа, и просим пароль.
		remember.onChange(() -> {
			if (!remember.isOn() && !saved().isEmpty()) {
				settings.remove("device");
				save();
				askPassword();
			}
			check.run();
		});
		check.run();
		passBlock.setVisible(saved().isEmpty());

		password.addActionListener(e -> go());
		nick.addActionListener(e -> password.requestFocusInWindow());

		frame.setVisible(true);
		if (!nick.getText().isBlank() && saved().isEmpty()) {
			password.requestFocusInWindow();
		}
		// Новости и онлайн тянем после окна: сайт может не ответить, а играть это не мешает.
		new Thread(this::fillSide, "новости").start();
	}

	/** Правая колонка: новости, кто в игре и ссылки. */
	private JPanel side() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(Skin.PANEL);
		panel.setBorder(new EmptyBorder(10, 22, 18, 22));

		JPanel bar = new JPanel(new BorderLayout());
		bar.setBackground(Skin.PANEL);
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JPanel keys = new JPanel();
		keys.setBackground(Skin.PANEL);
		keys.setLayout(new BoxLayout(keys, BoxLayout.X_AXIS));
		Skin.WinButton hide = new Skin.WinButton(false, () -> frame.setState(JFrame.ICONIFIED));
		Skin.WinButton close = new Skin.WinButton(true, () -> System.exit(0));
		hide.setMaximumSize(new Dimension(34, 26));
		close.setMaximumSize(new Dimension(34, 26));
		keys.add(hide);
		keys.add(Box.createHorizontalStrut(4));
		keys.add(close);
		bar.add(keys, BorderLayout.EAST);
		dragBy(bar);
		left(panel, bar);
		left(panel, Box.createVerticalStrut(8));

		left(panel, row(Skin.caption("Новости"), 16, Skin.PANEL));
		left(panel, Box.createVerticalStrut(6));
		newsBox = new JPanel();
		newsBox.setLayout(new BoxLayout(newsBox, BoxLayout.Y_AXIS));
		newsBox.setBackground(Skin.PANEL);
		newsBox.setAlignmentX(0f);
		newsBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
		left(newsBox, row(Skin.label("Загружаю…", Skin.MUTED, Font.PLAIN, 12f), 20, Skin.PANEL));
		left(panel, newsBox);
		left(panel, Box.createVerticalStrut(18));

		left(panel, row(Skin.caption("Сейчас в игре"), 16, Skin.PANEL));
		left(panel, Box.createVerticalStrut(4));
		onlineLine = Skin.label(" ", Skin.MUTED, Font.PLAIN, 12f);
		left(panel, row(onlineLine, 20, Skin.PANEL));

		left(panel, Box.createVerticalStrut(18));
		left(panel, row(Skin.caption("Игра"), 16, Skin.PANEL));
		left(panel, Box.createVerticalStrut(4));
		gameLine = Skin.label("…", Skin.MUTED, Font.PLAIN, 12f);
		left(panel, row(gameLine, 18, Skin.PANEL));
		packLine = Skin.label(" ", Skin.MUTED, Font.PLAIN, 12f);
		left(panel, row(packLine, 18, Skin.PANEL));

		left(panel, Box.createVerticalGlue());
		left(panel, link("Настройки", this::openSetup));
		left(panel, link("Регистрация", () -> open(Site.BASE + "/register")));
		left(panel, link("Забыл пароль", () -> open(Site.BASE + "/forgot")));
		left(panel, link("Вики сервера", () -> open(Site.BASE + "/wiki")));
		left(panel, link("Карта мира", () -> open(Site.BASE + "/map")));
		left(panel, link("Форум", () -> open(Site.BASE + "/forum")));
		return panel;
	}

	/** Всё в колонке прижато к левому краю: BoxLayout иначе разъезжается. */
	private static void left(JPanel to, java.awt.Component what) {
		if (what instanceof JComponent piece) {
			piece.setAlignmentX(0f);
		}
		to.add(what);
	}

	private Skin.Link link(String text, Runnable action) {
		Skin.Link out = new Skin.Link(text, 13f, false, action);
		out.setAlignmentX(0f);
		out.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
		return out;
	}

	/** Открыть страницу в браузере игрока. */
	private static void open(String url) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception broken) {
            // Нет браузера по умолчанию - молчим: это не повод мешать игре.
        }
	}

	/**
	 * Скачана ли игра и сколько она занимает. По этой строке видно, ждать ли первого запуска
	 * долго: гигабайт по слабому каналу - это минуты, и лучше знать заранее.
	 */
	private void tellAboutGame() {
		boolean ready = Files.isDirectory(root.resolve("versions"));
		long bytes = 0;
		if (ready) {
			try (var walk = Files.walk(root)) {
				bytes = walk.filter(Files::isRegularFile).mapToLong(file -> {
					try {
						return Files.size(file);
					} catch (IOException skip) {
						return 0;
					}
				}).sum();
			} catch (Exception quiet) {
				bytes = 0;
			}
		}
		double gb = Math.round(bytes / 1024.0 / 1024 / 102.4) / 10.0;
		String text = bytes > 0 ? "скачана, " + gb + " ГБ" : "ещё не скачана";
		String more = bytes > 0 ? " " : "первый запуск займёт несколько минут";
		SwingUtilities.invokeLater(() -> {
			gameLine.setText(text);
			if (!more.isBlank()) {
				packLine.setText(more);
			}
		});
	}

	private void fillSide() {
		tellAboutGame();
		Site.Home home = Site.home();
		String pack = packWhen();
		SwingUtilities.invokeLater(() -> {
			newsBox.removeAll();
			if (!pack.isEmpty()) {
				packLine.setText("сборка от " + pack);
			}
			if (home == null) {
				left(newsBox, row(Skin.label("Сайт не ответил", Skin.MUTED, Font.PLAIN, 12f), 20, Skin.PANEL));
				onlineLine.setText("неизвестно");
			} else {
				if (home.news().isEmpty()) {
					left(newsBox, row(Skin.label("Пока тихо", Skin.MUTED, Font.PLAIN, 12f), 20, Skin.PANEL));
				}
				for (Site.News one : home.news()) {
					Skin.Link item = new Skin.Link(one.title(), 13f, false, () -> open(one.url()));
					item.setAlignmentX(0f);
					item.setMaximumSize(new Dimension(SIDE - 44, 24));
					left(newsBox, item);
				}
				onlineLine.setText(text(home));
			}
			newsBox.revalidate();
			newsBox.repaint();
		});
	}

	/** Когда собран пак: версия приходит временем последней правки вида 20260914090330. */
	private static String packWhen() {
		try {
			String when = Site.pack().version();
			return when.length() >= 8
					? when.substring(6, 8) + "." + when.substring(4, 6) + "." + when.substring(0, 4)
					: "";
		} catch (Exception quiet) {
			return "";
		}
	}

	/** Строка про онлайн: сколько людей и кто именно, если их немного. */
	private static String text(Site.Home home) {
		if (!home.online()) {
			return "сервер спит";
		}
		if (home.players().isEmpty()) {
			return "никого, будь первым";
		}
		String who = String.join(", ", home.players());
		return home.players().size() + " " + (who.length() > 60 ? "" : "· " + who);
	}

	/** Показать поле пароля и поставить в него курсор: сохранённый вход больше не годится. */
	private void askPassword() {
		if (passBlock.isVisible()) {
			return;
		}
		passBlock.setVisible(true);
		passBlock.revalidate();
		passBlock.repaint();
		password.requestFocusInWindow();
	}

	/** Окно без рамки само не таскается - возим его за шапку. */
	private void dragBy(Component what) {
		java.awt.event.MouseAdapter hand = new java.awt.event.MouseAdapter() {
			private java.awt.Point grab;

			@Override
			public void mousePressed(java.awt.event.MouseEvent e) {
				grab = e.getPoint();
			}

			@Override
			public void mouseDragged(java.awt.event.MouseEvent e) {
				if (grab == null) {
					return;
				}
				java.awt.Point at = frame.getLocation();
				frame.setLocation(at.x + e.getX() - grab.x, at.y + e.getY() - grab.y);
			}
		};
		what.addMouseListener(hand);
		what.addMouseMotionListener(hand);
	}

	/** BoxLayout растягивает всё по высоте, поэтому ростом каждой полосы правим вручную. */
	private static Component capped(Component what, int height) {
		what.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		what.setPreferredSize(new Dimension(0, height));
		return what;
	}

	/** Строка во всю ширину: иначе подпись уезжает в центр. */
	private static JPanel row(Component what, int height) {
		return row(what, height, Skin.BG);
	}

	private static JPanel row(Component what, int height, java.awt.Color back) {
		JPanel line = new JPanel(new BorderLayout());
		line.setBackground(back);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		line.setPreferredSize(new Dimension(0, height));
		line.add(what, BorderLayout.WEST);
		return line;
	}

	/** Картинка из ресурсов рядом с классом; если её нет, окно просто останется без неё. */
	private static Image image(String name) {
		try (var in = Launcher.class.getResourceAsStream(name)) {
			return in == null ? null : ImageIO.read(in);
		} catch (IOException missing) {
			return null;
		}
	}

	/**
	 * Игра закрылась сразу после запуска. Причина почти всегда в логе, поэтому не пересказываем
	 * её своими словами, а даём открыть лог одним нажатием.
	 */
	private void crashed(int code) {
		status.setText("Игра закрылась сразу (код " + code + ")");
		if (crashLink == null) {
			crashLink = new Skin.Link("Показать лог игры", 12f, false,
					() -> Files2.reveal(root.resolve("game.log")));
			crashLink.setAlignmentX(0f);
			crashLink.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
			crashBox.add(crashLink);
		}
		crashBox.setVisible(true);
		crashBox.revalidate();
		crashBox.repaint();
	}

	/** Ключ «запомнить пароль» для этой машины; пустая строка, если его нет. */
	private String saved() {
		return settings.getProperty("device", "");
	}

	private void go() {
		String who = nick.getText().trim();
		String secret = new String(password.getPassword());
		String key = saved();
		if (who.isEmpty() || (secret.isEmpty() && key.isEmpty())) {
			say("Введи ник и пароль.");
			return;
		}
		working = true;
		play.setOn(false);
		bar.setVisible(true);
		bar.spin();
		spinner = new Timer(40, e -> bar.repaint());
		spinner.start();
		say("Вхожу");

		new Thread(() -> {
			try {
				// Пароль набран - идём по нему: игрок мог сменить учётную запись.
				Site.Account account = secret.isEmpty()
						? Site.loginSaved(key)
						: Site.login(who, secret, remember.isOn());
				settings.setProperty("nick", account.nick());
				if (!account.device().isEmpty()) {
					settings.setProperty("device", account.device());
				}
				save();

				Site.Pack pack = Site.pack();
				SwingUtilities.invokeLater(() -> {
					spinner.stop();
					bar.set(0);
				});
				Installer.Plan plan = new Installer(root).install(pack, account.packKey(), (what, done) ->
						SwingUtilities.invokeLater(() -> {
							status.setText(what);
							bar.set(done);
						}));

				say("Запускаю игру");
				// Моды расшифровываются сюда и живут ровно столько, сколько идёт игра.
				Path unpacked = Vault.unpack(pack.files(), account.packKey());
				Process game = Game.start(root, plan, account, pack.minecraft(), pack.fabric(),
						memory(), unpacked);
				SwingUtilities.invokeLater(() -> frame.setVisible(false));
				// Игра встаёт на ноги секунд десять. Если она умерла за это время - это не запуск,
				// а падение, и игроку надо показать окно обратно, иначе он остаётся ни с чем.
				if (!game.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
					// Игра поднялась. Лаунчер не уходит совсем, а тихо ждёт её конца, чтобы стереть
					// расшифрованные моды. Уйди он сразу - открытые джарники остались бы лежать во
					// временной папке до следующего запуска, а ради того, чтобы их там не было, всё
					// и затевалось. Окно к этому времени уже скрыто, так что для игрока он исчез.
					game.waitFor();
					Vault.erase(unpacked);
					System.exit(0);
				}
				Vault.erase(unpacked);
				int code = game.exitValue();
				SwingUtilities.invokeLater(() -> {
					working = false;
					bar.setVisible(false);
					play.setOn(true);
					frame.setVisible(true);
					crashed(code);
				});
				return;
			} catch (Exception broken) {
				String message = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				// Ключ протух или его отозвали сменой пароля - выбрасываем и просим пароль.
				if (message.contains("Сохранённый вход")) {
					settings.remove("device");
					save();
					SwingUtilities.invokeLater(() -> {
						remember.set(false);
						askPassword();
					});
				}
				SwingUtilities.invokeLater(() -> {
					if (spinner != null) {
						spinner.stop();
					}
					working = false;
					bar.setVisible(false);
					play.setOn(true);
					status.setText(message);
				});
			}
		}, "поехали").start();
	}

	/** Сколько памяти отдать игре: половина от машины, но не меньше двух и не больше восьми. */
	private int memory() {
		String saved = settings.getProperty("memory");
		if (saved != null) {
			try {
				return Integer.parseInt(saved);
			} catch (NumberFormatException ignored) {
				// Настройка испорчена - считаем сами.
			}
		}
		long total = 4096;
		try {
			com.sun.management.OperatingSystemMXBean os =
					(com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
							.getOperatingSystemMXBean();
			total = os.getTotalMemorySize() / 1024 / 1024;
		} catch (Throwable ignored) {
			// Не вышло спросить систему - остаёмся на четырёх гигабайтах.
		}
		return (int) Math.max(2048, Math.min(8192, total / 2));
	}

	private void say(String what) {
		SwingUtilities.invokeLater(() -> status.setText(what));
	}

	private void load() {
		Path file = Files2.home().resolve("launcher.properties");
		if (Files.isRegularFile(file)) {
			try (var in = Files.newInputStream(file)) {
				settings.load(in);
			} catch (IOException ignored) {
				// Настройки - удобство, а не необходимость.
			}
		}
	}

	private void save() {
		try {
			Files.createDirectories(Files2.home());
			try (var out = Files.newOutputStream(Files2.home().resolve("launcher.properties"))) {
				settings.store(out, "Пора Копать");
			}
		} catch (IOException ignored) {
			// Не записались настройки - ничего страшного, ник просто спросим снова.
		}
	}
}
