package ru.arthaix.meshimporter.client.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import ru.arthaix.meshimporter.MeshImporter;
import ru.arthaix.meshimporter.MeshImporterConfig;

/**
 * The mesh shader: texture x vertex colour x Minecraft's lightmap, where the sky part of the light is read per pixel
 * from the mesh's baked light volume (two 3D textures, +XYZ and -XYZ) with the surface normal from screen-space
 * derivatives. The texture alpha weighs cells (RGB is premultiplied), so cells inside walls and slabs drop out of the
 * interpolation. World sky and block light are read per pixel from a third 3D texture sampled from the world (same
 * weighting: blocks inside terrain drop out). Fixed-function fog is reproduced.
 */
public final class Shaders {

    private static final String VERTEX = String.join("\n",
        "#version 120",
        "varying vec3 vPos;",
        "varying vec4 vColor;",
        "varying vec2 vUv;",
        "varying vec2 vLight;",
        "varying float vFog;",
        "void main() {",
        "    vPos = gl_Vertex.xyz;",
        "    vColor = gl_Color;",
        "    vUv = gl_MultiTexCoord0.st;",
        "    vLight = gl_MultiTexCoord1.st;",
        "    vec4 eye = gl_ModelViewMatrix * gl_Vertex;",
        "    vFog = length(eye.xyz);",
        "    gl_Position = gl_ProjectionMatrix * eye;",
        "}");

    private static final String FRAGMENT = String.join("\n",
        "#version 120",
        "uniform sampler2D tex;",
        "uniform sampler2D lightmap;",
        "uniform sampler3D skyPos;",
        "uniform sampler3D skyNeg;",
        "uniform sampler3D worldLight;",
        "uniform float useWorld;",
        "uniform vec3 volOrigin;",
        "uniform vec3 volSize;",
        "uniform float useVolume;",
        "uniform float glow;",
        "uniform float alphaCut;",
        "uniform float floorLevel;",
        "uniform int fogMode;",
        "varying vec3 vPos;",
        "varying vec4 vColor;",
        "varying vec2 vUv;",
        "varying vec2 vLight;",
        "varying float vFog;",
        "void main() {",
        "    vec4 albedo = texture2D(tex, vUv) * vColor;",
        "    if (albedo.a < alphaCut) discard;",
        "    float sky = vLight.y / 16.0;",
        "    float block = vLight.x / 16.0;",
        "    if (glow > 0.5) {",
        "        sky = 15.0;",
        "        block = 15.0;",
        "    } else if (useVolume > 0.5 || useWorld > 0.5) {",
        "        vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));",
        "        vec3 q = (vPos + n * 0.5 - volOrigin) / volSize;",
        "        if (useWorld > 0.5) {",
        "            vec4 wl = texture3D(worldLight, q);",
        "            float weight = max(wl.a, 0.0001);",
        "            sky = wl.r / weight * 15.0;",
        "            block = wl.g / weight * 15.0;",
        "        }",
        "        if (useVolume > 0.5) {",
        "            vec4 plus = texture3D(skyPos, q);",
        "            vec4 minus = texture3D(skyNeg, q);",
        "            vec3 face = mix(minus.rgb, plus.rgb, step(0.0, n));",
        "            float vis = dot(n * n, face) / max(plus.a, 0.0001);",
        "            sky = min(sky, clamp(15.0 + 2.0 * log2(max(vis, 0.0001)), floorLevel, 15.0));",
        "        }",
        "    }",
        "    vec3 light = texture2D(lightmap, (vec2(block, sky) * 16.0 + 8.0) / 256.0).rgb;",
        "    vec4 color = vec4(albedo.rgb * light, albedo.a);",
        "    if (fogMode != 0) {",
        "        float f;",
        "        if (fogMode == 1) f = (gl_Fog.end - vFog) * gl_Fog.scale;",
        "        else if (fogMode == 2) f = exp(-gl_Fog.density * vFog);",
        "        else f = exp(-(gl_Fog.density * vFog) * (gl_Fog.density * vFog));",
        "        color.rgb = mix(gl_Fog.color.rgb, color.rgb, clamp(f, 0.0, 1.0));",
        "    }",
        "    gl_FragColor = color;",
        "}");

