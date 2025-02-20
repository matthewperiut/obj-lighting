package me.x150.testmod;

import lombok.SneakyThrows;
import me.x150.renderer.font.FontRenderer;
import me.x150.renderer.render.OutlineFramebuffer;
import me.x150.renderer.render.Renderer2d;
import me.x150.renderer.render.Renderer3d;
import me.x150.renderer.util.RendererUtils;
import me.x150.testmod.client.TestModClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Math;
import org.joml.Matrix4f;

import java.awt.*;

public class Handler {
	static FontRenderer fr;
	static FontRenderer fr1;

	public static Matrix4f getRotationMatrix(int ticks) {
		float angle = ticks % 360;
		float angleInRadians = Math.toRadians(angle);
		Matrix4f rotationMatrix = new Matrix4f().rotateY(angleInRadians);
		rotationMatrix = rotationMatrix.rotateX(angleInRadians);
		rotationMatrix = rotationMatrix.rotateZ(angleInRadians);
		return rotationMatrix;
	}

	@SneakyThrows
	public static void world(MatrixStack stack) {
		MinecraftClient client = MinecraftClient.getInstance();
        assert client.player != null;
        TestModClient.testObj.draw(stack, getRotationMatrix(client.player.age), new Vec3d(0, 80, 0));
		OutlineFramebuffer.useAndDraw(() -> Renderer3d.renderFilled(stack, Color.WHITE, new Vec3d(0, 300, 0), new Vec3d(5, 5, 5)), 1f, Color.GREEN, Color.BLACK);
	}

	public static void hud(DrawContext matrices) {
		if (fr == null) {
			fr = new FontRenderer(new Font[]{
					new Font("Ubuntu", Font.PLAIN, 8)
			}, 9f);
			fr1 = new FontRenderer(new Font[]{
					new Font("Ubuntu", Font.BOLD, 8)
			}, 9f * 3);
		}
		MatrixStack fs = RendererUtils.getEmptyMatrixStack();
		fs.push();
		Renderer2d.renderEllipse(matrices.getMatrices(), Color.RED, 30, 120, 10, 15, 20);
		Renderer2d.renderEllipseOutline(matrices.getMatrices(), Color.RED, 70, 120, 30, 15, 1+(System.currentTimeMillis() % 5000) / 5000d * 10, 1+(1- (System.currentTimeMillis() % 5000) / 5000d) * 10, 20);
		String n = """
				This is a rendering library.
				It supports TTF font rendering.
				I can type äöü, it will render it.
				It also supports newlines.
				""".trim();
		float stringWidth = fr.getStringWidth(n);
		float stringHeight = fr.getStringHeight(n);
//		fs.multiply(RotationAxis.NEGATIVE_Z.rotationDegrees((System.currentTimeMillis() % 5000) / 5000f * 360f),
//				30 + (stringWidth + 5) / 2, 30 + (stringHeight + 5) / 2, 0);
		Renderer2d.renderRoundedQuad(fs, Color.BLACK, 30 - 5, 30 - 5, 30 + stringWidth + 5, 30 + stringHeight + 5, 5,
				5);
		fr.drawString(fs, n, 30, 30, 1f, 1f, 1f, 1f);
		fs.pop();
	}
}
