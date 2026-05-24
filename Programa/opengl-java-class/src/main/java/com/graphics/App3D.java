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
 * App3D:
 * Ejemplo de cubo 3D en OpenGL moderno (pipeline programable) con:
 * - Traslacion en X/Y (WASD),
 * - Zoom real moviendo la escena en Z (+ y -),
 * - Rotacion en X/Y (flechas),
 * - Proyeccion perspectiva calculada en el vertex shader.
 *
 * La idea didactica es mostrar TODO el flujo minimo de una escena 3D:
 * 1) Crear ventana + contexto OpenGL (GLFW + LWJGL).
 * 2) Crear shaders (GPU) y buffers de vertices (VAO/VBO).
 * 3) Loop de render: input -> actualizar estado -> dibujar -> swap buffers.
 * 4) Liberar recursos al salir.
 */
public class App3D {

    // Handle/identificador de la ventana de GLFW.
    private long window;
    // ID del programa de shaders enlazado en GPU (vertex + fragment).
    private int programa;
    // VAO: encapsula configuracion de atributos de vertices.
    private int vao;
    // VBO: buffer con geometria (posiciones y colores del cubo).
    private int vbo;

    // Ubicaciones de uniforms en el shader.
    private int uOffsetLocation;
    private int uRotationLocation;
    private int uAspectLocation;

    // Resolucion inicial de la ventana.
    private static final int ANCHO = 800;
    private static final int ALTO = 600;

    // Movimiento en mundo 3D (X, Y).
    private float offsetX = 0.0f;
    private float offsetY = 0.0f;
    private static final float VELOCIDAD_MOV = 1.2f;
    private static final float LIMITE_MOV = 1.5f;

    // Zoom real 3D: acercar/alejar sobre eje Z (distancia de camara).
    private float offsetZ = -2.2f;
    private static final float ZOOM_CERCA = -1.2f;
    private static final float ZOOM_LEJOS = -6.0f;
    private static final float VELOCIDAD_ZOOM = 1.8f;

    // Rotacion en los tres ejes (radianes).
    private float rotX = 0.0f;
    private float rotY = 0.0f;
    private static final float ROT_Z = 0.0f;
    private static final float VELOCIDAD_ROT = 1.8f;

    /**
     * Metodo de alto nivel para ejecutar la app de principio a fin.
     * Separa claramente inicializacion, loop y limpieza.
     */
    public void run() {
        init();
        loop();
        cleanup();
    }

    /**
     * Inicializa sistema de ventana, contexto OpenGL y recursos de GPU.
     */
    private void init() {
        // 1) Inicializar GLFW (si falla, no hay ventana ni contexto).
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("No se pudo iniciar GLFW");
        }

