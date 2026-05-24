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
 * AppCamara:
 * Escena 3D con varios cubos y camara libre tipo first-person.
 *
 * Concepto clave que se enseña aqui:
 * - Los objetos se definen en espacio mundo.
 * - La "camara" no se mueve fisicamente en OpenGL fijo; en su lugar,
 *   transformamos el mundo al espacio de camara aplicando la inversa
 *   de la transformacion de la camara (traslacion y rotacion).
 */
public class AppCamara {

    // Handle de ventana GLFW.
    private long window;
    // Programa de shaders.
    private int programa;
    // Geometria del cubo.
    private int vao;
    private int vbo;

    // Uniforms para modelo, camara y proyeccion.
    private int uModelOffsetLocation;
    private int uCamPosLocation;
    private int uCamRotLocation;
    private int uAspectLocation;

    private static final int ANCHO = 1000;
    private static final int ALTO = 700;

    // Posicion de la camara en el mundo.
    private float camX = 0.0f;
    private float camY = 0.0f;
    private float camZ = 2.8f;

    // Rotacion de camara: yaw = horizontal, pitch = vertical.
    private float yaw = 0.0f;
    private float pitch = 0.0f;

    private static final float VELOCIDAD_CAMARA = 2.2f;
    private static final float VELOCIDAD_GIRO = 1.8f;
    private static final float PITCH_MIN = -1.4f;
    private static final float PITCH_MAX = 1.4f;

    // Posiciones de varios cubos para apreciar mejor el movimiento de camara.
    private static final float[][] CUBOS = {
        { 0.0f,  0.0f,  0.0f},
        { 2.0f,  0.0f, -2.0f},
        {-2.2f,  0.2f, -3.0f},
        { 0.5f,  1.1f, -4.0f},
        {-0.8f, -1.0f, -2.5f}
    };

    // Flujo principal de la aplicacion.
    public void run() {
        init();
        loop();
        cleanup();
    }

    // Inicializa GLFW/OpenGL y recursos.
    private void init() {
        // Arranque de GLFW.
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("No se pudo iniciar GLFW");
        }

