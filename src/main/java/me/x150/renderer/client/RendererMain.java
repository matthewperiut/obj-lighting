package me.x150.renderer.client;

import me.x150.renderer.shader.ShaderManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(EnvType.CLIENT)
public class RendererMain implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Renderer");

	@Override
	public void onInitializeClient() {
		ShaderManager.register();
	}
}
