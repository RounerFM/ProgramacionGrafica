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
 * =============================================================================
 * EJEMPLO MÍNIMO DE OPENGL (para quien no sabe nada de gráficos 3D)
 * =============================================================================
 *
 * Este programa hace 3 cosas:
 *   1. Abre una ventana (usando GLFW, una biblioteca que crea ventanas).
 *   2. Define un triángulo y unos "shaders" (programas que corren en la GPU).
 *   3. En un bucle, dibuja el triángulo en la ventana una y otra vez.
 *
 * CONCEPTOS CLAVE:
 *   - OpenGL: conjunto de funciones para dibujar en la pantalla usando la GPU.
 *   - GLFW: biblioteca que crea la ventana y maneja teclado/ratón. OpenGL solo dibuja;
 *     no crea ventanas, por eso usamos GLFW.
 *   - GPU: tarjeta gráfica. Los "shaders" son pequeños programas que se ejecutan ahí.
 *   - Shader: código que dice cómo transformar vértices (vertex shader) y qué color
 *     dar a cada píxel (fragment shader). Se escriben en un lenguaje parecido a C (GLSL).
 *   - Vértice: un punto en 3D (x, y, z). Un triángulo tiene 3 vértices.
 *   - VAO/VBO: formas de guardar en la GPU los datos de los vértices (posiciones, etc.).
 */
public class App {

    // Referencia a nuestra ventana. GLFW devuelve un "handle" (número largo) para identificarla.
    private long window;

    // Nuestro "programa" de shaders: es el vertex shader + fragment shader unidos.
    // La GPU usa este programa para dibujar cada frame.
    private int programa;

    // VAO = Vertex Array Object. Es como un "dossier" que guarda toda la configuración de
    // un objeto (qué buffer usar, cómo leer los datos). Así luego solo decimos "usa este VAO"
    // y OpenGL ya sabe cómo dibujar el triángulo.
    private int vao;

    // VBO = Vertex Buffer Object. Es un bloque de memoria en la GPU donde guardamos
    // las posiciones de los 3 vértices del triángulo (las coordenadas x, y, z de cada uno).
    private int vbo;

    // Tamaño de la ventana en píxeles (ancho x alto).
    private static final int ANCHO = 800;
    private static final int ALTO = 600;

    /**
     * Punto de entrada: inicializar todo, ejecutar el bucle de dibujo, y al salir limpiar.
     */
    public void run() {
        init();    // Crear ventana, cargar OpenGL, crear shaders y triángulo
        loop();    // Bucle que dibuja frame a frame hasta que cierres la ventana
        cleanup(); // Liberar memoria y cerrar GLFW
    }

    // =========================================================================
    // PASO 1: INICIALIZAR GLFW Y CREAR LA VENTANA
    // =========================================================================
    // GLFW (Graphics Library Framework) es una biblioteca que:
    //   - Crea y gestiona la ventana del sistema operativo.
    //   - Recibe eventos de teclado, ratón y ventana (cerrar, redimensionar).
    // OpenGL por sí solo NO crea ventanas; solo dibuja en un "contexto". Por eso
    // primero creamos la ventana con GLFW y luego le "enganchamos" OpenGL.
    private void init() {
        // Iniciar GLFW. Sin esto no podemos crear ventanas.
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("No se pudo iniciar GLFW");
        }

