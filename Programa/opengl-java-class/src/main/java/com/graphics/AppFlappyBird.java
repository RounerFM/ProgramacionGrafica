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
public class AppFlappyBird {

    private static final int ANCHO = 900;
    private static final int ALTO = 700;

    // Configuración física base
    private static final float BIRD_X1 = -0.50f;
    private static final float BIRD_X2 = -0.30f;
    private static final float BIRD_X3 = -0.10f;
    
    private static final float BIRD_ANCHO = 0.10f;
    private static final float BIRD_ALTO = 0.10f;
    
    private static float GRAVEDAD = -2.1f;
    private static float IMPULSO_SALTO = 0.75f;
    private static float VELOCIDAD_MAX_CAIDA = -1.8f;

    // Parámetros dinámicos base de tuberías
    private static final float TUBERIA_ANCHO = 0.18f;
    private static final float GAP_ALTO = 0.46f;
    private static final float VELOCIDAD_TUBERIAS_BASE = 0.60f;
    private static final float TIEMPO_ENTRE_TUBERIAS_BASE = 1.6f;
    private static final float GAP_MIN_CENTRO = -0.40f;
    private static final float GAP_MAX_CENTRO = 0.40f;
    private static final float SUELO_Y = -0.85f;

    // Recursos OpenGL basicos.
    private long window;
    private int programa;
    private int vaoQuad, vboQuad;
    private int vaoTriangulo, vboTriangulo;
    private int vaoCirculo, vboCirculo;
    private final int PUNTOS_CIRCULO = 20;

    // Uniforms de Shaders (Soporta posición, escala, color y rotación)
    private int uOffsetLocation;
    private int uScaleLocation;
    private int uColorLocation;
    private int uRotacionLocation; 

    // Estados de los Jugadores
    private class Jugador {
        float y = 0.0f;
        float velY = 0.0f;
        boolean vivo = true;
        int puntaje = 0;
        float anguloRotacion = 0.0f;
        float alaAnimTimer = 0.0f;
    }

    private Jugador j1 = new Jugador();
    private Jugador j2 = new Jugador();
    private Jugador j3 = new Jugador();

    // Variables de control del entorno global
    private float velocidadActualTuberias = VELOCIDAD_MAX_CAIDA;
    private float tiempoActualSpawn = TIEMPO_ENTRE_TUBERIAS_BASE;
    private float timerSpawn;
    private int nivelDificultad = 1;