    private static int program = -1;
    private static boolean tried;
    private static int uOrigin, uSize, uUseVolume, uUseWorld, uGlow, uAlphaCut, uFloor, uFogMode;
    private static int boundPos = -2, boundNeg = -2, boundWorld = -2;
    private static int previousProgram;
    private static java.lang.reflect.Method optifineShaders;
    private static java.lang.reflect.Field[] packUse;
    private static java.lang.reflect.Field[] packSlot;
    private static final int[] packSlots = new int[3];
    private static final float[][] packSaved = new float[3][4];
    private static final boolean[] packWasArray = new boolean[3];
    private static final java.nio.FloatBuffer PACK_BUF = org.lwjgl.BufferUtils.createFloatBuffer(16);
    private static final java.nio.IntBuffer PACK_INT = org.lwjgl.BufferUtils.createIntBuffer(16);
    private static boolean packLooked;
    /** Tangent slot while a pack draw is running (our VBOs carry a tangent), -1 otherwise. */
    private static int packTangent = -1;
    private static java.lang.reflect.Field packDefaultTexture;
    private static java.lang.reflect.Method packBindNS;
    private static boolean nsLooked;
    private static final int[] nsSaved = { -1, -1 };
    private static java.lang.reflect.Field optifinePack;
    private static boolean optifineLooked;

    private Shaders() {}

    /** Game thread (GL context). Compiles the shader the first time; true when meshes are drawn with it. */
    public static boolean ensure() {
        if (!tried) {
            tried = true;
            compile();
        }
        return active();
    }

    public static boolean active() {
        return MeshImporterConfig.shaderLighting && program > 0 && !optifineShaderPack();
    }

    /** Whether an OptiFine shader pack is drawing the world right now. */
    public static boolean shaderPack() {
        return optifineShaderPack();
    }

    /**
     * A shader pack binds three attributes of its own to every program (mc_Entity, mc_midTexCoord, at_tangent), and
     * Minecraft fills them while it builds its own geometry. Our meshes come from plain VBOs that carry none of them,
     * so an array still switched on from someone else's drawing would be read past its end - hence the torn and
     * missing triangles. Switch those arrays off for the duration and leave a sane constant in their place.
     */
    public static void beginPack(boolean tangentArray) {
        int[] att = packAttribs();
        if (att == null) return;
        for (int i = 0; i < att.length; i++) {
            if (att[i] < 0) continue;
            PACK_INT.clear();
            GL20.glGetVertexAttrib(att[i], GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED, PACK_INT);
            packWasArray[i] = PACK_INT.get(0) != 0;
            PACK_BUF.clear();
            GL20.glGetVertexAttrib(att[i], GL20.GL_CURRENT_VERTEX_ATTRIB, PACK_BUF);
            for (int k = 0; k < 4; k++) packSaved[i][k] = PACK_BUF.get(k);
            if (packWasArray[i]) GL20.glDisableVertexAttribArray(att[i]);
        }
        // mc_Entity: not a block; mc_midTexCoord: no sprite centre to give; at_tangent: any one direction, handedness +1
        if (att[0] >= 0) GL20.glVertexAttrib4f(att[0], -1f, -1f, 0f, 0f);
        if (att[1] >= 0) GL20.glVertexAttrib4f(att[1], 0f, 0f, 0f, 1f);
        // the tangent comes from our own buffer (RenderData.pointers): a constant one is parallel to every face
        // that looks along it, and the pack's normal-map frame turns into NaN there
        packTangent = tangentArray ? att[2] : -1;
        if (packTangent >= 0) GL20.glEnableVertexAttribArray(packTangent);
        bindNeutralMaps();
    }

    private static java.lang.reflect.Method packUseProgram;
    private static java.lang.reflect.Field packWater, packActive, packShadowPass;
    private static boolean waterLooked;
    private static Object packPrevious;