        // --- "Hints" = opciones que le damos a GLFW para la ventana ---
        // Restablecer todas las opciones por defecto.
        GLFW.glfwDefaultWindowHints();
        // Ocultar la ventana al principio; la mostraremos cuando OpenGL esté listo.
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        // Permitir que el usuario pueda redimensionar la ventana.
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        // Queremos OpenGL 3.3 (versión 3, revisión 3). Nuestros shaders usan "#version 330".
        // En Mac es obligatorio pedir una versión concreta.
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        // "Core profile": solo funciones modernas de OpenGL (sin compatibilidad con código muy antiguo).
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        // En macOS hay que activar "forward compatibility" para usar el Core profile.
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);

        // Crear la ventana: ancho, alto, título, monitor (0 = ventana normal), ventana para compartir (0 = ninguna).
        // Devuelve un identificador (long) que usamos en el resto de llamadas a GLFW.
        window = GLFW.glfwCreateWindow(ANCHO, ALTO, "OpenGL - Triángulo", 0, 0);
        if (window == 0) {
            throw new RuntimeException("No se pudo crear la ventana");
        }

        // Decir que todas las órdenes de OpenGL que ejecutemos a partir de ahora
        // se apliquen a ESTA ventana (por si en el futuro hubiera varias).
        GLFW.glfwMakeContextCurrent(window);
        // Sincronizar el dibujo con el monitor (1 = esperar al siguiente refresco).
        // Así evitamos "tearing" (imagen partida) y no dibujamos más frames de los que el monitor puede mostrar.
        GLFW.glfwSwapInterval(1);
        // Hacer la ventana visible.
        GLFW.glfwShowWindow(window);

        // Cargar en Java las funciones de OpenGL según la versión que pedimos (3.3).
        // LWJGL necesita este paso para enlazar las llamadas Java con la GPU.
        GL.createCapabilities();

        // Ahora creamos los shaders (programas de la GPU) y la geometría del triángulo.
        crearShaders();
        crearTriangulo();
    }

    // =========================================================================
    // PASO 2: CREAR LOS SHADERS (programas que corren en la GPU)
    // =========================================================================
    //
    // ¿POR QUÉ HAY DOS SHADERS (vertexSrc y fragmentSrc)?
    // -------------------------------------------------
    // En OpenGL moderno la GPU dibuja en dos etapas:
    //   1. VERTEX SHADER: se ejecuta por cada VÉRTICE (en nuestro triángulo, 3 veces).
    //      Su trabajo es decir "dónde" va ese vértice en la pantalla (posición en 2D/3D).
    //   2. FRAGMENT SHADER: se ejecuta por cada PÍXEL que ocupa el triángulo (cientos o miles).
    //      Su trabajo es decir "de qué color" se pinta ese píxel.
    // Sin el vertex shader, la GPU no sabría dónde colocar los puntos. Sin el fragment shader,
    // no sabría qué color dar a cada píxel. Por eso hacen falta los dos.
    //
    // ¿EN QUÉ LENGUAJE ESTÁN ESCRITOS?
    // ---------------------------------
    // En GLSL (OpenGL Shading Language). Es un lenguaje parecido a C, creado para programar
    // la GPU. No es Java: son cadenas de texto (vertexSrc, fragmentSrc) que enviamos a
    // OpenGL; la GPU las compila y las ejecuta. Por eso en el código Java son simplemente
    // String con el código fuente en GLSL.
    //
    private void crearShaders() {
        // --- vertexSrc: código GLSL del VERTEX SHADER ---
        // Recibe la posición de cada vértice (aPos) y la asigna a gl_Position (salida estándar).
        // "layout (location = 0) in vec3 aPos" = entrada en el canal 0, 3 floats (x, y, z).
        String vertexSrc =
            "#version 330 core\n"
            + "layout (location = 0) in vec3 aPos;\n"
            + "void main() { gl_Position = vec4(aPos, 1.0); }\n";

        // Código del fragment shader en GLSL.
        // "fragColor" = color de salida del píxel. vec4(R, G, B, A) = rojo, verde, azul, alpha.
        // --- fragmentSrc: código GLSL del FRAGMENT SHADER ---
        // Se ejecuta por cada píxel del triángulo. "fragColor" es la salida (color del píxel).
        // vec4(R, G, B, A) = rojo, verde, azul, transparencia. (0.2, 0.8, 0.4, 1.0) = verde.
        String fragmentSrc =
            "#version 330 core\n"
            + "out vec4 fragColor;\n"
            + "void main() { fragColor = vec4(0.2, 0.8, 0.4, 1.0); }\n";

        // Crear el objeto "vertex shader" en la GPU y compilar el código.
        int vertexShader = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(vertexShader, vertexSrc);
        GL20.glCompileShader(vertexShader);
        comprobarShader(vertexShader, "Vertex");

        // Crear el objeto "fragment shader" en la GPU y compilar el código.
        int fragmentShader = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(fragmentShader, fragmentSrc);
        GL20.glCompileShader(fragmentShader);
        comprobarShader(fragmentShader, "Fragment");

        // Crear el programa (vertex + fragment unidos) y enlazarlos.
        programa = GL20.glCreateProgram();
        GL20.glAttachShader(programa, vertexShader);
        GL20.glAttachShader(programa, fragmentShader);
        GL20.glLinkProgram(programa);

        // Comprobar que el enlace fue correcto (si hay error, los shaders no son compatibles, etc.).
        if (GL20.glGetProgrami(programa, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException("Error al enlazar programa: " + GL20.glGetProgramInfoLog(programa));
        }

        // Los shaders ya están copiados dentro del programa; podemos borrarlos para liberar.
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
    }

    // Si el shader no compiló bien (error de sintaxis en GLSL, etc.), lanzamos excepción con el mensaje.
    private void comprobarShader(int shader, String tipo) {
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new RuntimeException(tipo + " shader: " + GL20.glGetShaderInfoLog(shader));
        }
    }

    // =========================================================================
    // PASO 3: CREAR EL TRIÁNGULO (geometría y buffers en la GPU)
    // =========================================================================
    // Un triángulo son 3 puntos (vértices). Cada vértice tiene posición (x, y, z).
    // En OpenGL las coordenadas suelen ir de -1 a 1: (-1,-1) = esquina inferior izquierda,
    // (1,1) = esquina superior derecha. El centro es (0, 0).
    // Guardamos las 3 posiciones en un buffer (VBO) en la GPU y le decimos al VAO
    // cómo interpretar ese buffer (cuántos floats por vértice, etc.).
    private void crearTriangulo() {
        // 3 vértices, cada uno con (x, y, z). Forman un triángulo con la punta arriba.
        float[] vertices = {
             0.0f,  0.5f, 0.0f,   // vértice 1: arriba al centro
            -0.5f, -0.5f, 0.0f,   // vértice 2: abajo a la izquierda
             0.5f, -0.5f, 0.0f    // vértice 3: abajo a la derecha
        };

        // VAO: objeto que guarda la configuración de los buffers y atributos.
        // Más adelante, con solo hacer "bind" de este VAO, OpenGL sabe qué triángulo dibujar.
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);

        // VBO: buffer en la GPU donde guardamos los números (las coordenadas).
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);

        // OpenGL espera los datos en un FloatBuffer, no en un float[] de Java.
        // Copiamos el array al buffer y "flip()" para dejarlo listo para lectura.
        FloatBuffer buffer = BufferUtils.createFloatBuffer(vertices.length);
        buffer.put(vertices).flip();
        // Enviar los datos al VBO. GL_STATIC_DRAW = "no vamos a cambiar estos datos" (optimización).
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW);

        // Decir a OpenGL cómo leer el buffer:
        //   atributo en location 0 (como "layout (location = 0)" en el vertex shader),
        //   3 floats por vértice (x, y, z),
        //   tipo GL_FLOAT, sin normalizar,
        //   stride = 12 bytes (3 floats × 4 bytes), offset = 0.
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        // Activar el atributo 0 (en el shader es "in vec3 aPos" en location 0).
        GL20.glEnableVertexAttribArray(0);

        // Desenlazar VBO y VAO. El VAO ya guardó la configuración; no hace falta dejarlos activos.
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    // =========================================================================
    // BUCLE PRINCIPAL: en cada iteración dibujamos un frame
    // =========================================================================
    // Mientras la ventana no se cierre:
    //   1. Limpiamos la pantalla con un color de fondo.
    //   2. Decimos "usa este programa de shaders y este VAO (triángulo)".
    //   3. Ordenamos dibujar 3 vértices en modo triángulo.
    //   4. Intercambiamos buffers (doble buffer: dibujamos en uno y mostramos el otro).
    //   5. Procesamos eventos (cerrar ventana, teclado, etc.).
    private void loop() {
        while (!GLFW.glfwWindowShouldClose(window)) {
            // Color con el que limpiar la pantalla (gris oscuro: R, G, B, A).
            GL11.glClearColor(0.08f, 0.08f, 0.12f, 1.0f);
            // Limpiar el buffer de color (rellenar con el color anterior).
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Activar nuestro programa de shaders (vertex + fragment).
            GL20.glUseProgram(programa);
            // Activar el VAO que contiene el triángulo (posición de los 3 vértices).
            GL30.glBindVertexArray(vao);
            // Orden de dibujo: modo GL_TRIANGLES, empezar en el vértice 0, dibujar 3 vértices.
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            // Doble buffer: hemos dibujado en un buffer "oculto"; ahora lo mostramos en pantalla
            // e intercambiamos. Así el usuario no ve líneas a medias.
            GLFW.glfwSwapBuffers(window);
            // Procesar eventos del sistema (si el usuario cerró la ventana, pulsó una tecla, etc.).
            GLFW.glfwPollEvents();
        }
    }

    // =========================================================================
    // LIMPIEZA: liberar recursos y cerrar GLFW
    // =========================================================================
    private void cleanup() {
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vbo);
        GL20.glDeleteProgram(programa);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }

    public static void main(String[] args) {
        new App().run();
    }
}
