package com.graphics;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * AppLaberinto:
 * Demo 3D first-person con colisiones contra muros sobre una grilla.
 *
 * Objetivo:
 * - Empezar en celda INICIO.
 * - Navegar por el laberinto sin atravesar muros.
 * - Llegar a la celda META (cubo dorado) para ganar.
 *
 * Ideas pedagogicas centrales:
 * 1) Mapa en matriz (logica) -> coordenadas de mundo (render).
 * 2) Camara first-person con yaw/pitch.
 * 3) Deteccion de colision por celdas cercanas.
 * 4) Reutilizacion de una sola malla de cubo para piso, muros y meta.
 */
public class AppLaberinto {

    // Recursos nativos/OpenGL.
    private long window;
    private int programa;
    private int vao;
    private int vbo;

    private int uModelOffsetLocation;
    private int uModelScaleLocation;
    private int uTintLocation;
    private int uCamPosLocation;
    private int uCamRotLocation;
    private int uAspectLocation;

    // Resolucion inicial.
    private static final int ANCHO = 1100;
    private static final int ALTO = 750;

    // Escala de mundo.
    private static final float TILE = 2.0f;
    private static final float WALL_HEIGHT = 2.0f;
    private static final float FLOOR_THICKNESS = 0.1f;
    // "Radio" del jugador para colisiones.
    private static final float PLAYER_RADIUS = 0.25f;

    // Tipos de celda en la matriz.
    private static final int MURO = 1;
    private static final int META = 2;
    private static final int INICIO = 3;

    // Laberinto base: 1 muro, 0 camino, 3 inicio, 2 meta.
    private static final int[][] LABERINTO = {
        {1,1,1,1,1,1,1,1,1,1,1},
        {1,3,0,0,0,1,0,0,0,0,1},
        {1,0,1,1,0,1,0,1,1,0,1},
        {1,0,1,0,0,0,0,0,1,0,1},
        {1,0,1,0,1,1,1,0,1,0,1},
        {1,0,0,0,1,0,0,0,1,0,1},
        {1,1,1,0,1,0,1,1,1,0,1},
        {1,0,0,0,0,0,1,0,0,0,1},
        {1,0,1,1,1,0,1,0,1,0,1},
        {1,0,0,0,1,0,0,0,1,2,1},
        {1,1,1,1,1,1,1,1,1,1,1}
    };

    // Camara/jugador (first-person): XZ en piso, Y fija.
    private float camX;
    private static final float CAM_Y = 0.35f;
    private float camZ;
    private float yaw = 0.0f;
    private float pitch = 0.0f;

    private float goalX;
    private float goalZ;

    private static final float VELOCIDAD = 2.8f;
    private static final float VELOCIDAD_GIRO = 1.8f;
    private static final float PITCH_MIN = -1.2f;
    private static final float PITCH_MAX = 1.2f;

    // Estado de victoria.
    private boolean gano = false;

    // Flujo principal.
    public void run() {
        init();
        loop();
        cleanup();
    }

    // Inicializacion de ventana, GL, mapa y recursos.
    private void init() {
        // Arranque GLFW.
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("No se pudo iniciar GLFW");
        }

