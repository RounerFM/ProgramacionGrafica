package com.graphics;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * AppFlappyBird:
 * Mini-juego estilo Flappy Bird con OpenGL 2D (NDC directo, sin texturas).
 *
 * Estructura del juego:
 * - Jugador (pajaro) representado por un rectangulo.
 * - Obstaculos (tuberias) como rectangulos superior/inferior.
 * - Fisica basica: gravedad + impulso al saltar.
 * - Colision AABB simplificada.
 * - Puntuacion por cada tuberia superada.
 *
 * Nota didactica:
 * Para simplificar la clase, se usa un solo "quad base" (2 triangulos)
 * y se dibuja cualquier rectangulo con uniforms de offset/scale/color.
 */
public class AppFlappyBirdExample {

    // Tamano inicial de ventana.
    private static final int ANCHO = 900;
    private static final int ALTO = 700;

    // Posicion horizontal fija del pajaro en NDC.
    private static final float BIRD_X = -0.45f;
    // Tamano del pajaro.
    private static final float BIRD_ANCHO = 0.10f;
    private static final float BIRD_ALTO = 0.10f;
    // Fisica vertical.
    private static final float GRAVEDAD = -1.9f;
    private static final float IMPULSO_SALTO = 0.85f;
    private static final float VELOCIDAD_MAX_CAIDA = -1.8f;

    // Parametros de tuberias.
    private static final float TUBERIA_ANCHO = 0.18f;
    private static final float GAP_ALTO = 0.48f;
    private static final float VELOCIDAD_TUBERIAS = 0.62f;
    private static final float TIEMPO_ENTRE_TUBERIAS = 1.5f;
    private static final float GAP_MIN_CENTRO = -0.45f;
    private static final float GAP_MAX_CENTRO = 0.45f;

    // Recursos OpenGL basicos.
    private long window;
    private int programa;
    private int vao;
    private int vbo;
    // Uniforms de transformacion y color.
    private int uOffsetLocation;
    private int uScaleLocation;
    private int uColorLocation;

    // Estado del jugador/juego.
    private float birdY;
    private float birdVelY;
    private float timerSpawn;
    private int puntaje;

    private boolean started;
    private boolean gameOver;
    private boolean prevSpace;
    private boolean prevR;

    // Lista de obstaculos activos.
    private final List<Tuberia> tuberias = new ArrayList<>();
    // RNG para variar la posicion del gap.
    private final Random random = new Random();

    /**
     * Modelo de una tuberia:
     * x: posicion horizontal comun para parte superior/inferior,
     * gapCentroY: centro vertical del hueco,
     * puntuada: evita sumar dos veces la misma tuberia.
     */
    private static class Tuberia {
        float x;
        float gapCentroY;
        boolean puntuada;

        Tuberia(float x, float gapCentroY) {
            this.x = x;
            this.gapCentroY = gapCentroY;
        }
    }

    // Flujo principal de la aplicacion.
    public void run() {
        init();
        // Estado inicial listo para jugar.
        resetGame();
        loop();
        cleanup();
    }

    // Inicializa GLFW/OpenGL + shaders + geometria base.
    private void init() {
        // Arranque de GLFW.
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("No se pudo iniciar GLFW");
        }