    /**
     * See-through surfaces under a pack: the entity program the pack gives us (gbuffers_entities) has blending
     * switched off and writes alpha 1, so glass came out as dark opaque panes. Draw them with the program of
     * translucent terrain instead (gbuffers_water - glass, not water, for a block without an id), with depth written
     * like OptiFine does for water. Not in the shadow pass, which has one program for everything.
     */
    public static void beginPackTranslucent() {
        packPrevious = null;
        if (!waterLooked) {
            waterLooked = true;
            try {
                Class<?> c = Class.forName("net.optifine.shaders.Shaders", false, Shaders.class.getClassLoader());
                packWater = c.getField("ProgramWater");
                packActive = c.getField("activeProgram");
                packShadowPass = c.getField("isShadowPass");
                packUseProgram = c.getMethod("useProgram", packWater.getType());
            } catch (ReflectiveOperationException | LinkageError e) {
                packUseProgram = null;
                MeshImporter.logger.warn("OptiFine has no water program to lend: see-through mesh surfaces stay opaque under a shader pack: " + e);
            }
        }
        if (packUseProgram == null) return;
        try {
            if (packShadowPass.getBoolean(null)) return;
            Object previous = packActive.get(null);
            packUseProgram.invoke(null, packWater.get(null));
            packPrevious = previous;
        } catch (ReflectiveOperationException | RuntimeException e) {
            packUseProgram = null;
            MeshImporter.logger.warn("Could not switch to the shader pack's water program: " + e);
        }
    }

    private static java.lang.reflect.Field shadowPassField;
    private static boolean shadowPassLooked;