        // Configuracion de ventana/contexto OpenGL 3.3 core.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        // Crear ventana.
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "AppLaberinto - Llega al cubo dorado", 0, 0);
        if (window == 0) {
            throw new RuntimeException("No se pudo crear la ventana");
        }

        // ESC cierra app.
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                GLFW.glfwSetWindowShouldClose(w, true);
            }
        });

        // Contexto actual, vsync y mostrar.
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GLFW.glfwShowWindow(window);
        // Cargar funciones GL.
        GL.createCapabilities();
        // Activar z-buffer para 3D correcto.
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        // Localizar spawn/meta desde la matriz.
        localizarInicioYMeta();
        // Preparar pipeline y geometria.
        crearShaders();
        crearCubo();
    }

    /**
     * Recorre matriz para encontrar celdas INICIO y META,
     * convirtiendo indices [fila,col] a coordenadas de mundo.
     */
    private void localizarInicioYMeta() {
        for (int fila = 0; fila < LABERINTO.length; fila++) {
            for (int col = 0; col < LABERINTO[fila].length; col++) {
                if (LABERINTO[fila][col] == INICIO) {
                    camX = cellCenterX(col);
                    camZ = cellCenterZ(fila);
                }
                if (LABERINTO[fila][col] == META) {
                    goalX = cellCenterX(col);
                    goalZ = cellCenterZ(fila);
                }
            }
        }
    }

    /**
     * Vertex shader:
     * - Escala y traslada el cubo (modelo),
     * - aplica vista first-person (camara),
     * - aplica proyeccion perspectiva.
     *
     * Fragment shader:
     * - solo color interpolado.
     */
    private void crearShaders() {
        String vertexSrc = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aColor;

            uniform vec3 uModelOffset;
            uniform vec3 uModelScale;
            uniform vec3 uTint;
            uniform vec3 uCamPos;
            uniform vec2 uCamRot; // x=pitch, y=yaw
            uniform float uAspect;

            out vec3 vColor;

            void main() {
                vec3 worldPos = (aPos * uModelScale) + uModelOffset;
                vec3 viewPos = worldPos - uCamPos;

                float yaw = -uCamRot.y;
                float pitch = -uCamRot.x;

                mat3 rotYaw = mat3(
                    cos(yaw), 0.0, sin(yaw),
                    0.0,      1.0, 0.0,
                    -sin(yaw),0.0, cos(yaw)
                );
                mat3 rotPitch = mat3(
                    1.0, 0.0,       0.0,
                    0.0, cos(pitch),-sin(pitch),
                    0.0, sin(pitch), cos(pitch)
                );

                vec3 camSpace = rotPitch * (rotYaw * viewPos);

                float fov = radians(62.0);
                float f = 1.0 / tan(fov * 0.5);
                float near = 0.1;
                float far = 100.0;

                vec4 clip;
                clip.x = camSpace.x * (f / uAspect);
                clip.y = camSpace.y * f;
                clip.z = ((far + near) / (near - far)) * camSpace.z
                       + ((2.0 * far * near) / (near - far));
                clip.w = -camSpace.z;
                gl_Position = clip;

                vColor = aColor * uTint;
            }
            """;

        String fragmentSrc = """
            #version 330 core
            in vec3 vColor;
            out vec4 fragColor;
            void main() { fragColor = vec4(vColor, 1.0); }
            """;

        // Compilacion vertex shader.
        int vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertexShader, vertexSrc);
        GL20.glCompileShader(vertexShader);
        comprobarShader(vertexShader, "Vertex");

        // Compilacion fragment shader.
        int fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragmentShader, fragmentSrc);
        GL20.glCompileShader(fragmentShader);
        comprobarShader(fragmentShader, "Fragment");

        // Link de programa.
        programa = GL20.glCreateProgram();
        GL20.glAttachShader(programa, vertexShader);
        GL20.glAttachShader(programa, fragmentShader);
        GL20.glLinkProgram(programa);

        if (GL20.glGetProgrami(programa, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Error al enlazar programa: " + GL20.glGetProgramInfoLog(programa));
        }

        // Uniform locations.
        uModelOffsetLocation = GL20.glGetUniformLocation(programa, "uModelOffset");
        uModelScaleLocation = GL20.glGetUniformLocation(programa, "uModelScale");
        uTintLocation = GL20.glGetUniformLocation(programa, "uTint");
        uCamPosLocation = GL20.glGetUniformLocation(programa, "uCamPos");
        uCamRotLocation = GL20.glGetUniformLocation(programa, "uCamRot");
        uAspectLocation = GL20.glGetUniformLocation(programa, "uAspect");
        if (uModelOffsetLocation == -1 || uModelScaleLocation == -1 || uTintLocation == -1
                || uCamPosLocation == -1 || uCamRotLocation == -1 || uAspectLocation == -1) {
            throw new RuntimeException("No se encontraron uniforms requeridos");
        }

        // Liberar shaders temporales.
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    // Helper de validacion de compilacion GLSL.
    private void comprobarShader(int shader, String tipo) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException(tipo + " shader: " + GL20.glGetShaderInfoLog(shader));
        }
    }

    /**
     * Crea cubo unitario coloreado por cara.
     * Luego se instancia muchisimas veces con offset+scale distintos.
     */
    private void crearCubo() {
        float[] vertices = {
            // Cara frontal (rojo)
            -0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
            -0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
            -0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,

            // Cara trasera (verde)
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
            -0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,

            // Cara izquierda (azul)
            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, 1.0f,

            // Cara derecha (amarillo)
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.0f,

            // Cara superior (magenta)
            -0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 1.0f,

            // Cara inferior (cian)
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 1.0f,
             0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 1.0f
        };

        // Config VAO.
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Subida VBO.
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // Copiar vertices a GPU.
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        // Atributo posicion (location 0).
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Atributo color (location 1).
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Desbind.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Input y movimiento del jugador:
     * - W/S avanzar-retroceder
     * - A/D strafe
     * - Flechas giran camara
     * - Shift acelera
     *
     * Importante:
     * Se calcula posicion tentativa (nextX,nextZ) y luego se valida con colisiones.
     */
    private void procesarInput(float deltaTime) {
        float vel = VELOCIDAD * deltaTime;
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) {
            vel *= 1.8f;
        }
        float velGiro = VELOCIDAD_GIRO * deltaTime;

        float forwardX = (float) Math.sin(yaw);
        float forwardZ = (float) -Math.cos(yaw);
        float rightX = (float) Math.cos(yaw);
        float rightZ = (float) Math.sin(yaw);

        // Posicion tentativa antes de validar.
        float nextX = camX;
        float nextZ = camZ;

        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) {
            nextX += forwardX * vel;
            nextZ += forwardZ * vel;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) {
            nextX -= forwardX * vel;
            nextZ -= forwardZ * vel;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) {
            nextX -= rightX * vel;
            nextZ -= rightZ * vel;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) {
            nextX += rightX * vel;
            nextZ += rightZ * vel;
        }

        // Resolver por ejes independientes para permitir "slide" en esquinas.
        if (puedeMover(nextX, camZ)) {
            camX = nextX;
        }
        if (puedeMover(camX, nextZ)) {
            camZ = nextZ;
        }

        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) {
            yaw += velGiro;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) {
            yaw -= velGiro;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            pitch += velGiro;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
            pitch -= velGiro;
        }

        // Limitar pitch para evitar mirada invertida/extrema.
        pitch = clamp(pitch, PITCH_MIN, PITCH_MAX);
    }

    /**
     * Valida si una posicion (x,z) colisiona con algun muro.
     *
     * Estrategia:
     * - Convertir bounding box del jugador (con radio) a rango de celdas.
     * - Revisar solo esas celdas cercanas (eficiente).
     * - Para cada muro, comprobar overlap AABB en X y Z.
     */
    private boolean puedeMover(float x, float z) {
        // Celdas potencialmente tocadas por el "disco" del jugador.
        int cMin = Math.max(0, (int) Math.floor((x - PLAYER_RADIUS - worldMinX()) / TILE));
        int cMax = Math.min(LABERINTO[0].length - 1, (int) Math.floor((x + PLAYER_RADIUS - worldMinX()) / TILE));
        int fMin = Math.max(0, (int) Math.floor((z - PLAYER_RADIUS - worldMinZ()) / TILE));
        int fMax = Math.min(LABERINTO.length - 1, (int) Math.floor((z + PLAYER_RADIUS - worldMinZ()) / TILE));

        for (int fila = fMin; fila <= fMax; fila++) {
            for (int col = cMin; col <= cMax; col++) {
                if (LABERINTO[fila][col] != MURO) {
                    continue;
                }
                float cx = cellCenterX(col);
                float cz = cellCenterZ(fila);
                float half = TILE * 0.5f;

                // Overlap AABB expandido por radio del jugador.
                boolean hitX = x > cx - half - PLAYER_RADIUS && x < cx + half + PLAYER_RADIUS;
                boolean hitZ = z > cz - half - PLAYER_RADIUS && z < cz + half + PLAYER_RADIUS;
                if (hitX && hitZ) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Comprueba condicion de victoria:
     * si la camara esta suficientemente cerca del centro de META.
     */
    private void verificarVictoria() {
        if (gano) {
            return;
        }
        float dx = camX - goalX;
        float dz = camZ - goalZ;
        if (dx * dx + dz * dz < 0.45f * 0.45f) {
            gano = true;
            GLFW.glfwSetWindowTitle(window, "AppLaberinto - GANASTE! (ESC para salir)");
        }
    }

    /**
     * Renderiza todo el laberinto iterando la matriz.
     * Cada celda puede dibujar:
     * - piso (si no es muro),
     * - muro (si MURO),
     * - meta dorada (si META).
     */
    private void renderLaberinto() {
        // Activar programa y configurar camara.
        GL20.glUseProgram(programa);
        GL20.glUniform3f(uCamPosLocation, camX, CAM_Y, camZ);
        GL20.glUniform2f(uCamRotLocation, pitch, yaw);
        GL20.glUniform1f(uAspectLocation, (float) ANCHO / (float) ALTO);
        GL30.glBindVertexArray(vao);

        for (int fila = 0; fila < LABERINTO.length; fila++) {
            for (int col = 0; col < LABERINTO[fila].length; col++) {
                int celda = LABERINTO[fila][col];
                float x = cellCenterX(col);
                float z = cellCenterZ(fila);

                // Piso para celdas no-muro.
                if (celda != MURO) {
                    // Offset del piso (ligeramente bajo para no intersecar camara).
                    GL20.glUniform3f(uModelOffsetLocation, x, -1.05f, z);
                    // Escala: ancho/largo de tile y espesor bajo.
                    GL20.glUniform3f(uModelScaleLocation, TILE, FLOOR_THICKNESS, TILE);
                    // Tinte gris oscuro.
                    GL20.glUniform3f(uTintLocation, 0.25f, 0.25f, 0.28f);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
                }

                if (celda == MURO) {
                    // Muro centrado verticalmente sobre el piso.
                    GL20.glUniform3f(uModelOffsetLocation, x, -0.05f, z);
                    GL20.glUniform3f(uModelScaleLocation, TILE, WALL_HEIGHT, TILE);
                    // Tinte claro para distinguir de piso.
                    GL20.glUniform3f(uTintLocation, 0.55f, 0.55f, 0.60f);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
                }

                if (celda == META) {
                    // Cubo objetivo mas pequeno y dorado.
                    GL20.glUniform3f(uModelOffsetLocation, x, -0.55f, z);
                    GL20.glUniform3f(uModelScaleLocation, 0.7f, 0.7f, 0.7f);
                    GL20.glUniform3f(uTintLocation, 1.0f, 0.85f, 0.2f);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
                }
            }
        }
    }

    // Utility clamp.
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // Centro X de una celda (columna -> mundo).
    private float cellCenterX(int col) {
        return worldMinX() + col * TILE + TILE * 0.5f;
    }

    // Centro Z de una celda (fila -> mundo).
    private float cellCenterZ(int fila) {
        return worldMinZ() + fila * TILE + TILE * 0.5f;
    }

    // Borde minimo X del mapa en mundo (centrado en origen).
    private float worldMinX() {
        return -LABERINTO[0].length * TILE * 0.5f;
    }

    // Borde minimo Z del mapa en mundo (centrado en origen).
    private float worldMinZ() {
        return -LABERINTO.length * TILE * 0.5f;
    }

    /**
     * Loop principal:
     * - deltaTime,
     * - input/movimiento,
     * - verificacion victoria,
     * - render,
     * - swap/poll.
     */
    private void loop() {
        float ultimoTiempo = (float) GLFW.glfwGetTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            // Tiempo de frame.
            float tiempoActual = (float) GLFW.glfwGetTime();
            float deltaTime = tiempoActual - ultimoTiempo;
            ultimoTiempo = tiempoActual;

            procesarInput(deltaTime);
            verificarVictoria();

            GL11.glClearColor(0.06f, 0.06f, 0.09f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Dibuja toda la escena.
            renderLaberinto();

            // Presentar frame + eventos.
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    // Limpieza de recursos.
    private void cleanup() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL20.glDeleteProgram(programa);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    // Punto de entrada Java.
    public static void main(String[] args) {
        new AppLaberinto().run();
    }
}