        // Config de ventana/contexto.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        // Crear ventana.
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "Flappy Bird OpenGL", 0, 0);
        if (window == 0) {
            throw new RuntimeException("No se pudo crear la ventana");
        }

        // Contexto + VSync + mostrar.
        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GLFW.glfwShowWindow(window);
        // Cargar funciones OpenGL.
        GL.createCapabilities();

        // Crear pipeline y quad unitario reutilizable.
        crearShaders();
        crearQuadBase();
    }

    /**
     * Crea shaders 2D:
     * - Vertex: transforma quad base con escala y offset.
     * - Fragment: color uniforme.
     */
    private void crearShaders() {
        String vertexSrc = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            uniform vec2 uOffset;
            uniform vec2 uScale;
            void main() {
                vec2 finalPos = aPos.xy * uScale + uOffset;
                gl_Position = vec4(finalPos, aPos.z, 1.0);
            }
            """;

        // Color solido por objeto.
        String fragmentSrc = """
            #version 330 core
            uniform vec3 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(uColor, 1.0);
            }
            """;

        // Compilar vertex shader.
        int vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertexShader, vertexSrc);
        GL20.glCompileShader(vertexShader);
        comprobarShader(vertexShader, "Vertex");

        // Compilar fragment shader.
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

        // Resolver uniforms.
        uOffsetLocation = GL20.glGetUniformLocation(programa, "uOffset");
        uScaleLocation = GL20.glGetUniformLocation(programa, "uScale");
        uColorLocation = GL20.glGetUniformLocation(programa, "uColor");
        if (uOffsetLocation == -1 || uScaleLocation == -1 || uColorLocation == -1) {
            throw new RuntimeException("No se pudieron obtener uniforms del shader");
        }

        // Limpiar objetos shader temporales.
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    // Verificacion de compilacion GLSL.
    private void comprobarShader(int shader, String tipo) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException(tipo + " shader: " + GL20.glGetShaderInfoLog(shader));
        }
    }

    /**
     * Crea un rectangulo unitario centrado en origen:
     * - Rango x,y de -0.5 a +0.5.
     * - 2 triangulos (6 vertices).
     * Cualquier objeto 2D se dibuja escalando y moviendo este quad.
     */
    private void crearQuadBase() {
        float[] vertices = {
            -0.5f, -0.5f, 0.0f,
             0.5f, -0.5f, 0.0f,
             0.5f,  0.5f, 0.0f,
            -0.5f, -0.5f, 0.0f,
             0.5f,  0.5f, 0.0f,
            -0.5f,  0.5f, 0.0f
        };

        // VAO.
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // VBO.
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // Subida de vertices.
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        // Atributo posicion.
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        // Desbind.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Reinicia estado de partida.
     * Se usa al iniciar app y al reiniciar tras game over.
     */
    private void resetGame() {
        birdY = 0.0f;
        birdVelY = 0.0f;
        timerSpawn = 0.0f;
        puntaje = 0;
        started = false;
        gameOver = false;
        tuberias.clear();
        actualizarTitulo();
    }

    /**
     * Input del jugador:
     * - ESC: salir.
     * - SPACE: empezar/saltar.
     * - R: reset manual (solo en game over).
     *
     * Se usa deteccion de flanco (prevSpace/prevR) para no disparar
     * multiples acciones mientras tecla permanece presionada.
     */
    private void procesarInput() {
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
            GLFW.glfwSetWindowShouldClose(window, true);
        }

        boolean spaceAhora = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (spaceAhora && !prevSpace) {
            if (gameOver) {
                resetGame();
                started = true;
                birdVelY = IMPULSO_SALTO;
            } else {
                started = true;
                birdVelY = IMPULSO_SALTO;
            }
        }
        prevSpace = spaceAhora;

        boolean rAhora = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
        if (rAhora && !prevR && gameOver) {
            resetGame();
        }
        prevR = rAhora;
    }

    /**
     * Actualizacion de logica por frame (dt en segundos):
     * - fisica vertical,
     * - spawn y movimiento de tuberias,
     * - puntaje y colisiones.
     */
    private void actualizar(float dt) {
        // Si aun no inicio o ya termino, no avanza simulacion.
        if (!started || gameOver) {
            return;
        }

        // Integracion de fisica simple.
        birdVelY += GRAVEDAD * dt;
        // Limitar velocidad de caida para sensacion jugable estable.
        if (birdVelY < VELOCIDAD_MAX_CAIDA) {
            birdVelY = VELOCIDAD_MAX_CAIDA;
        }
        birdY += birdVelY * dt;

        // Colision contra techo/suelo NDC.
        float birdTop = birdY + (BIRD_ALTO * 0.5f);
        float birdBottom = birdY - (BIRD_ALTO * 0.5f);
        if (birdTop >= 1.0f || birdBottom <= -1.0f) {
            gameOver = true;
            actualizarTitulo();
            return;
        }

        // Temporizador para generar nuevas tuberias.
        timerSpawn += dt;
        if (timerSpawn >= TIEMPO_ENTRE_TUBERIAS) {
            timerSpawn = 0.0f;
            spawnTuberia();
        }

        Iterator<Tuberia> it = tuberias.iterator();
        while (it.hasNext()) {
            Tuberia t = it.next();
            // Avance horizontal de obstaculos (derecha -> izquierda).
            t.x -= VELOCIDAD_TUBERIAS * dt;

            // Puntuar cuando la tuberia ya quedo atras del pajaro.
            if (t.x + (TUBERIA_ANCHO * 0.5f) < BIRD_X && !t.puntuada) {
                t.puntuada = true;
                puntaje++;
                actualizarTitulo();
            }

            if (colisionaConTuberia(t)) {
                gameOver = true;
                actualizarTitulo();
                return;
            }

            // Remover tuberias fuera de pantalla para no acumular memoria.
            if (t.x + (TUBERIA_ANCHO * 0.5f) < -1.3f) {
                it.remove();
            }
        }
    }

    // Crea tuberia nueva en borde derecho con gap vertical aleatorio.
    private void spawnTuberia() {
        float gapCentro = GAP_MIN_CENTRO + random.nextFloat() * (GAP_MAX_CENTRO - GAP_MIN_CENTRO);
        tuberias.add(new Tuberia(1.2f, gapCentro));
    }

    /**
     * Colision AABB simplificada:
     * 1) Si no hay overlap horizontal, no colisiona.
     * 2) Si hay overlap horizontal, colisiona si el pajaro esta fuera del gap.
     */
    private boolean colisionaConTuberia(Tuberia t) {
        float birdLeft = BIRD_X - (BIRD_ANCHO * 0.5f);
        float birdRight = BIRD_X + (BIRD_ANCHO * 0.5f);
        float birdBottom = birdY - (BIRD_ALTO * 0.5f);
        float birdTop = birdY + (BIRD_ALTO * 0.5f);

        float pipeLeft = t.x - (TUBERIA_ANCHO * 0.5f);
        float pipeRight = t.x + (TUBERIA_ANCHO * 0.5f);
        boolean overlapX = birdRight > pipeLeft && birdLeft < pipeRight;
        if (!overlapX) {
            return false;
        }

        float gapTop = t.gapCentroY + (GAP_ALTO * 0.5f);
        float gapBottom = t.gapCentroY - (GAP_ALTO * 0.5f);
        return birdTop > gapTop || birdBottom < gapBottom;
    }

    /**
     * Render del frame:
     * - fondo,
     * - tuberias,
     * - pajaro,
     * - franja central en game over.
     */
    private void render() {
        // Cielo.
        GL11.glClearColor(0.52f, 0.80f, 0.92f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        // Activar pipeline y malla base.
        GL20.glUseProgram(programa);
        GL30.glBindVertexArray(vao);

        for (Tuberia t : tuberias) {
            // Calcular limites verticales del hueco.
            float gapTop = t.gapCentroY + (GAP_ALTO * 0.5f);
            float gapBottom = t.gapCentroY - (GAP_ALTO * 0.5f);

            // Tramo superior de tuberia.
            float altoSuperior = 1.0f - gapTop;
            if (altoSuperior > 0.0f) {
                float yCentroSup = gapTop + (altoSuperior * 0.5f);
                dibujarRect(t.x, yCentroSup, TUBERIA_ANCHO, altoSuperior, 0.18f, 0.70f, 0.25f);
            }

            // Tramo inferior de tuberia.
            float altoInferior = gapBottom + 1.0f;
            if (altoInferior > 0.0f) {
                float yCentroInf = -1.0f + (altoInferior * 0.5f);
                dibujarRect(t.x, yCentroInf, TUBERIA_ANCHO, altoInferior, 0.18f, 0.70f, 0.25f);
            }
        }

        // Dibujar pajaro.
        dibujarRect(BIRD_X, birdY, BIRD_ANCHO, BIRD_ALTO, 0.98f, 0.85f, 0.20f);

        // Overlay simple de game over (sin texto en framebuffer).
        if (gameOver) {
            dibujarRect(0.0f, 0.0f, 2.0f, 0.22f, 0.15f, 0.18f, 0.22f);
        }
    }

    // Helper de dibujo parametrico de rectangulos.
    private void dibujarRect(float x, float y, float ancho, float alto, float r, float g, float b) {
        // Traslacion del quad.
        GL20.glUniform2f(uOffsetLocation, x, y);
        // Escala del quad.
        GL20.glUniform2f(uScaleLocation, ancho, alto);
        // Color.
        GL20.glUniform3f(uColorLocation, r, g, b);
        // Dibujar 2 triangulos.
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
    }

    // Actualiza feedback visual en barra de titulo.
    private void actualizarTitulo() {
        String tituloBase = "Flappy Bird OpenGL | Puntos: " + puntaje;
        if (!started) {
            GLFW.glfwSetWindowTitle(window, tituloBase + " | SPACE para empezar");
        } else if (gameOver) {
            GLFW.glfwSetWindowTitle(window, tituloBase + " | GAME OVER - SPACE o R para reiniciar");
        } else {
            GLFW.glfwSetWindowTitle(window, tituloBase);
        }
    }

    /**
     * Bucle principal:
     * - calcula dt,
     * - procesa input,
     * - actualiza logica,
     * - renderiza,
     * - swap/poll.
     */
    private void loop() {
        float ultimoTiempo = (float) GLFW.glfwGetTime();
        while (!GLFW.glfwWindowShouldClose(window)) {
            float ahora = (float) GLFW.glfwGetTime();
            float dt = ahora - ultimoTiempo;
            ultimoTiempo = ahora;
            // Limite de dt para evitar "saltos" grandes si el frame se congela.
            if (dt > 0.033f) {
                dt = 0.033f;
            }

            procesarInput();
            actualizar(dt);
            render();

            // Presentar frame y leer eventos.
            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    // Liberacion de recursos.
    private void cleanup() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL20.glDeleteProgram(programa);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    // Entry point.
    public static void main(String[] args) {
        new AppFlappyBirdExample().run();
    }

    
}
