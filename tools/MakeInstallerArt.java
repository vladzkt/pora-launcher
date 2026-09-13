import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Рисует картинки для мастера установки: WiX ждёт их ровно такого размера и в BMP.
 *
 * Запускать из корня репозитория, это делает tools/pack.ps1:
 *     java tools/MakeInstallerArt.java build/wixres
 *
 * Обе картинки тёмные: свои надписи мастер печатает прямо поверх них, а цвет этих надписей мы
 * задаём в main.wxs. Светлым остаётся только середина средних страниц - её оформить нельзя,
 * там Windows рисует поля и кнопки по-своему.
 */
public final class MakeInstallerArt {

	private static final Color BG = new Color(0x0B0D11);
	private static final Color PANEL = new Color(0x12161D);
	private static final Color GOLD = new Color(0xF0B53F);
	private static final Color PALE = new Color(0xDDE3EC);
	private static final String ART = "src/main/resources/ru/mcgl/launcher/head.png";
	private static final String ICON = "src/main/resources/ru/mcgl/launcher/icon.png";

	public static void main(String[] args) throws Exception {
		String out = args.length > 0 ? args[0] : "build/wixres";
		new File(out).mkdirs();
		BufferedImage art = ImageIO.read(new File(ART));
		BufferedImage icon = ImageIO.read(new File(ICON));
		banner(icon, out);
		dialog(art, out);
		System.out.println("Картинки мастера: " + out);
	}

	/**
	 * Полоса сверху на средних страницах. Тёмная: свой заголовок мастер печатает прямо на ней,
	 * и мы красим его золотом - на светлой полосе это не читалось бы.
	 */
	private static void banner(BufferedImage icon, String out) throws Exception {
		BufferedImage image = new BufferedImage(493, 58, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = nice(image);
		g.setColor(BG);
		g.fillRect(0, 0, 493, 58);
		g.drawImage(icon, 493 - 52, 7, 44, 44, null);
		g.setColor(GOLD);
		g.fillRect(0, 56, 493, 2);
		g.dispose();
		ImageIO.write(image, "bmp", new File(out + "/banner.bmp"));
	}

	/**
	 * Первая и последняя страницы: там эта картинка занимает всё окно целиком, поэтому фон у
	 * них какой нарисуем. Рисуем тёмный, а надписи мастера красим светлым в main.wxs.
	 */
	private static void dialog(BufferedImage art, String out) throws Exception {
		BufferedImage image = new BufferedImage(493, 312, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = nice(image);
		g.setColor(PANEL);
		g.fillRect(0, 0, 493, 312);

		int side = 164;
		double scale = 312.0 / art.getHeight();
		int drawW = (int) Math.ceil(art.getWidth() * scale);
		// Полоса узкая, а маскот сидит правее середины: двигаем кадр к нему, иначе в полосу
		// попадает тёмная стена и картинка выглядит чёрным прямоугольником.
		int focus = (int) (drawW * 0.70) - side / 2;
		g.setClip(0, 0, side, 312);
		g.drawImage(art, -focus, 0, drawW, 312, null);
		g.setClip(null);

		g.setColor(GOLD);
		g.fillRect(side, 0, 2, 312);

		// Правое поле темнеет книзу: там мастер печатает свой текст, и ровная заливка выглядит
		// казённо.
		g.setPaint(new GradientPaint(0, 0, PANEL, 0, 312, BG));
		g.fillRect(side + 2, 0, 493 - side - 2, 312);

		g.setColor(GOLD);
		g.setFont(new Font("Segoe UI", Font.BOLD, 19));
		g.drawString("ПОРА КОПАТЬ", side + 24, 268);
		g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
		g.setColor(PALE);
		g.drawString("porakopatb.com", side + 25, 286);

		g.dispose();
		ImageIO.write(image, "bmp", new File(out + "/dialog.bmp"));
	}

	private static Graphics2D nice(BufferedImage image) {
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		return g;
	}
}
