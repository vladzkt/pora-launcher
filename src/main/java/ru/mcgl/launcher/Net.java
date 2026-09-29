package ru.mcgl.launcher;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Системный прокси Windows с запасным выходом напрямую (29.09.2026).
 *
 * Java по умолчанию прокси системы не видит и идёт напрямую. У части игроков интернет на
 * компьютере работает только через прокси: браузер сайт открывал, а лаунчер получал «Connection
 * reset» и не мог даже обновиться (Kirito). С {@code java.net.useSystemProxies} Java берёт прокси из
 * настроек Windows - но тогда игрок с прописанным и неработающим прокси, у которого сейчас всё
 * ходит напрямую, остался бы без лаунчера: сама Java после отказа прокси напрямую не пробует
 * (проверено с мёртвым прокси на 127.0.0.1:9). Поэтому в конец списка дописан выход напрямую -
 * соединение перебирает список, пока одно не откроется.
 */
final class Net {

	private Net() {
	}

	/** Вызывать первым делом, до любого запроса: выбор прокси Java запоминает при первом обращении. */
	static void systemProxiesWithFallback() {
		System.setProperty("java.net.useSystemProxies", "true");
		ProxySelector system = ProxySelector.getDefault();
		if (system == null) {
			return;
		}
		ProxySelector.setDefault(new ProxySelector() {
			@Override
			public List<Proxy> select(URI uri) {
				List<Proxy> list = new ArrayList<>(system.select(uri));
				if (!list.contains(Proxy.NO_PROXY)) {
					list.add(Proxy.NO_PROXY);
				}
				return list;
			}

			@Override
			public void connectFailed(URI uri, SocketAddress address, IOException failure) {
				system.connectFailed(uri, address, failure);
			}
		});
	}
}
