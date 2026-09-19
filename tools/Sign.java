import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Подпись джарника лаунчера ключом владельца.
 *
 *   java tools/Sign.java <ключ> <джарник>
 *
 * Рядом с джарником появляется файл .sig - подпись Ed25519 в Base64. Лаунчер перед запуском
 * скачанного джарника сверяет её открытым ключом, зашитым в его код: тогда даже взломанный сайт не
 * подсунет чужой код, потому что подписать его нечем.
 *
 * Закрытый ключ на сервере не лежит и в репозиторий не коммитится - он в профиле владельца
 * (%USERPROFILE%\.porakopatb-keys\launcher-signing.key). Потеряется - делается новая пара, и
 * открытая часть меняется в коде; старые лаунчеры после этого обновятся только вручную.
 */
public class Sign {
	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("java tools/Sign.java <ключ> <джарник>");
			System.exit(2);
		}
		byte[] pkcs8 = Base64.getDecoder().decode(Files.readString(Path.of(args[0])).trim());
		PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
		Path jar = Path.of(args[1]);
		Signature signer = Signature.getInstance("Ed25519");
		signer.initSign(key);
		signer.update(Files.readAllBytes(jar));
		String sig = Base64.getEncoder().encodeToString(signer.sign());
		Path out = jar.resolveSibling(jar.getFileName() + ".sig");
		Files.writeString(out, sig);
		System.out.println("Подписано: " + out.getFileName());
		System.out.println(sig);
	}
}
