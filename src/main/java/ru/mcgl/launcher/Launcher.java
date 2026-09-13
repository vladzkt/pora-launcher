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
	private static final int HEIGHT = 520;
	// Ровно доля картинки 920x320 при ширине окна: так она видна целиком и без искажений.
	private static final int HEADER = 167;
	private static final int PAD = 30;

	private final Path root = Files2.home();
	private final Properties settings = new Properties();

	private JFrame frame;
	private JTextField nick;
	private JPasswordField password;
	private Skin.GoldButton play;
	private Skin.Check remember;
	private Skin.Bar bar;
	private JLabel status;
	private Timer spinner;
	private boolean working;

	public static void main(String[] args) throws Exception {
		if (args.length > 0 && ("--check".equals(args[0]) || "--install".equals(args[0]))) {
			console(args);
			return;
		}
		if (args.length > 0 && "--shot".equals(args[0])) {
			shot(args.length > 1 ? args[1] : "launcher.png");
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
	private static void shot(String file) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			Launcher one = new Launcher();
			one.show();
			one.frame.setVisible(false);
			Component pane = one.frame.getContentPane();
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

	private void show() {
		load();
		frame = new JFrame("Пора Копать");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		// Без системной рамки: своя кнопка закрытия лежит прямо на картинке.
		frame.setUndecorated(true);
		frame.setSize(WIDTH, HEIGHT);
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
		body.add(row(Skin.caption("Пароль"), 16));
		body.add(Box.createVerticalStrut(5));
		body.add(capped(new Skin.Field(password), 40));
		body.add(Box.createVerticalStrut(12));

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
		body.add(Box.createVerticalGlue());

		body.add(row(Skin.label("porakopatb.com", new java.awt.Color(0x5E6878), Font.PLAIN, 11f), 16));

		Skin.Header head = new Skin.Header(image("head.png"), WIDTH, HEADER);
		head.setLayout(null);
		Skin.WinButton close = new Skin.WinButton(true, () -> System.exit(0));
		Skin.WinButton hide = new Skin.WinButton(false, () -> frame.setState(JFrame.ICONIFIED));
		close.setBounds(WIDTH - 40, 8, 34, 26);
		hide.setBounds(WIDTH - 78, 8, 34, 26);
		head.add(close);
		head.add(hide);
		dragBy(head);

		JPanel outer = new JPanel(new BorderLayout());
		outer.setBackground(Skin.BG);
		outer.add(head, BorderLayout.NORTH);
		outer.add(body, BorderLayout.CENTER);
		frame.setContentPane(outer);

		// Кнопка гаснет, пока не введено и то и другое: меньше поводов увидеть отказ сайта.
		// С сохранённым входом пароль не нужен - там уже есть чем войти.
		Runnable check = () -> play.setOn(!working && !nick.getText().trim().isEmpty()
				&& (password.getPassword().length > 0 || !saved().isEmpty()));
		Skin.onType(nick, check);
		Skin.onType(password, check);
		// Снял галочку - забываем ключ сразу, не дожидаясь следующего входа.
		remember.onChange(() -> {
			if (!remember.isOn()) {
				settings.remove("device");
				save();
			}
			check.run();
		});
		check.run();
		if (!saved().isEmpty()) {
			status.setText("Пароль сохранён - жми «Играть»");
		}

		password.addActionListener(e -> go());
		nick.addActionListener(e -> password.requestFocusInWindow());

		frame.setVisible(true);
		if (!nick.getText().isBlank() && saved().isEmpty()) {
			password.requestFocusInWindow();
		}
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
		JPanel line = new JPanel(new BorderLayout());
		line.setBackground(Skin.BG);
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
				Installer.Plan plan = new Installer(root).install(pack, (what, done) ->
						SwingUtilities.invokeLater(() -> {
							status.setText(what);
							bar.set(done);
						}));

				say("Запускаю игру");
				Game.start(root, plan, account, pack.minecraft(), pack.fabric(), memory());
				SwingUtilities.invokeLater(() -> frame.setVisible(false));
				// Даём игре встать на ноги и уходим: держать окно лаунчера незачем.
				Thread.sleep(8000);
				System.exit(0);
			} catch (Exception broken) {
				String message = broken.getMessage() == null ? broken.toString() : broken.getMessage();
				// Ключ протух или его отозвали сменой пароля - выбрасываем и просим пароль.
				if (message.contains("Сохранённый вход")) {
					settings.remove("device");
					save();
					SwingUtilities.invokeLater(() -> remember.set(false));
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
		Path file = root.resolve("launcher.properties");
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
			Files.createDirectories(root);
			try (var out = Files.newOutputStream(root.resolve("launcher.properties"))) {
				settings.store(out, "Пора Копать");
			}
		} catch (IOException ignored) {
			// Не записались настройки - ничего страшного, ник просто спросим снова.
		}
	}
}