    /** Whether OptiFine is drawing the shadow map of a pack right now (it draws entity passes 0 and 1 there too). */
    public static boolean packShadowPass() {
        if (!shadowPassLooked) {
            shadowPassLooked = true;
            try {
                shadowPassField = Class.forName("net.optifine.shaders.Shaders", false, Shaders.class.getClassLoader()).getField("isShadowPass");
            } catch (ReflectiveOperationException | LinkageError e) {
                shadowPassField = null;
            }
        }
        try {
            return shadowPassField != null && shadowPassField.getBoolean(null);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static void endPackTranslucent() {
        if (packPrevious == null) return;
        try {
            packUseProgram.invoke(null, packPrevious);
        } catch (ReflectiveOperationException | RuntimeException e) {
            packUseProgram = null;
        }
        packPrevious = null;
    }

    /** Tangent attribute slot to point at our buffer while a pack draw runs, -1 when there is none. */
    public static int packTangentSlot() {
        return packTangent;
    }

    /**
     * The pack reads normal and specular maps from units 2 and 3. OptiFine only sets them when a texture goes through
     * its TextureManager; ours are bound directly, so those units still held whatever was drawn before (usually the
     * block atlas maps of the resource pack), and our walls picked up random smoothness and metalness at their UVs -
     * dark, mirror-like facades. Bind OptiFine's own flat normal and blank specular for the duration.
     */
    private static void bindNeutralMaps() {
        nsSaved[0] = nsSaved[1] = -1;
        if (!nsLooked) {
            nsLooked = true;
            try {
                ClassLoader loader = Shaders.class.getClassLoader();
                packDefaultTexture = Class.forName("net.optifine.shaders.Shaders", false, loader).getField("defaultTexture");
                packBindNS = Class.forName("net.optifine.shaders.ShadersTex", false, loader).getMethod("bindNSTextures", int.class, int.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                packDefaultTexture = null;
                packBindNS = null;
                MeshImporter.logger.warn("OptiFine keeps its default normal/specular maps elsewhere; meshes keep whatever is bound: " + e);
            }
        }
        if (packBindNS == null) return;
        try {
            Object tex = packDefaultTexture.get(null);
            if (tex == null) return;
            Object multi = net.minecraft.client.renderer.texture.ITextureObject.class.getMethod("getMultiTexID").invoke(tex);
            if (multi == null) return;
            int norm = multi.getClass().getField("norm").getInt(multi);
            int spec = multi.getClass().getField("spec").getInt(multi);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 2);
            nsSaved[0] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 3);
            nsSaved[1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            packBindNS.invoke(null, norm, spec);
        } catch (ReflectiveOperationException | RuntimeException e) {
            nsSaved[0] = nsSaved[1] = -1;
            packBindNS = null;
            MeshImporter.logger.warn("Could not bind OptiFine's default normal/specular maps: " + e);
        }
    }

    /** Gives the shader pack's attributes back exactly as they were. */
    public static void endPack() {
        int[] att = packAttribs();
        if (nsSaved[0] >= 0 && packBindNS != null) {
            try {
                packBindNS.invoke(null, nsSaved[0], nsSaved[1]);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // the next texture OptiFine binds sets them again anyway
            }
        }
        nsSaved[0] = nsSaved[1] = -1;
        int tangentArray = packTangent;
        packTangent = -1;
        if (att == null) return;
        for (int i = 0; i < att.length; i++) {
            if (att[i] < 0) continue;
            if (i == 2 && tangentArray >= 0) GL20.glDisableVertexAttribArray(att[i]);
            GL20.glVertexAttrib4f(att[i], packSaved[i][0], packSaved[i][1], packSaved[i][2], packSaved[i][3]);
            if (packWasArray[i]) GL20.glEnableVertexAttribArray(att[i]);
        }
    }

    /** The attribute slots the loaded pack actually uses, or -1 each; null when OptiFine does not tell us. */
    private static int[] packAttribs() {
        if (!packLooked) {
            packLooked = true;
            try {
                Class<?> c = Class.forName("net.optifine.shaders.Shaders", false, Shaders.class.getClassLoader());
                packUse = new java.lang.reflect.Field[] {
                    c.getField("useEntityAttrib"), c.getField("useMidTexCoordAttrib"), c.getField("useTangentAttrib") };
                packSlot = new java.lang.reflect.Field[] {
                    c.getField("entityAttrib"), c.getField("midTexCoordAttrib"), c.getField("tangentAttrib") };
            } catch (ReflectiveOperationException | LinkageError ignored) {
                packUse = null;
                packSlot = null;
            }
        }
        if (packUse == null) return null;
        try {
            for (int i = 0; i < 3; i++) packSlots[i] = packUse[i].getBoolean(null) ? packSlot[i].getInt(null) : -1;
        } catch (ReflectiveOperationException e) {
            packUse = null;
            return null;
        }
        return packSlots;
    }

    /**
     * An OptiFine shader pack owns the pipeline (several render targets): meshes then go through it as plain geometry
     * instead of our own shader, which would otherwise paint into its buffers and come out inside out and full of
     * holes. Where the answer lives differs between OptiFine versions: Config.isShaders() is the one that has always
     * been there, the Shaders fields are the fallback.
     */
    private static boolean optifineShaderPack() {
        if (!optifineLooked) {
            optifineLooked = true;
            ClassLoader loader = Shaders.class.getClassLoader();
            try {
                optifineShaders = Class.forName("Config", false, loader).getMethod("isShaders");
                MeshImporter.logger.info("OptiFine found: meshes step aside while a shader pack is on");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                optifineShaders = null;
            }
            if (optifineShaders == null) {
                try {
                    Class<?> shaders = Class.forName("net.optifine.shaders.Shaders", false, loader);
                    try {
                        optifinePack = shaders.getField("shaderPackLoaded");
                    } catch (NoSuchFieldException e) {
                        optifinePack = shaders.getField("isShaderPackInitialized");
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    optifinePack = null;
                }
            }
        }
        try {
            if (optifineShaders != null) return Boolean.TRUE.equals(optifineShaders.invoke(null));
            if (optifinePack != null) return optifinePack.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        return false;
    }

    private static void compile() {
        if (!MeshImporterConfig.shaderLighting) return;
        if (!OpenGlHelper.shadersSupported) {
            MeshImporter.logger.warn("Shaders are not supported by this graphics driver: meshes are lit per vertex");
            return;
        }
        try {
            int vs = shader(GL20.GL_VERTEX_SHADER, VERTEX);
            int fs = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
            if (vs == 0 || fs == 0) return;
            int p = GL20.glCreateProgram();
            GL20.glAttachShader(p, vs);
            GL20.glAttachShader(p, fs);
            GL20.glLinkProgram(p);
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                MeshImporter.logger.warn("Mesh shader does not link, meshes are lit per vertex: " + GL20.glGetProgramInfoLog(p, 4096));
                GL20.glDeleteProgram(p);
                return;
            }
            GL20.glUseProgram(p);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "tex"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "lightmap"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "skyPos"), 2);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "skyNeg"), 3);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "worldLight"), 4);
            uUseWorld = GL20.glGetUniformLocation(p, "useWorld");
            uOrigin = GL20.glGetUniformLocation(p, "volOrigin");
            uSize = GL20.glGetUniformLocation(p, "volSize");
            uUseVolume = GL20.glGetUniformLocation(p, "useVolume");
            uGlow = GL20.glGetUniformLocation(p, "glow");
            uAlphaCut = GL20.glGetUniformLocation(p, "alphaCut");
            uFloor = GL20.glGetUniformLocation(p, "floorLevel");
            uFogMode = GL20.glGetUniformLocation(p, "fogMode");
            GL20.glUseProgram(0);
            program = p;
            MeshImporter.logger.info("Mesh shader ready: per-pixel baked lighting");
        } catch (Throwable t) {
            MeshImporter.logger.warn("Mesh shader unavailable, meshes are lit per vertex: " + t);
            program = -1;
        }
    }

    private static int shader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            MeshImporter.logger.warn("Mesh shader does not compile, meshes are lit per vertex: " + GL20.glGetShaderInfoLog(s, 4096));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /** Before drawing a pass (0 = opaque, 1 = translucent). */
    public static void begin(int pass) {
        previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(program);
        GL20.glUniform1f(uAlphaCut, pass == 0 ? 0.1f : 0.004f);
        GL20.glUniform1f(uFloor, MeshImporterConfig.shadowLevel);
        int mode = 0;
        if (GL11.glIsEnabled(GL11.GL_FOG)) {
            int m = GL11.glGetInteger(GL11.GL_FOG_MODE);
            mode = m == GL11.GL_LINEAR ? 1 : m == GL11.GL_EXP ? 2 : 3;
        }
        GL20.glUniform1i(uFogMode, mode);
        GL20.glUniform1f(uGlow, 0f);
        boundPos = boundNeg = boundWorld = -2;
    }

    public static void glow(boolean glow) {
        GL20.glUniform1f(uGlow, glow ? 1f : 0f);
    }

    /** Light textures of the cell about to be drawn (ids < 0 = none: open sky, vertex world light) and the box they cover. */
    public static void volume(int texPos, int texNeg, int texWorld, float ox, float oy, float oz, float sx, float sy, float sz) {
        boolean baked = texPos >= 0 && texNeg >= 0, world = texWorld >= 0;
        GL20.glUniform1f(uUseVolume, baked ? 1f : 0f);
        GL20.glUniform1f(uUseWorld, world ? 1f : 0f);
        if (!baked && !world) return;
        GL20.glUniform3f(uOrigin, ox, oy, oz);
        GL20.glUniform3f(uSize, sx, sy, sz);
        boolean switched = false;
        if (baked && (texPos != boundPos || texNeg != boundNeg)) {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 2);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texPos);
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 3);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texNeg);
            boundPos = texPos;
            boundNeg = texNeg;
            switched = true;
        }
        if (world && texWorld != boundWorld) {
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 4);
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, texWorld);
            boundWorld = texWorld;
            switched = true;
        }
        if (switched) GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /** After the pass: back to fixed function with nothing left bound on the extra units. */
    public static void end() {
        GL20.glUseProgram(previousProgram);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 2);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 3);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit + 4);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        boundPos = boundNeg = boundWorld = -2;
    }
}