        // 2) Configurar hints de la ventana/contexto.
        GLFW.glfwDefaultWindowHints();
        // Crear oculta al inicio y mostrar cuando todo este listo.
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        // Permitir redimensionar.
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        // Pedir OpenGL 3.3 Core Profile.
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        // Requisito habitual en macOS para core profile.
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        // 3) Crear ventana.
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "OpenGL - App3D", 0, 0);
        if (window == 0) {
            throw new RuntimeException("No se pudo crear la ventana");
        }

        // 4) Callback de teclado para cerrar con ESC.
        GLFW.glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
                GLFW.glfwSetWindowShouldClose(w, true);
            }
        });

        // 5) Zoom con rueda del mouse (modifica distancia en Z).
        GLFW.glfwSetScrollCallback(window, (w, xoffset, yoffset) -> {
            // yoffset positivo: acerca; negativo: aleja.
            offsetZ += (float) yoffset * 0.25f;
            // Evita atravesar la camara o alejarse demasiado.
            offsetZ = clamp(offsetZ, ZOOM_LEJOS, ZOOM_CERCA);
        });

        // 6) Activar contexto OpenGL en hilo actual.
        GLFW.glfwMakeContextCurrent(window);
        // 7) VSync (sincroniza FPS con monitor para evitar tearing).
        GLFW.glfwSwapInterval(1);
        // 8) Mostrar ventana.
        GLFW.glfwShowWindow(window);
        // 9) Cargar punteros de funciones OpenGL (LWJGL).
        GL.createCapabilities();
        // 10) Activar depth test para que lo cercano tape lo lejano.
        GL11.glEnable(GL11.GL_DEPTH_TEST);

        // 11) Crear pipeline y geometria.
        crearShaders();
        crearCubo();
    }

    /**
     * Crea y enlaza shaders:
     * - Vertex shader: aplica rotacion, traslacion y proyeccion perspectiva.
     * - Fragment shader: pinta color interpolado por vertice.
     */
    private void crearShaders() {
        String vertexSrc = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            layout (location = 1) in vec3 aColor;
            uniform vec3 uOffset;
            uniform vec3 uRotation;
            uniform float uAspect;
            out vec3 vColor;
            void main() {
                mat3 rotX = mat3(
                    1.0, 0.0, 0.0,
                    0.0, cos(uRotation.x), -sin(uRotation.x),
                    0.0, sin(uRotation.x),  cos(uRotation.x)
                );
                mat3 rotY = mat3(
                     cos(uRotation.y), 0.0, sin(uRotation.y),
                     0.0,              1.0, 0.0,
                    -sin(uRotation.y), 0.0, cos(uRotation.y)
                );
                mat3 rotZ = mat3(
                    cos(uRotation.z), -sin(uRotation.z), 0.0,
                    sin(uRotation.z),  cos(uRotation.z), 0.0,
                    0.0,               0.0,              1.0
                );

                vec3 worldPos = (rotZ * rotY * rotX) * aPos + uOffset;

                float fov = radians(60.0);
                float f = 1.0 / tan(fov * 0.5);
                float near = 0.1;
                float far = 100.0;

                vec4 clip;
                clip.x = worldPos.x * (f / uAspect);
                clip.y = worldPos.y * f;
                clip.z = ((far + near) / (near - far)) * worldPos.z
                       + ((2.0 * far * near) / (near - far));
                clip.w = -worldPos.z;
                gl_Position = clip;
                vColor = aColor;
            }
            """;

        // Fragment shader minimo: usa color recibido del vertex shader.
        String fragmentSrc = """
            #version 330 core
            in vec3 vColor;
            out vec4 fragColor;
            void main() { fragColor = vec4(vColor, 1.0); }
            """;

        // Crear y compilar vertex shader.
        int vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertexShader, vertexSrc);
        GL20.glCompileShader(vertexShader);
        comprobarShader(vertexShader, "Vertex");

        // Crear y compilar fragment shader.
        int fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragmentShader, fragmentSrc);
        GL20.glCompileShader(fragmentShader);
        comprobarShader(fragmentShader, "Fragment");

        // Crear programa y adjuntar ambos shaders.
        programa = GL20.glCreateProgram();
        GL20.glAttachShader(programa, vertexShader);
        GL20.glAttachShader(programa, fragmentShader);
        // Link: valida interfaz entre shaders y crea ejecutable final.
        GL20.glLinkProgram(programa);

        // Verificar link.
        if (GL20.glGetProgrami(programa, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Error al enlazar programa: " + GL20.glGetProgramInfoLog(programa));
        }

        // Obtener IDs de uniforms para escribirles desde CPU cada frame.
        uOffsetLocation = GL20.glGetUniformLocation(programa, "uOffset");
        uRotationLocation = GL20.glGetUniformLocation(programa, "uRotation");
        uAspectLocation = GL20.glGetUniformLocation(programa, "uAspect");
        if (uOffsetLocation == -1 || uRotationLocation == -1 || uAspectLocation == -1) {
            throw new RuntimeException("No se encontraron uniforms requeridos (uOffset/uRotation/uAspect)");
        }

        // Ya no se necesitan objetos shader sueltos despues del link.
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    /**
     * Verifica errores de compilacion GLSL y lanza excepcion con log.
     */
    private void comprobarShader(int shader, String tipo) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException(tipo + " shader: " + GL20.glGetShaderInfoLog(shader));
        }
    }

    /**
     * Construye un cubo con 36 vertices (6 caras * 2 triangulos * 3 vertices).
     * Cada vertice tiene 6 floats:
     * - posicion (x,y,z)
     * - color (r,g,b)
     */
    private void crearCubo() {
        float[] vertices = {
            // Cara frontal
            -0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
            -0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 0.0f,
            -0.5f, -0.5f,  0.5f,  1.0f, 0.0f, 0.0f,

            // Cara trasera
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
            -0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 0.0f,

            // Cara izquierda
            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, 0.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  0.0f, 0.0f, 1.0f,

            // Cara derecha
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f,  0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f, -0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 1.0f, 0.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 1.0f, 0.0f,

            // Cara superior
            -0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 1.0f,
            -0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f,  0.5f,  0.5f,  1.0f, 0.0f, 1.0f,
             0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 1.0f,
            -0.5f,  0.5f, -0.5f,  1.0f, 0.0f, 1.0f,

            // Cara inferior
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 1.0f,
             0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
             0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
            -0.5f, -0.5f,  0.5f,  0.0f, 1.0f, 1.0f,
            -0.5f, -0.5f, -0.5f,  0.0f, 1.0f, 1.0f
        };

        // Generar y bindear VAO (almacena configuracion de atributos).
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // Generar y bindear VBO (almacena datos de vertices).
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // Copiar datos Java -> FloatBuffer nativo -> GPU.
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        // Atributo 0: posicion (3 floats), stride = 6 floats.
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        // Atributo 1: color (3 floats), offset = 3 floats.
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 6 * Float.BYTES, 3L * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Buenas practicas: desbind.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Lee teclado y actualiza estado de movimiento, zoom y rotacion.
     * Usa deltaTime para que velocidad sea estable sin importar FPS.
     */
    private void procesarInput(float deltaTime) {
        // Distancia/angular por frame.
        float pasoMov = VELOCIDAD_MOV * deltaTime;
        float pasoZoom = VELOCIDAD_ZOOM * deltaTime;
        float pasoRot = VELOCIDAD_ROT * deltaTime;

        // Movimiento en plano (WASD).
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) {
            offsetX -= pasoMov;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) {
            offsetX += pasoMov;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) {
            offsetY += pasoMov;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) {
            offsetY -= pasoMov;
        }

        // Zoom real: mover Z con + y - (teclado normal y numpad).
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_ADD) == GLFW.GLFW_PRESS) {
            offsetZ += pasoZoom;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_KP_SUBTRACT) == GLFW.GLFW_PRESS) {
            offsetZ -= pasoZoom;
        }

        // Rotacion horizontal con flechas izquierda/derecha (Yaw).
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS) {
            rotY += pasoRot;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS) {
            rotY -= pasoRot;
        }

        // Rotacion vertical con flechas arriba/abajo (Pitch).
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS) {
            rotX += pasoRot;
        }
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS) {
            rotX -= pasoRot;
        }

        // Clamp para mantener valores en rangos utiles y evitar overflow visual.
        offsetX = clamp(offsetX, -LIMITE_MOV, LIMITE_MOV);
        offsetY = clamp(offsetY, -LIMITE_MOV, LIMITE_MOV);
        offsetZ = clamp(offsetZ, ZOOM_LEJOS, ZOOM_CERCA);
    }

    // Utilidad matematica: limita un valor al rango [min, max].
    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Bucle principal de render.
     * Orden: tiempo -> input -> clear -> uniforms -> draw -> swap/poll.
     */
    private void loop() {
        // Marca de tiempo del frame anterior para calcular deltaTime.
        float ultimoTiempo = (float) GLFW.glfwGetTime();

        while (!GLFW.glfwWindowShouldClose(window)) {
            // Tiempo actual y delta entre frames (en segundos).
            float tiempoActual = (float) GLFW.glfwGetTime();
            float deltaTime = tiempoActual - ultimoTiempo;
            ultimoTiempo = tiempoActual;

            // Actualiza posicion/rotacion/zoom segun teclas.
            procesarInput(deltaTime);

            // Limpia color y profundidad antes de dibujar frame nuevo.
            GL11.glClearColor(0.08f, 0.08f, 0.12f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

            // Activar programa.
            GL20.glUseProgram(programa);
            // Enviar uniforms de transformacion.
            GL20.glUniform3f(uOffsetLocation, offsetX, offsetY, offsetZ);
            GL20.glUniform3f(uRotationLocation, rotX, rotY, ROT_Z);
            // Aspect ratio fijo segun dimensiones iniciales de ventana.
            GL20.glUniform1f(uAspectLocation, (float) ANCHO / (float) ALTO);
            // Activar geometria del cubo.
            GL30.glBindVertexArray(vao);
            // Dibujar 36 vertices como triangulos.
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 36);

            // Presentar imagen y procesar eventos.
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    /**
     * Libera objetos de OpenGL y cierra ventana/sistema GLFW.
     */
    private void cleanup() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL20.glDeleteProgram(programa);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    // Punto de entrada Java.
    public static void main(String[] args) {
        new App3D().run();
    }
}
