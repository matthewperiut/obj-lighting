package me.x150.renderer.objfile;

import com.mojang.blaze3d.systems.RenderSystem;
import de.javagl.obj.*;
import me.x150.renderer.shader.ShaderManager;
import me.x150.renderer.util.BufferUtils;
import me.x150.renderer.util.RendererUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * A wavefront obj file parser.
 * <h2>General Info</h2>
 * This implementation has been tested and optimized to work well with blender exported OBJs. OBJs exported from other sources may not work as well.<br>
 * When exporting a model in blender (for use with this library), make sure the following options are set:
 * <ul>
 *     <li>Forward axis: Either X, Y, -X or -Y</li>
 *     <li><b>Up axis: Y</b></li>
 *     <li>UV Coordinates: Yes</li>
 *     <li>Normals: Yes</li>
 *     <li><b>Triangulated mesh: Yes</b>*</li>
 * </ul>
 * <b>Highlighted options</b> are especially important.<br><br>
 * *: Non-triangulated meshes may not work, triangulation may fail.<br><br>
 * <h2>Parsing</h2>
 * This class uses {@link ObjReader} to read and parse .obj files. .mtl files are also handled by said library.
 * File access is managed by the {@link ResourceProvider} interface, which has the job of mapping a file name into a readable file.
 * <h2>Rendering</h2>
 * To render a loaded ObjFile, call {@link #draw(MatrixStack, Matrix4f, Vec3d)}.
 */
public class ObjFile implements Closeable {
	private final ResourceProvider provider;
	private final String name;
	final Map<Obj, VertexBuffer> buffers = new HashMap<>();
	final Map<String, Identifier> boundTextures = new HashMap<>();
	Map<String, Obj> materialNameObjMap;
	private List<Mtl> allMaterials;
	private boolean baked = false;
	private boolean closed = false;

	/**
	 * Creates a new ObjFile
	 *
	 * @param name     Filename of the target .obj, resolved by the {@link ResourceProvider} {@code provider}
	 * @param provider The resource provider to use
	 * @throws IOException When reading the .obj fails
	 */
	public ObjFile(String name, ResourceProvider provider) throws IOException {
		this.name = name;
		this.provider = provider;
		read();
	}

	private static Vec3d transformVec3d(Vec3d in) {
		Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
		Vec3d camPos = camera.getPos();
		return in.subtract(camPos);
	}

	private void read() throws IOException {
		try (InputStream reader = provider.open(name)) {
			Obj r = ObjUtils.convertToRenderable(ObjReader.read(reader));
			allMaterials = new ArrayList<>();
			for (String mtlFileName : r.getMtlFileNames()) {
				try (InputStream openReaderTo = provider.open(mtlFileName)) {
					List<Mtl> read = MtlReader.read(openReaderTo);
					allMaterials.addAll(read);
				}
			}
			materialNameObjMap = ObjSplitting.splitByMaterialGroups(r);

		}
	}

	private Identifier createTex0(String s) {
		try (InputStream reader = this.provider.open(s)) {
			Identifier identifier = RendererUtils.randomIdentifier();
			BufferedImage read1 = ImageIO.read(reader);
			RendererUtils.registerBufferedImageTexture(identifier, read1);
			return identifier;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private void bake() {
		BufferBuilder b = Tessellator.getInstance().getBuffer();
		for (Map.Entry<String, Obj> stringObjEntry : materialNameObjMap.entrySet()) {
			String materialName = stringObjEntry.getKey();
			Obj objToDraw = stringObjEntry.getValue();
			Mtl material = allMaterials.stream().filter(f -> f.getName().equals(materialName)).findFirst().orElse(null);
			boolean hasTexture = material != null && material.getMapKd() != null;
			if (hasTexture) {
				String mapKd = material.getMapKd();
				boundTextures.computeIfAbsent(mapKd, this::createTex0);
			}
			VertexFormat vmf;
			if (material != null) {
				vmf = hasTexture ? VertexFormats.POSITION_TEXTURE_COLOR_NORMAL : VertexFormats.POSITION_COLOR;
			} else {
				vmf = VertexFormats.POSITION;
			}
			b.begin(VertexFormat.DrawMode.TRIANGLES, vmf);
			for (int i = 0; i < objToDraw.getNumFaces(); i++) {
				ObjFace face = objToDraw.getFace(i);
				boolean hasNormals = face.containsNormalIndices();
				boolean hasUV = face.containsTexCoordIndices();
				for (int i1 = 0; i1 < face.getNumVertices(); i1++) {
					FloatTuple xyz = objToDraw.getVertex(face.getVertexIndex(i1));
					VertexConsumer vertex = b.vertex(xyz.getX(), xyz.getY(), xyz.getZ());
					if (vmf == VertexFormats.POSITION_TEXTURE_COLOR_NORMAL) {
						if (!hasUV) {
							throw new IllegalStateException(
									"Diffuse texture present, vertex doesn't have UV coordinates. File corrupted?");
						}
						if (!hasNormals) {
							throw new IllegalStateException(
									"Diffuse texture present, vertex doesn't have normal coordinates. File corrupted?");
						}
						FloatTuple uvs = objToDraw.getTexCoord(face.getTexCoordIndex(i1));
						vertex.texture(uvs.getX(), 1 - uvs.getY());
					}
					if (vmf == VertexFormats.POSITION_TEXTURE_COLOR_NORMAL || vmf == VertexFormats.POSITION_COLOR) {
						Objects.requireNonNull(material);
						FloatTuple kd = material.getKd();
						if (kd != null) {
							vertex.color(kd.getX(), kd.getY(), kd.getZ(), 1f);
						} else {
							vertex.color(1f, 1f, 1f, 1f);
						}
					}
					if (vmf == VertexFormats.POSITION_TEXTURE_COLOR_NORMAL) {
						FloatTuple normals = objToDraw.getNormal(face.getNormalIndex(i1));
						vertex.normal(normals.getX(), normals.getY(), normals.getZ());
					}
					vertex.next();
				}
			}
			BufferBuilder.BuiltBuffer end = b.end();
			buffers.put(objToDraw, BufferUtils.createVbo(end, VertexBuffer.Usage.STATIC));
		}
		baked = true;
	}


	protected Vector3f calculateSunPosition(long worldTime) {

		double timeFraction = worldTime % 24000.0 / 24000.0;
		double angle = ((timeFraction) * 2.0 * Math.PI);

		// Calculate sun X and Y position using cosine and sine to create the unit circle.
		// Reverse Y since in most systems, positive Y goes up.
		float sunX = (float) Math.cos(angle);
		float sunY = (float) Math.sin(angle);

		// The sun doesn't move in the Z direction so Z is 0.
		float sunZ = 0.0f;

		return new Vector3f(sunX, sunY, sunZ);
	}


	/**
	 * You can provide your own shader
	 * Overridable, is called in draw()
	 *
	 * @return shader used in draw
	 */
	protected ShaderProgram getNormalLitShader()
	{
		return ShaderManager.OBJ_SHADER.getProgram();
	}

	/**
	 * Draws this ObjFile. Calls {@link #bake()} if necessary.
	 * Automatically calculates light level at only the origin applied to the entire object.
	 *
	 * @param stack      MatrixStack
	 * @param viewMatrix View matrix to apply to this ObjFile, independent of any other matrix.
	 * @param origin     Origin point to draw at
	 */
	public void draw(MatrixStack stack, Matrix4f viewMatrix, Vec3d origin)
	{
		BlockPos bp = BlockPos.ofFloored(origin);
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		if (world != null) {
			// Compute celestial light based on time of day.
			float celestialAngle = world.getSkyAngleRadians(1.0F);
			float celestialLight = 1.0F - (MathHelper.cos(celestialAngle >= Math.PI ? (float)Math.PI * 2 - celestialAngle : celestialAngle) * 2.0F + 0.2F);
			celestialLight = MathHelper.clamp(celestialLight, 0.0F, 1.0F);
			celestialLight = 1.0F - celestialLight;
			celestialLight = (float)((double)celestialLight * ((1.0D - (double)world.getRainGradient(1.0F) * 5.0F / 16.0D)));
			celestialLight = (float)((double)celestialLight * ((1.0D - (double)world.getThunderGradient(1.0F) * 5.0F / 16.0D)));

			// Compute sky light level.
			int skyLightLevel = world.getLightLevel(LightType.SKY, bp);
			float skyLight = skyLightLevel / 15.0F;

			// Scale celestial light based on sky light.
			float scaledCelestialLight = celestialLight * skyLight;

			// Compute block light.
			int blockLightLevel = world.getLightLevel(LightType.BLOCK, bp);
			float blockLight = blockLightLevel / 15.0F;

			// Combine scaled celestial light with block light.
			float finalLight = Math.max(Math.max(scaledCelestialLight, blockLight), 0.2f);
			draw(stack, viewMatrix, origin, finalLight, calculateSunPosition(world.getTimeOfDay()));
		}
	}

	private static final Matrix4f unmodified_matrix = new Matrix4f();

	/**
	 * Draws this ObjFile. Calls {@link #bake()} if necessary.
	 *
	 * @param stack      MatrixStack
	 * @param viewMatrix View matrix to apply to this ObjFile, independent of any other matrix.
	 * @param origin     Origin point to draw at
	 * @param lightLevel Light level to render the model at
	 */
	public void draw(MatrixStack stack, Matrix4f viewMatrix, Vec3d origin, float lightLevel, Vector3f lightPos) {
		if (closed) {
			throw new IllegalStateException("Closed");
		}
		if (!baked) {
			bake();
		}
		Vec3d o = transformVec3d(origin);
		Matrix4f projectionMatrix = RenderSystem.getProjectionMatrix();
		Matrix4f m4f = new Matrix4f(stack.peek().getPositionMatrix());
		m4f.translate((float) o.x, (float) o.y, (float) o.z);
		m4f.mul(viewMatrix);

		RendererUtils.setupRender();
		RenderSystem.enableCull();
		for (Map.Entry<String, Obj> stringObjEntry : materialNameObjMap.entrySet()) {
			String materialName = stringObjEntry.getKey();
			Obj obj = stringObjEntry.getValue();
			Mtl material = allMaterials.stream().filter(f -> f.getName().equals(materialName)).findFirst().orElse(null);
			boolean hasTexture = material != null && material.getMapKd() != null;
			if (hasTexture) {
				String mapKd = material.getMapKd();
				Identifier identifier = boundTextures.get(mapKd);
				RenderSystem.setShaderTexture(0, identifier);
			}
			Supplier<ShaderProgram> shader;
			if (material != null) {
				shader = hasTexture ? this::getNormalLitShader : GameRenderer::getPositionColorProgram;
				shader.get().bind();
				ShaderManager.OBJ_SHADER.findUniform1f("LightLevel").set(lightLevel);

				if (!viewMatrix.equals(unmodified_matrix))
				{
					// this calculation is expensive.
					Matrix4f invViewMatrix = new Matrix4f(viewMatrix);
					invViewMatrix.invert();
					lightPos = new Vector3f(
							invViewMatrix.m00() * lightPos.x + invViewMatrix.m10() * lightPos.y + invViewMatrix.m20() * lightPos.z,
							invViewMatrix.m01() * lightPos.x + invViewMatrix.m11() * lightPos.y + invViewMatrix.m21() * lightPos.z,
							invViewMatrix.m02() * lightPos.x + invViewMatrix.m12() * lightPos.y + invViewMatrix.m22() * lightPos.z);
				}

				ShaderManager.OBJ_SHADER.findUniform3f("LightPosition").set(lightPos);
			} else {
				shader = GameRenderer::getPositionProgram;
			}
			VertexBuffer vertexBuffer = buffers.get(obj);
			vertexBuffer.bind();
			vertexBuffer.draw(m4f, projectionMatrix, shader.get());
		}
		VertexBuffer.unbind();
		RendererUtils.endRender();
	}

	/**
	 * Clears all associated VertexBuffers, removes every linked texture and closes this ObjFile. All subsequent calls to any method will fail.
	 */
	@Override
	public void close() {
		for (VertexBuffer buffer : buffers.values()) {
			buffer.close();
		}
		buffers.clear();
		for (Identifier value : boundTextures.values()) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(value);
		}
		boundTextures.clear();
		allMaterials.clear();
		closed = true;
	}

	/**
	 * A function, which maps a resource name found in an .obj file to an InputStream
	 */
	@FunctionalInterface
	public interface ResourceProvider {
		/**
		 * Appends the filename to read to a Path, then tries to load the resulting file.
		 *
		 * @param parent Parent path of all files
		 * @return New ResourceProvider
		 */
		static ResourceProvider ofPath(Path parent) {
			return name -> {
				Path resolve = parent.resolve(name);
				return Files.newInputStream(resolve);
			};
		}

		/**
		 * Opens {@code name} as InputStream
		 *
		 * @param name Filename to open
		 * @return The opened InputStream. Closed by the library when done.
		 */
		InputStream open(String name) throws IOException;
	}
}
