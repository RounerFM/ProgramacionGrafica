# OpenGL Java Class (LWJGL)

Proyecto base de OpenGL en Java usando **LWJGL + GLFW**, con dos entradas:

- `com.graphics.App` (triángulo básico)
- `com.graphics.AppMovimientoTeclado` (triángulo movible con teclado)

## Requisitos

- Java 17 o superior
- Maven 3.9+
- macOS (este `pom.xml` ya incluye `natives-macos` y `natives-macos-arm64`)

## 1) Crear un proyecto Maven (desde cero)

Si quieres crear un proyecto nuevo igual a este formato:

```bash
mvn archetype:generate \
  -DgroupId=com.graphics \
  -DartifactId=opengl-java-class \
  -DarchetypeArtifactId=maven-archetype-quickstart \
  -DinteractiveMode=false
```

Luego entra al proyecto:

```bash
cd opengl-java-class
```

Después debes:

1. Reemplazar el `pom.xml` por uno con dependencias de LWJGL/GLFW/OpenGL.
2. Crear las clases en `src/main/java/com/graphics/`.

## 2) Ubicarte en este proyecto

En este repo en particular, la carpeta que contiene el `pom.xml` es:

```bash
cd "/Users/kenjikv/Documents/Personal/Personal/ProgramacionGrafica/OpenGL/Clase 01/opengl-java-class/opengl-java-class"
```

## 3) Compilar

```bash
mvn compile
```

## 4) Ejecutar cada app por separado

### Ejecutar `App` (triángulo base)

```bash
mvn compile exec:exec -DmainClass=com.graphics.App
```

Tambien puedes ejecutarla con la clase por defecto definida en `pom.xml`:

```bash
mvn exec:exec
```

### Ejecutar `AppMovimientoTeclado` (mover con WASD/flechas)

```bash
mvn compile exec:exec -DmainClass=com.graphics.AppMovimientoTeclado
```

## Controles en `AppMovimientoTeclado`

- `W` / `Flecha Arriba`: mover arriba
- `S` / `Flecha Abajo`: mover abajo
- `A` / `Flecha Izquierda`: mover izquierda
- `D` / `Flecha Derecha`: mover derecha
- `ESC`: cerrar ventana

## Problema comun: "no encuentra POM"

Si ves un error de Maven indicando que no hay `pom.xml`, estas ejecutando en la carpeta incorrecta.
Debes ejecutar los comandos dentro de:

`.../opengl-java-class/opengl-java-class`
