package ru.mcgl.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Свои аккаунты на этом компьютере - основной и твинки (владелец, 12.10.2026: «добавь возможность
 * добавлять себе твинков в лаунчере»).
 *
 * Лежат в тех же настройках лаунчера: {@code accounts} - ники через запятую, и у каждого свой ключ
 * «запомнить пароль» под {@code device.<ник маленькими>}. Ключ сайт выдаёт на учётную запись, а не на
 * компьютер, и их у одного человека может быть сколько угодно, - так что вход вторым аккаунтом первый
 * не выбивает. Сколько аккаунтов пустят с одного компьютера, решает сайт (два, с Premium три); здесь
 * только список, чтобы не набирать ник и пароль заново.
 *
 * До 1.0.29 ключ был один, под {@code device}, - при первом запуске он переезжает к своему нику.
 */
final class Accounts {

	/** Больше в списке не держим: сайт с одного компьютера пускает два аккаунта, с Premium - три. */
	static final int MAX = 3;

	private final Properties settings;

	Accounts(Properties settings) {
		this.settings = settings;
		migrate();
	}

	private void migrate() {
		if (settings.containsKey("accounts")) {
			return;
		}
		String nick = settings.getProperty("nick", "").trim();
		String device = settings.getProperty("device", "");
		settings.setProperty("accounts", nick);
		if (!nick.isEmpty() && !device.isEmpty()) {
			settings.setProperty(keyName(nick), device);
		}
		settings.remove("device");
	}

	List<String> nicks() {
		List<String> out = new ArrayList<>();
		for (String part : settings.getProperty("accounts", "").split(",")) {
			if (!part.isBlank()) {
				out.add(part.trim());
			}
		}
		return out;
	}

	boolean has(String nick) {
		return find(nick) != null;
	}

	/** Ключ «запомнить пароль» этого аккаунта; пустая строка, если его нет. */
	String key(String nick) {
		return nick == null || nick.isBlank() ? "" : settings.getProperty(keyName(nick), "");
	}

	/** Аккаунт вошёл: в список (ник - как его пишет сайт), лишний старый - вон. */
	void add(String nick) {
		List<String> list = nicks();
		String known = find(nick);
		if (known != null) {
			list.set(list.indexOf(known), nick);
		} else {
			list.add(nick);
			while (list.size() > MAX) {
				String oldest = list.remove(0);
				settings.remove(keyName(oldest));
			}
		}
		store(list);
	}

	void remember(String nick, String key) {
		add(nick);
		settings.setProperty(keyName(nick), key);
	}

	void forget(String nick) {
		if (nick != null && !nick.isBlank()) {
			settings.remove(keyName(nick));
		}
	}

	void remove(String nick) {
		List<String> list = nicks();
		String known = find(nick);
		if (known != null) {
			list.remove(known);
			store(list);
		}
		forget(nick);
	}

	private String find(String nick) {
		if (nick == null) {
			return null;
		}
		for (String known : nicks()) {
			if (known.equalsIgnoreCase(nick.trim())) {
				return known;
			}
		}
		return null;
	}

	private void store(List<String> list) {
		settings.setProperty("accounts", String.join(",", list));
	}

	private static String keyName(String nick) {
		return "device." + nick.trim().toLowerCase(Locale.ROOT);
	}
}