        // Configuracion de ventana/contexto.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        // Crear ventana.
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "OpenGL - AppCamara", 0, 0);
        if (window == 0) {
            throw new RuntimeException("No se pudo crear la ventana");
        }

        // ESC para cerrar.
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                GLFW.glfwSetWindowShouldClose(w, true);
            }
        });

        // Contexto actual + VSync + mostrar ventana.
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GLFW.glfwShowWindow(window);
        // Cargar funciones OpenGL.
        GL.createCapabilities();

        // Habilitar buffer de profundidad para render 3D correcto.
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        // Crear shaders y malla base (cubo).
        crearShaders();
        crearCubo();
    }

    /**
     * Crea shaders.
     * En vertex shader hacemos:
     * 1) Modelo (traslacion por cubo),
     * 2) View (restar posicion de camara + rotacion inversa),
     * 3) Proyeccion perspectiva.
     */
    private void crearShaders() {
        String vertexSrc = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aColor;

            uniform vec3 uModelOffset;
            uniform vec3 uCamPos;
            uniform vec2 uCamRot;   // x=pitch, y=yaw
            uniform float uAspect;

            out vec3 vColor;

            void main() {
                // Modelo: solo traslacion para colocar cada cubo en escena.
                vec3 worldPos = aPos + uModelOffset;

                // View: transformar desde mundo al espacio de camara.
                vec3 viewPos = worldPos - uCamPos;

                // Inversa de la rotacion de camara (yaw y pitch).
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

                // Proyeccion perspectiva.
                float fov = radians(60.0);
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

                vColor = aColor;
            }
            """;

        String fragmentSrc = """
            #version 330 core
            in vec3 vColor;
            out vec4 fragColor;
            void main() { fragColor = vec4(vColor, 1.0); }
            """;

        // Compilacion de vertex shader.
        int vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertexShader, vertexSrc);
        GL20.glCompileShader(vertexShader);
        comprobarShader(vertexShader, "Vertex");

        // Compilacion de fragment shader.
        int fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragmentShader, fragmentSrc);
        GL20.glCompileShader(fragmentShader);
        comprobarShader(fragmentShader, "Fragment");

        // Link de programa final.
        programa = GL20.glCreateProgram();
        GL20.glAttachShader(programa, vertexShader);
        GL20.glAttachShader(programa, fragmentShader);
        GL20.glLinkProgram(programa);

        if (GL20.glGetProgrami(programa, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Error al enlazar programa: " + GL20.glGetProgramInfoLog(programa));
        }

        // Resolver ubicaciones de uniforms.
        uModelOffsetLocation = GL20.glGetUniformLocation(programa, "uModelOffset");
        uCamPosLocation = GL20.glGetUniformLocation(programa, "uCamPos");
        uCamRotLocation = GL20.glGetUniformLocation(programa, "uCamRot");
        uAspectLocation = GL20.glGetUniformLocation(programa, "uAspect");
        if (uModelOffsetLocation == -1 || uCamPosLocation == -1 || uCamRotLocation == -1 || uAspectLocation == -1) {
            throw new RuntimeException("No se encontraron uniforms requeridos");
        }

        // Limpiar objetos shader intermedios.
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    // Reporta errores de compilacion GLSL.
    private void comprobarShader(int shader, String tipo) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException(tipo + " shader: " + GL20.glGetShaderInfoLog(shader));
        }
    }

    /**
     * Crea cubo unitario coloreado por cara.
     * Formato por vertice: posicion xyz + color rgb.
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

        // VAO guarda setup de atributos.
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // VBO guarda array de vertices en GPU.
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // Subida de datos a GPU.
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        // Atributo 0: posicion.
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Atributo 1: color.
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Desbind para no arrastrar estado.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Input de camara:
     * - W/S: avanzar/retroceder segun yaw actual,
     * - A/D: strafe izquierda/derecha,
     * - Q/E: bajar/subir,
     * - Flechas: yaw/pitch.
     */
    private void procesarInput(float deltaTime) {
        // Velocidad base en unidades/segundo.
        float velocidad = VELOCIDAD_CAMARA * deltaTime;
        // Sprint con SHIFT.
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS) {
            velocidad *= 2.0f;
        }
        // Velocidad angular.
        float velGiro = VELOCIDAD_GIRO * deltaTime;

        // Vectores direccionales en plano XZ segun yaw.
        float forwardX = (float) Math.sin(yaw);
        float forwardZ = (float) -Math.cos(yaw);
        float rightX = (float) Math.cos(yaw);
        float rightZ = (float) Math.sin(yaw);

        // Movimiento de camara.
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) {
            camX += forwardX * velocidad;
            camZ += forwardZ * velocidad;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) {
            camX -= forwardX * velocidad;
            camZ -= forwardZ * velocidad;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) {
            camX -= rightX * velocidad;
            camZ -= rightZ * velocidad;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) {
            camX += rightX * velocidad;
            camZ += rightZ * velocidad;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Q) == GLFW.GLFW_PRESS) {
            camY -= velocidad;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS) {
            camY += velocidad;
        }

        // Orientacion de camara.
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

        // Limita inclinacion vertical para evitar flips visuales.
        pitch = clamp(pitch, PITCH_MIN, PITCH_MAX);
    }

    // Utilidad de limite numerico.
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Bucle de render:
     * - actualiza input,
     * - limpia buffers,
     * - configura uniforms de camara,
     * - dibuja todos los cubos en distintas posiciones.
     */
    private void loop() {
        float ultimoTiempo = (float) GLFW.glfwGetTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            // Delta temporal para movimiento independiente de FPS.
            float tiempoActual = (float) GLFW.glfwGetTime();
            float deltaTime = tiempoActual - ultimoTiempo;
            ultimoTiempo = tiempoActual;

            procesarInput(deltaTime);

            GL11.glClearColor(0.08f, 0.08f, 0.12f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            GL20.glUseProgram(programa);
            GL20.glUniform3f(uCamPosLocation, camX, camY, camZ);
            GL20.glUniform2f(uCamRotLocation, pitch, yaw);
            GL20.glUniform1f(uAspectLocation, (float) ANCHO / (float) ALTO);
            GL30.glBindVertexArray(vao);

            // Se reutiliza el mismo cubo cambiando solo el offset por instancia.
            for (float[] cubo : CUBOS) {
                GL20.glUniform3f(uModelOffsetLocation, cubo[0], cubo[1], cubo[2]);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);
            }

            // Presenta frame y procesa eventos de ventana/input.
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    // Limpieza final de recursos nativos.
    private void cleanup() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL20.glDeleteProgram(programa);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    // Entry point.
    public static void main(String[] args) {
        new AppCamara().run();
    }
}