    private boolean started;
    private boolean gameOver;
    private boolean prevSpace, prevW, prevR, prevP;

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
        boolean puntuadaJ1;
        boolean puntuadaJ2;
        boolean puntuadaJ3;

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
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "Flappy Bird OpenGL Co-Op", 0, 0);
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
        generarGeometrias();
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
            uniform float uRotacion;
            void main() {
                // Aplicar matriz de rotación básica 2D en el Vertex Shader
                float cosR = cos(uRotacion);
                float sinR = sin(uRotacion);
                vec2 posRotada = vec2(
                    aPos.x * cosR - aPos.y * sinR,
                    aPos.x * sinR + aPos.y * cosR
                );
                vec2 finalPos = posRotada * uScale + uOffset;
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
        uRotacionLocation = GL20.glGetUniformLocation(programa, "uRotacion");
        if (uOffsetLocation == -1 || uScaleLocation == -1 || uColorLocation == -1) {
            throw new RuntimeException("No se pudieron obtener uniforms del shader");
        }

        // Limpiar objetos shader temporales.
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    private void comprobarShader(int shader, String tipo) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException(tipo + " shader: " + GL20.glGetShaderInfoLog(shader));
        }
    }

    private void generarGeometrias() {
        // 1. Quad Base (Rectángulos)
        float[] verticesQuad = {
            -0.5f, -0.5f, 0.0f,  
            0.5f, -0.5f, 0.0f,  
            0.5f,  0.5f, 0.0f,
            -0.5f, -0.5f, 0.0f,  
            0.5f,  0.5f, 0.0f, 
            -0.5f,  0.5f, 0.0f
        };
        vaoQuad = GL30.glGenVertexArrays();
        vboQuad = GL15.glGenBuffers();
        vincBuffer(vaoQuad, vboQuad, verticesQuad);

        // 2. Triángulo (Picos, Colas)
        float[] verticesTri = {
            -0.5f, -0.5f, 0.0f,  0.5f, -0.5f, 0.0f,  0.0f,  0.5f, 0.0f
        };
        vaoTriangulo = GL30.glGenVertexArrays();
        vboTriangulo = GL15.glGenBuffers();
        vincBuffer(vaoTriangulo, vboTriangulo, verticesTri);

        // 3. Círculo aproximado vía Triangle Fan (Cuerpos, Ojos)
        float[] verticesCir = new float[(PUNTOS_CIRCULO + 2) * 3];
        verticesCir[0] = 0.0f; verticesCir[1] = 0.0f; verticesCir[2] = 0.0f; // Centro
        for (int i = 0; i <= PUNTOS_CIRCULO; i++) {
            double angulo = i * 2.0 * Math.PI / PUNTOS_CIRCULO;
            verticesCir[(i + 1) * 3] = (float) Math.cos(angulo) * 0.5f;
            verticesCir[(i + 1) * 3 + 1] = (float) Math.sin(angulo) * 0.5f;
            verticesCir[(i + 1) * 3 + 2] = 0.0f;
        }
        vaoCirculo = GL30.glGenVertexArrays();
        vboCirculo = GL15.glGenBuffers();
        vincBuffer(vaoCirculo, vboCirculo, verticesCir);
    }

    private void vincBuffer(int vao, int vbo, float[] vertices) {
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    private void resetGame() {
        j1 = new Jugador();
        j2 = new Jugador();
        j3 = new Jugador();
        
        j1.y = 0.15f;  
        j2.y = -0.15f;
        j3.y = 0.0f;

        velocidadActualTuberias = VELOCIDAD_TUBERIAS_BASE;
        tiempoActualSpawn = TIEMPO_ENTRE_TUBERIAS_BASE;
        timerSpawn = 0.0f;
        nivelDificultad = 1;
        
        started = false;
        gameOver = false;
        tuberias.clear();
        actualizarTitulo();
    }

    private void procesarInput() {
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS) {
            GLFW.glfwSetWindowShouldClose(window, true);
        }

        // CONTROLES JUGADOR 1 (ESPACIO)
        boolean spaceAhora = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (spaceAhora && !prevSpace) {
            if (gameOver) {
                resetGame();
            } else if (j1.vivo) {
                started = true;
                j1.velY = IMPULSO_SALTO;
            }
        }
        prevSpace = spaceAhora;

        // CONTROLES JUGADOR 2 (W)
        boolean wAhora = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS;
        if (wAhora && !prevW && !gameOver) {
            if (j2.vivo) {
                started = true;
                j2.velY = IMPULSO_SALTO;
            }
        }
        prevW = wAhora;

        // CONTROLES JUGADOR 3 (P)
        boolean pAhora = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
        if (pAhora && !prevP && !gameOver) {
            if (j3.vivo) {
                started = true;
                j3.velY = IMPULSO_SALTO;
            }
        }
        prevP = pAhora;

        // RESET MANUAL CON R
        boolean rAhora = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
        if (rAhora && !prevR && gameOver) {
            resetGame();
        }
        prevR = rAhora;
    }

    private void actualizar(float dt) {
        if (!started || gameOver) return;

        // Actualizar física e inclinación del Jugador 1
        if (j1.vivo) {
            j1.velY += GRAVEDAD * dt;
            //if (j1.velY < VELOCIDAD_MAX_CAIDA) j1.velY = VELOCIDAD_MAX_CAIDA;
            if (GRAVEDAD < 0) {
                if (j1.velY < VELOCIDAD_MAX_CAIDA) j1.velY = VELOCIDAD_MAX_CAIDA; // Normal
            } else {
                if (j1.velY > VELOCIDAD_MAX_CAIDA) j1.velY = VELOCIDAD_MAX_CAIDA; // Invertido
            }
            j1.y += j1.velY * dt;
            j1.anguloRotacion = j1.velY * 0.6f; 
            j1.alaAnimTimer += dt * 15.0f;

            // Colisiones Suelo / Techo J1
            if (j1.y + (BIRD_ALTO * 0.4f) >= 1.0f || j1.y - (BIRD_ALTO * 0.4f) <= SUELO_Y) {
                j1.vivo = false;
            }
        }

        // Actualizar física e inclinación del Jugador 2
        if (j2.vivo) {
            j2.velY += GRAVEDAD * dt;
            //if (j2.velY < VELOCIDAD_MAX_CAIDA) j2.velY = VELOCIDAD_MAX_CAIDA;
            if (GRAVEDAD < 0) {
                if (j2.velY < VELOCIDAD_MAX_CAIDA) j2.velY = VELOCIDAD_MAX_CAIDA; // Normal
            } else {
                if (j2.velY > VELOCIDAD_MAX_CAIDA) j2.velY = VELOCIDAD_MAX_CAIDA; // Invertido
            }
            j2.y += j2.velY * dt;
            j2.anguloRotacion = j2.velY * 0.6f;
            j2.alaAnimTimer += dt * 15.0f;

            // Colisiones Suelo / Techo J2
            if (j2.y + (BIRD_ALTO * 0.4f) >= 1.0f || j2.y - (BIRD_ALTO * 0.4f) <= SUELO_Y) {
                j2.vivo = false;
            }
        }

        // Actualizar física e inclinación del Jugador 3
        if (j3.vivo) {
            j3.velY += GRAVEDAD * dt;
            //if (j3.velY < VELOCIDAD_MAX_CAIDA) j3.velY = VELOCIDAD_MAX_CAIDA;
            if (GRAVEDAD < 0) {
                if (j3.velY < VELOCIDAD_MAX_CAIDA) j3.velY = VELOCIDAD_MAX_CAIDA; // Normal
            } else {
                if (j3.velY > VELOCIDAD_MAX_CAIDA) j3.velY = VELOCIDAD_MAX_CAIDA; // Invertido
            }
            j3.y += j3.velY * dt;
            j3.anguloRotacion = j3.velY * 0.6f;
            j3.alaAnimTimer += dt * 15.0f;

            // Colisiones Suelo / Techo J2
            if (j3.y + (BIRD_ALTO * 0.4f) >= 1.0f || j3.y - (BIRD_ALTO * 0.4f) <= SUELO_Y) {
                j3.vivo = false;
            }
        }

        // Condición de Fin de juego: Ambos deben estar muertos
        if (!j1.vivo && !j2.vivo && !j3.vivo 
            
        ) {
            gameOver = true;
            actualizarTitulo();
            return;
        }

        // Sistema de Dificultad Dinámica Progresiva
        int maxPuntaje = Math.max(j1.puntaje, Math.max(j2.puntaje, j3.puntaje));
        // Sube de nivel cada 3 puntos
        nivelDificultad = 1 + (maxPuntaje / 3); 
        // CAMBIO DINÁMICO DE GRAVEDAD (A partir de nivel 3)
        if (nivelDificultad >= 2) {
            GRAVEDAD = 2.1f;             
            IMPULSO_SALTO = -0.75f;       
            VELOCIDAD_MAX_CAIDA = 1.8f;  
        } else {
            GRAVEDAD = -2.1f;           
            IMPULSO_SALTO = 0.75f;     
            VELOCIDAD_MAX_CAIDA = -1.8f;  
        }
        velocidadActualTuberias = VELOCIDAD_TUBERIAS_BASE + (nivelDificultad - 1) * 0.12f;
        tiempoActualSpawn = Math.max(0.9f, TIEMPO_ENTRE_TUBERIAS_BASE - (nivelDificultad - 1) * 0.15f);

        // Generar Obstáculos
        timerSpawn += dt;
        if (timerSpawn >= tiempoActualSpawn) {
            timerSpawn = 0.0f;
            spawnTuberia();
        }

        Iterator<Tuberia> it = tuberias.iterator();
        while (it.hasNext()) {
            Tuberia t = it.next();
            t.x -= velocidadActualTuberias * dt;

            // Gestionar puntuación independiente
            if (t.x < BIRD_X1 && !t.puntuadaJ1 && j1.vivo) {
                t.puntuadaJ1 = true;
                j1.puntaje++;
                actualizarTitulo();
            }
            if (t.x < BIRD_X2 && !t.puntuadaJ2 && j2.vivo) {
                t.puntuadaJ2 = true;
                j2.puntaje++;
                actualizarTitulo();
            }
            if (t.x < BIRD_X3 && !t.puntuadaJ3 && j3.vivo) {
                t.puntuadaJ3 = true;
                j3.puntaje++;
                actualizarTitulo();
            }

            // Validar colisiones con estructuras físicas de tuberías
            if (j1.vivo && colisionaPajaro(BIRD_X1, j1.y, t)) j1.vivo = false;
            if (j2.vivo && colisionaPajaro(BIRD_X2, j2.y, t)) j2.vivo = false;
            if (j3.vivo && colisionaPajaro(BIRD_X3, j3.y, t)) j3.vivo = false;

            if (t.x + (TUBERIA_ANCHO * 0.5f) < -1.3f) {
                it.remove();
            }
        }
    }

    private void spawnTuberia() {
        float gapCentro = GAP_MIN_CENTRO + random.nextFloat() * (GAP_MAX_CENTRO - GAP_MIN_CENTRO);
        tuberias.add(new Tuberia(1.2f, gapCentro));
    }

    private boolean colisionaPajaro(float bX, float bY, Tuberia t) {
        float birdLeft = bX - (BIRD_ANCHO * 0.4f);
        float birdRight = bX + (BIRD_ANCHO * 0.4f);
        float birdBottom = bY - (BIRD_ALTO * 0.4f);
        float birdTop = bY + (BIRD_ALTO * 0.4f);

        float pipeLeft = t.x - (TUBERIA_ANCHO * 0.5f);
        float pipeRight = t.x + (TUBERIA_ANCHO * 0.5f);

        if (!(birdRight > pipeLeft && birdLeft < pipeRight)) return false;

        float gapTop = t.gapCentroY + (GAP_ALTO * 0.5f);
        float gapBottom = t.gapCentroY - (GAP_ALTO * 0.5f);

        return birdTop > gapTop || birdBottom < gapBottom;
    }

    private void render() {
        // Fondo dinámico: El cielo cambia de tonalidad según el nivel alcanzado
        float rFondo = Math.max(0.2f, 0.52f - (nivelDificultad * 0.05f));
        float gFondo = Math.max(0.4f, 0.80f - (nivelDificultad * 0.05f));
        float bFondo = Math.min(1.0f, 0.92f + (nivelDificultad * 0.01f));
        GL11.glClearColor(rFondo, gFondo, bFondo, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

        GL20.glUseProgram(programa);

        // 1. Dibujar Tuberías Estilizadas (Con bordes oscuros simulados)
        for (Tuberia t : tuberias) {
            float gapTop = t.gapCentroY + (GAP_ALTO * 0.5f);
            float gapBottom = t.gapCentroY - (GAP_ALTO * 0.5f);

            // Tubo Superior
            float altoSuperior = 1.0f - gapTop;
            if (altoSuperior > 0.0f) {
                float yCentroSup = gapTop + (altoSuperior * 0.5f);
                // Cuerpo Verde
                dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, t.x, yCentroSup, TUBERIA_ANCHO, altoSuperior, 0.10f, 0.65f, 0.20f, 0);
                // Borde Estético
                dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, t.x, gapTop + 0.02f, TUBERIA_ANCHO + 0.02f, 0.05f, 0.05f, 0.25f, 0.08f, 0);
            }

            // Tubo Inferior
            float altoInferior = gapBottom - SUELO_Y;
            if (altoInferior > 0.0f) {
                float yCentroInf = SUELO_Y + (altoInferior * 0.5f);
                // Cuerpo Verde
                dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, t.x, yCentroInf, TUBERIA_ANCHO, altoInferior, 0.10f, 0.65f, 0.20f, 0);
                // Borde Estético
                dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, t.x, gapBottom - 0.02f, TUBERIA_ANCHO + 0.02f, 0.05f, 0.05f, 0.25f, 0.08f, 0);
            }
        }

        // 2. RENDERIZADO DEL SUELO DEL ESCENARIO
        dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, 0.0f, SUELO_Y - 0.15f, 2.0f, 0.3f, 0.70f, 0.55f, 0.35f, 0);
        dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, 0.0f, SUELO_Y, 2.0f, 0.03f, 0.15f, 0.50f, 0.15f, 0); // Pasto

        // 3. Dibujar Pájaro Co-Op Compuesto (Jugador 1: Amarillo, Jugador 2: Azul)
        if (j1.vivo) dibujarPajaroCompuesto(BIRD_X1, j1.y, j1.anguloRotacion, j1.alaAnimTimer, 0.98f, 0.82f, 0.10f);
        if (j2.vivo) dibujarPajaroCompuesto(BIRD_X2, j2.y, j2.anguloRotacion, j2.alaAnimTimer, 0.20f, 0.60f, 0.98f);
        if (j3.vivo) dibujarPajaroCompuesto(BIRD_X3, j3.y, j3.anguloRotacion, j3.alaAnimTimer, 0.40f, 0.60f, 0.98f);

        // Overlay de Fin de juego
        if (gameOver) {
            dibujar(vaoQuad, GL11.GL_TRIANGLES, 6, 0.0f, 0.0f, 2.0f, 0.30f, 0.12f, 0.12f, 0.15f, 0);
        }
    }

    /**
     * Ensamblador anatómico del Personaje Compuesto (Múltiples primitivas OpenGL)
     */
    private void dibujarPajaroCompuesto(float x, float y, float rot, float alaTimer, float r, float g, float b) {
        // Matriz base escalada al tamaño final del viewport
        float sX = BIRD_ANCHO;
        float sY = BIRD_ALTO;

        // 1. COLA (Triángulo izquierdo)
        dibujar(vaoTriangulo, GL11.GL_TRIANGLES, 3, x - (sX * 0.45f), y, sX * 0.5f, sY * 0.5f, r * 0.8f, g * 0.8f, b * 0.8f, rot + (float)Math.PI/2);

        // 2. CUERPO PRINCIPAL (Círculo aproximado)
        dibujar(vaoCirculo, GL11.GL_TRIANGLE_FAN, PUNTOS_CIRCULO + 2, x, y, sX, sY, r, g, b, rot);

        // 3. OJO (Círculo Blanco + Pupila Negra)
        float ojoXOffset = (float) Math.cos(rot) * (sX * 0.20f) - (float) Math.sin(rot) * (sY * 0.20f);
        float ojoYOffset = (float) Math.sin(rot) * (sX * 0.20f) + (float) Math.cos(rot) * (sY * 0.20f);
        dibujar(vaoCirculo, GL11.GL_TRIANGLE_FAN, PUNTOS_CIRCULO + 2, x + ojoXOffset, y + ojoYOffset, sX * 0.35f, sY * 0.35f, 1.0f, 1.0f, 1.0f, rot);
        
        float pupilaX = x + ojoXOffset + (float) Math.cos(rot) * (sX * 0.05f);
        float pupilaY = y + ojoYOffset + (float) Math.sin(rot) * (sY * 0.05f);
        dibujar(vaoCirculo, GL11.GL_TRIANGLE_FAN, PUNTOS_CIRCULO + 2, pupilaX, pupilaY, sX * 0.15f, sY * 0.15f, 0.05f, 0.05f, 0.05f, rot);

        // 4. PICO (Triángulo Naranja a la derecha)
        float picoX = x + (float) Math.cos(rot) * (sX * 0.45f);
        float picoY = y + (float) Math.sin(rot) * (sY * 0.45f);
        dibujar(vaoTriangulo, GL11.GL_TRIANGLES, 3, picoX, picoY, sX * 0.4f, sY * 0.3f, 0.95f, 0.45f, 0.05f, rot - (float)Math.PI/2);

        // 5. ALA ANIMADA (Oscilación senoidal simulando aleteo mecánico)
        float factorAleteo = (float) Math.sin(alaTimer) * 0.35f;
        dibujar(vaoCirculo, GL11.GL_TRIANGLE_FAN, PUNTOS_CIRCULO + 2, x - (sX * 0.10f), y - (sY * 0.05f), sX * 0.45f, sY * (0.3f + factorAleteo), r * 0.9f, g * 0.9f, b * 0.3f, rot);
    }

    private void dibujar(int vao, int modo, int cuentaVertices, float x, float y, float ancho, float alto, float r, float g, float b, float rotacion) {
        GL30.glBindVertexArray(vao);
        GL20.glUniform2f(uOffsetLocation, x, y);
        GL20.glUniform2f(uScaleLocation, ancho, alto);
        GL20.glUniform3f(uColorLocation, r, g, b);
        GL20.glUniform1f(uRotacionLocation, rotacion); 
        GL11.glDrawArrays(modo, 0, cuentaVertices);
    }

    private void actualizarTitulo() {
        String base = "Flappy Bird Co-Op | NIVEL: " + nivelDificultad + " | J1 (Espacio): " + j1.puntaje + " pts" + (j1.vivo ? "" : " [X]") + " | J2 (W): " + j2.puntaje + " pts" + (j2.vivo ? "" : " [X]")+ " | J3 (Arriba): " + j3.puntaje + " pts" + (j3.vivo ? "" : " [X]");
        if (!started) {
            GLFW.glfwSetWindowTitle(window, base + " | ¡PRESIONA ESPACIO O W PARA EMPEZAR!");
        } else if (gameOver) {
            GLFW.glfwSetWindowTitle(window, base + " | ¡GAME OVER! Presiona SPACE o R para reiniciar");
        } else {
            GLFW.glfwSetWindowTitle(window, base);
        }
    }

    private void loop() {
        float ultimoTiempo = (float) GLFW.glfwGetTime();
        while (!GLFW.glfwWindowShouldClose(window)) {
            float ahora = (float) GLFW.glfwGetTime();
            float dt = ahora - ultimoTiempo;
            ultimoTiempo = ahora;
            if (dt > 0.033f) dt = 0.033f;

            procesarInput();
            actualizar(dt);
            render();

            GLFW.glfwSwapBuffers(window);
            GLFW.glfwPollEvents();
        }
    }

    private void cleanup() {
        GL30.glDeleteVertexArrays(vaoQuad);
        GL15.glDeleteBuffers(vboQuad);
        GL30.glDeleteVertexArrays(vaoTriangulo);
        GL15.glDeleteBuffers(vboTriangulo);
        GL30.glDeleteVertexArrays(vaoCirculo);
        GL15.glDeleteBuffers(vboCirculo);
        
        GL20.glDeleteProgram(programa);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    public static void main(String[] args) {
        new AppFlappyBird().run();
    }
}